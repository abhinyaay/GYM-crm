import {
  Alert, Badge, Button, Code, Group, Modal, Paper, PasswordInput, ScrollArea, SegmentedControl, Select, SimpleGrid, Stack,
  Switch, Table, Tabs, Text, Textarea, TextInput, ThemeIcon, Title, Tooltip,
} from '@mantine/core';
import {
  IconAlarm, IconCheck, IconCloudUpload, IconDoorExit, IconFingerprint, IconHeartbeat, IconId, IconKey, IconLock, IconLockOpen,
  IconQrcode, IconRefresh, IconShieldCheck, IconShieldX, IconUserScan, IconWifiOff, IconX,
} from '@tabler/icons-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  demoCardUid, REASON_TEXT, type AccessReason, type Allowlist, type CredentialType, type Decision, type Device,
  type DeviceSecret, type Direction, type EnrolledCredential, type GateCommand, type GateDecision, type HeartbeatResponse,
} from '@/api/access';
import { ApiError } from '@/api/client';
import { useAuth } from '@/auth/AuthContext';
import { hasAnyRole, MANAGERS } from '@/auth/roles';
import { MemberSearch } from '@/components/MemberSearch';
import { PageHeader } from '@/components/PageHeader';
import { fromNow, label, time } from '@/lib/format';
import { cardHash, gateRequest, normalizeCardUid, templateHash, type GateIdentity } from '@/lib/gateClient';
import { errorMessage, notifySuccess } from '@/lib/notify';
import { useFeatures } from '@/lib/features';
import { useApiMutation, useApiQuery } from '@/lib/useApi';

const SECRET_KEY = (uid: string) => `gymops.gate.secret.${uid}`;

interface ScanInput {
  type: CredentialType;
  value: string;
  hint: string;
}

interface Display {
  state: 'idle' | 'granted' | 'denied' | 'error';
  title: string;
  detail?: string;
  name?: string | null;
  openUntil?: number;
}

interface LogRow {
  at: string;
  credential: string;
  direction: Direction;
  decision: Decision | 'ERROR';
  reason: string;
  ms?: number;
  signed?: boolean;
  offline?: boolean;
}

interface BufferedEvent {
  deviceEventId: string;
  credentialType: CredentialType;
  credentialValue: string;
  direction: Direction;
  occurredAt: string;
  offlineDecision: Decision;
  offlineReason?: AccessReason;
}

function readSecret(uid: string): string {
  try {
    return sessionStorage.getItem(SECRET_KEY(uid)) ?? '';
  } catch {
    return '';
  }
}

function writeSecret(uid: string, secret: string) {
  try {
    if (secret) sessionStorage.setItem(SECRET_KEY(uid), secret);
    else sessionStorage.removeItem(SECRET_KEY(uid));
  } catch { /* storage unavailable: secret lives in memory only */ }
}

const newEventId = (prefix = 'sim') => `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;

/**
 * A turnstile / kiosk simulator that speaks the real, signed device protocol from the browser (WebCrypto HMAC).
 * The device secret is pasted by the operator (or obtained by a manager via "rotate & use") and kept only in this
 * tab's sessionStorage. Nothing here is privileged: the server treats these requests exactly like a physical gate.
 */
export default function GateSimulatorPage() {
  const { user } = useAuth();
  const manager = hasAnyRole(user, MANAGERS);
  const [params, setParams] = useSearchParams();
  const devices = useApiQuery<Device[]>('/api/v1/access/devices');
  const deviceId = params.get('device');
  const device = (devices.data ?? []).find((d) => String(d.id) === deviceId) ?? null;
  const [secret, setSecret] = useState('');
  const [direction, setDirection] = useState<Direction>('ENTRY');
  const [display, setDisplay] = useState<Display>({ state: 'idle', title: 'Present card, finger or QR code' });
  const [now, setNow] = useState(() => Date.now());
  const [log, setLog] = useState<LogRow[]>([]);
  const [offline, setOffline] = useState(false);
  const [allowlist, setAllowlist] = useState<{ list: Allowlist; signed: boolean } | null>(null);
  const [buffer, setBuffer] = useState<BufferedEvent[]>([]);
  const [heartbeat, setHeartbeat] = useState<{ at: string; ok: boolean; info: string } | null>(null);
  const [autoHeartbeat, setAutoHeartbeat] = useState(true);
  const [commands, setCommands] = useState<GateCommand[]>([]);
  const [rotateOpen, setRotateOpen] = useState(false);
  const [busy, setBusy] = useState(false);

  const deviceUid = device?.deviceUid;
  const deviceDirection = device?.direction;
  useEffect(() => {
    setSecret(deviceUid ? readSecret(deviceUid) : '');
    setDirection(deviceDirection === 'EXIT' ? 'EXIT' : 'ENTRY');
    setAllowlist(null);
    setBuffer([]);
    setCommands([]);
    setHeartbeat(null);
  }, [deviceUid, deviceDirection]);

  // drives the "unlocked for N s" countdown
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 250);
    return () => clearInterval(t);
  }, []);

  const gate: GateIdentity | null = device && secret.length > 0 ? { deviceUid: device.deviceUid, secret } : null;

  const sendHeartbeat = useCallback(async () => {
    if (!gate) return;
    try {
      const r = await gateRequest<HeartbeatResponse>(gate, 'POST', '/api/v1/access/gate/heartbeat',
        { firmwareVersion: 'sim-1.0', uptimeSeconds: Math.round(performance.now() / 1000), offlineQueueDepth: 0 });
      setHeartbeat({ at: new Date().toISOString(), ok: true,
        info: `${r.data.pendingCommands} pending command(s) · signature ${r.signatureValid ? 'verified' : 'INVALID'}` });
      if (r.data.pendingCommands > 0) {
        const c = await gateRequest<{ commands: GateCommand[] }>(gate, 'GET', '/api/v1/access/gate/commands');
        setCommands(c.data.commands);
      }
    } catch (e) {
      setHeartbeat({ at: new Date().toISOString(), ok: false, info: errorMessage(e) });
    }
    // gate identity is a value object rebuilt each render; key the callback on its fields
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [gate?.deviceUid, gate?.secret]);

  const heartbeatRef = useRef(sendHeartbeat);
  heartbeatRef.current = sendHeartbeat;
  useEffect(() => {
    if (!gate || !autoHeartbeat || offline) return undefined;
    void heartbeatRef.current();
    const t = setInterval(() => void heartbeatRef.current(), 30_000);
    return () => clearInterval(t);
  }, [gate?.deviceUid, gate?.secret, autoHeartbeat, offline]); // eslint-disable-line react-hooks/exhaustive-deps

  const show = (d: GateDecision | null, input: ScanInput, err?: unknown, ms?: number, signed?: boolean) => {
    const at = new Date().toISOString();
    if (err || !d) {
      const msg = err instanceof ApiError && err.status === 401 ? 'Device authentication failed: check the secret'
        : errorMessage(err);
      setDisplay({ state: 'error', title: 'GATE ERROR', detail: msg });
      setLog((l) => [{ at, credential: input.hint, direction, decision: 'ERROR' as const, reason: msg }, ...l].slice(0, 50));
      return;
    }
    const reason = d.reasonCode ? REASON_TEXT[d.reasonCode] ?? d.reasonCode : '';
    setDisplay(d.decision === 'GRANTED'
      ? { state: 'granted', title: d.direction === 'ENTRY' ? 'WELCOME' : 'GOODBYE', name: d.memberDisplayName,
        detail: d.reasonCode && d.reasonCode !== 'OK' ? reason : d.offline ? 'Admitted from offline allowlist' : undefined,
        openUntil: Date.now() + (d.openSeconds || 5) * 1000 }
      : { state: 'denied', title: 'DENIED', name: d.memberDisplayName, detail: d.message ?? reason });
    setLog((l) => [{ at, credential: input.hint, direction: d.direction, decision: d.decision, reason: reason || '—', ms,
      signed, offline: d.offline }, ...l].slice(0, 50));
  };

  const scan = async (input: ScanInput) => {
    if (!gate || !device) return;
    if (offline) {
      const local = await decideOffline(input);
      show(local, input, undefined, 0, allowlist?.signed);
      return;
    }
    setBusy(true);
    try {
      const r = await gateRequest<GateDecision>(gate, 'POST', '/api/v1/access/gate/events', {
        deviceEventId: newEventId(), credentialType: input.type, credentialValue: input.value, direction,
      });
      show(r.data, input, undefined, r.durationMs, r.signatureValid);
    } catch (e) {
      show(null, input, e);
    } finally {
      setBusy(false);
    }
  };

  /** What a real gate does when the network is down: match against the signed allowlist and buffer the event. */
  const decideOffline = async (input: ScanInput): Promise<GateDecision> => {
    let granted = false;
    let reason: AccessReason = 'UNKNOWN_CREDENTIAL';
    if (input.type === 'QR') {
      reason = 'OFFLINE_DENIED';
    } else if (allowlist) {
      const hash = input.type === 'RFID_CARD' ? await cardHash(input.value) : await templateHash(input.type, input.value);
      const entry = allowlist.list.entries.find((e) => e.credentialType === input.type && e.credentialHash === hash);
      const t = new Date();
      if (entry && new Date(entry.validFrom) <= t && t < new Date(entry.validUntil)) {
        const hhmm = t.toTimeString().slice(0, 5);
        const inWindow = !entry.dailyAccessStart || (hhmm >= entry.dailyAccessStart.slice(0, 5)
          && hhmm < (entry.dailyAccessEnd ?? '24:00').slice(0, 5));
        granted = inWindow;
        reason = inWindow ? 'OK' : 'OUTSIDE_PLAN_HOURS';
      }
    }
    const ev: BufferedEvent = {
      deviceEventId: newEventId('off'), credentialType: input.type, credentialValue: input.value, direction,
      occurredAt: new Date().toISOString(), offlineDecision: granted ? 'GRANTED' : 'DENIED',
      ...(granted ? {} : { offlineReason: reason }),
    };
    setBuffer((b) => [...b, ev]);
    return { eventId: null, deviceEventId: ev.deviceEventId, decision: ev.offlineDecision, reasonCode: reason,
      message: granted ? 'Offline: admitted from allowlist' : `Offline: ${REASON_TEXT[reason]}`, openGate: granted,
      openSeconds: 5, memberDisplayName: null, direction, offline: true, antiPassbackViolation: false, replayed: false,
      serverTime: new Date().toISOString() };
  };

  const downloadAllowlist = async () => {
    if (!gate) return;
    try {
      const r = await gateRequest<Allowlist>(gate, 'GET', '/api/v1/access/gate/allowlist');
      setAllowlist({ list: r.data, signed: r.signatureValid });
      notifySuccess(`Allowlist: ${r.data.entries.length} credentials, valid until ${new Date(r.data.validUntil).toLocaleString()}`
        + (r.signatureValid ? ' (signature verified)' : ' (SIGNATURE INVALID)'));
    } catch (e) {
      setDisplay({ state: 'error', title: 'GATE ERROR', detail: errorMessage(e) });
    }
  };

  const uploadBuffer = async () => {
    if (!gate || buffer.length === 0) return;
    try {
      const r = await gateRequest<{ processed: number; results: GateDecision[] }>(gate, 'POST',
        '/api/v1/access/gate/events/batch', { events: buffer });
      setBuffer([]);
      setOffline(false);
      const flagged = r.data.results.filter((x) => x.reasonCode && x.reasonCode !== 'OK').length;
      notifySuccess(`Uploaded ${r.data.processed} offline event(s); ${flagged} flagged by the server during reconciliation`);
    } catch (e) {
      setDisplay({ state: 'error', title: 'UPLOAD FAILED', detail: errorMessage(e) });
    }
  };

  const raiseAlarm = async (type: 'DOOR_FORCED' | 'DOOR_HELD_OPEN' | 'TAILGATING') => {
    if (!gate) return;
    try {
      await gateRequest(gate, 'POST', '/api/v1/access/gate/alarms', { deviceEventId: newEventId('alarm'), type,
        details: 'Raised from the gate simulator' });
      notifySuccess(`${label(type)} alarm sent; branch managers are notified`);
    } catch (e) {
      setDisplay({ state: 'error', title: 'GATE ERROR', detail: errorMessage(e) });
    }
  };

  /** Push-button / free exit: no credential, always opens (fire safety); logged as an anonymous exit. */
  const pressExitButton = async () => {
    if (!gate) return;
    setBusy(true);
    try {
      const r = await gateRequest<GateDecision>(gate, 'POST', '/api/v1/access/gate/exit-button', { deviceEventId: newEventId('btn') });
      show(r.data, { type: 'RFID_CARD', value: '', hint: 'Exit button' }, undefined, r.durationMs, r.signatureValid);
    } catch (e) {
      setDisplay({ state: 'error', title: 'GATE ERROR', detail: errorMessage(e) });
    } finally {
      setBusy(false);
    }
  };

  const unlocked = display.state === 'granted' && (display.openUntil ?? 0) > now;
  const secondsLeft = unlocked ? Math.ceil(((display.openUntil ?? 0) - now) / 1000) : 0;

  return (
    <>
      <PageHeader title="Gate simulator"
        subtitle="A turnstile that talks to GymOps exactly like real gate firmware: HMAC-signed requests, signed responses" />

      <SimpleGrid cols={{ base: 1, lg: 3 }} spacing="md">
        <Stack gap="md">
          <Paper withBorder p="md">
            <Title order={5} mb="sm">Device</Title>
            <Stack gap="sm">
              <Select label="Gate device" placeholder="Choose a device" searchable value={deviceId}
                onChange={(v) => setParams(v ? { device: v } : {})}
                data={(devices.data ?? []).map((d) => ({ value: String(d.id), label: `${d.name} · ${d.branchName}` }))} />
              {device && (
                <>
                  <Group gap={6}>
                    <Badge variant="light">{label(device.type)}</Badge>
                    <Badge variant="light" color="grape">{label(device.direction)}</Badge>
                    {device.status === 'DISABLED' && <Badge color="red">Disabled</Badge>}
                    <Code fz="xs">{device.deviceUid}</Code>
                  </Group>
                  <PasswordInput label="Device secret" description="Kept only in this browser tab (sessionStorage)."
                    placeholder="64 hex characters" value={secret}
                    onChange={(e) => {
                      const v = e.currentTarget.value.trim();
                      setSecret(v);
                      writeSecret(device.deviceUid, v);
                    }} />
                  {manager && (
                    <Button size="xs" variant="subtle" leftSection={<IconKey size={14} />} onClick={() => setRotateOpen(true)}>
                      Lost the secret? Rotate &amp; use a new one
                    </Button>
                  )}
                  {device.direction === 'BOTH' && (
                    <SegmentedControl fullWidth value={direction} onChange={(v) => setDirection(v as Direction)}
                      data={[{ value: 'ENTRY', label: 'Entry side' }, { value: 'EXIT', label: 'Exit side' }]} />
                  )}
                </>
              )}
            </Stack>
          </Paper>

          {gate && (
            <Paper withBorder p="md">
              <Group justify="space-between" mb="xs">
                <Title order={5}>Health &amp; network</Title>
                <Switch size="xs" label="Auto heartbeat" checked={autoHeartbeat}
                  onChange={(e) => setAutoHeartbeat(e.currentTarget.checked)} />
              </Group>
              <Group gap="xs" mb="sm">
                <Button size="xs" variant="light" leftSection={<IconHeartbeat size={14} />} disabled={offline}
                  onClick={() => void sendHeartbeat()}>Heartbeat</Button>
                {heartbeat && (
                  <Text size="xs" c={heartbeat.ok ? 'teal' : 'red'}>{heartbeat.ok ? 'OK' : 'Failed'} {fromNow(heartbeat.at)}: {heartbeat.info}</Text>
                )}
              </Group>
              <Group gap="xs" mb="xs">
                <Button size="xs" variant="light" leftSection={<IconRefresh size={14} />} disabled={offline}
                  onClick={() => void downloadAllowlist()}>Download offline allowlist</Button>
                {allowlist && (
                  <Badge color={allowlist.signed ? 'teal' : 'red'} variant="light"
                    leftSection={allowlist.signed ? <IconShieldCheck size={12} /> : <IconShieldX size={12} />}>
                    {allowlist.list.entries.length} entries
                  </Badge>
                )}
              </Group>
              <Switch label="Simulate network outage (decide locally from the allowlist)" checked={offline}
                disabled={!allowlist && !offline} onChange={(e) => setOffline(e.currentTarget.checked)}
                thumbIcon={offline ? <IconWifiOff size={10} /> : undefined} />
              {!allowlist && <Text size="xs" c="dimmed" mt={4}>Download the allowlist first to go offline.</Text>}
              {buffer.length > 0 && (
                <Button mt="sm" size="xs" leftSection={<IconCloudUpload size={14} />} onClick={() => void uploadBuffer()}>
                  Reconnect &amp; upload {buffer.length} buffered event(s)
                </Button>
              )}
            </Paper>
          )}

          {gate && <CommandsPanel gate={gate} commands={commands} setCommands={setCommands} offline={offline} />}
        </Stack>

        <Stack gap="md">
          <GateDisplay display={display} unlocked={unlocked} secondsLeft={secondsLeft} offline={offline} />
          {gate && !offline && (
            <Paper withBorder p="md">
              <Title order={6} mb="xs">Exit button &amp; door sensors</Title>
              <Group gap="xs">
                <Button size="xs" variant="filled" color="teal" leftSection={<IconDoorExit size={14} />} loading={busy}
                  onClick={() => void pressExitButton()}>Press exit button</Button>
                <Button size="xs" color="red" variant="light" leftSection={<IconAlarm size={14} />}
                  onClick={() => void raiseAlarm('DOOR_FORCED')}>Door forced</Button>
                <Button size="xs" color="orange" variant="light" onClick={() => void raiseAlarm('DOOR_HELD_OPEN')}>Held open</Button>
                <Button size="xs" color="orange" variant="light" onClick={() => void raiseAlarm('TAILGATING')}>Tailgating</Button>
              </Group>
            </Paper>
          )}
        </Stack>

        <Stack gap="md">
          {device ? (
            gate ? <Presenter branchId={device.branchId} busy={busy} onScan={(i) => void scan(i)} />
              : <Alert color="blue" title="Paste the device secret">
                  Every gate request is signed with the device&apos;s secret. It was shown once when the device was registered
                  {manager ? '; you can also rotate it here.' : '.'}
                </Alert>
          ) : <Alert color="blue" title="Choose a device">Pick the turnstile you want to stand at.</Alert>}
        </Stack>
      </SimpleGrid>

      <Paper withBorder p="md" mt="md">
        <Title order={5} mb="sm">This session</Title>
        {log.length === 0 ? <Text size="sm" c="dimmed">No scans yet.</Text> : (
          <ScrollArea>
            <Table fz="xs" striped>
              <Table.Thead>
                <Table.Tr><Table.Th>Time</Table.Th><Table.Th>Credential</Table.Th><Table.Th>Dir.</Table.Th>
                  <Table.Th>Decision</Table.Th><Table.Th>Reason</Table.Th><Table.Th>Latency</Table.Th><Table.Th>Response signature</Table.Th></Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {log.map((r, i) => (
                  <Table.Tr key={`${r.at}-${i}`}>
                    <Table.Td>{time(r.at)}</Table.Td>
                    <Table.Td>{r.credential}</Table.Td>
                    <Table.Td>{r.direction === 'ENTRY' ? 'In' : 'Out'}</Table.Td>
                    <Table.Td>
                      <Badge size="sm" variant="light" color={r.decision === 'GRANTED' ? 'teal' : r.decision === 'DENIED' ? 'red' : 'gray'}>
                        {r.decision}
                      </Badge>
                      {r.offline && <Badge size="xs" ml={4} variant="outline" color="gray">offline</Badge>}
                    </Table.Td>
                    <Table.Td>{r.reason}</Table.Td>
                    <Table.Td>{r.ms != null ? `${r.ms} ms` : '—'}</Table.Td>
                    <Table.Td>{r.signed == null ? '—' : r.signed ? '✓ verified' : '✗ invalid'}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </ScrollArea>
        )}
      </Paper>

      {device && (
        <RotateAndUse device={device} opened={rotateOpen} onClose={() => setRotateOpen(false)}
          onSecret={(s) => {
            setSecret(s);
            writeSecret(device.deviceUid, s);
          }} />
      )}
    </>
  );
}

// ------------------------------------------------------------------ gate display

function GateDisplay({ display, unlocked, secondsLeft, offline }: {
  display: Display; unlocked: boolean; secondsLeft: number; offline: boolean;
}) {
  const palette = display.state === 'granted' && unlocked ? { bg: 'var(--mantine-color-teal-7)', fg: 'white' }
    : display.state === 'denied' ? { bg: 'var(--mantine-color-red-7)', fg: 'white' }
      : display.state === 'error' ? { bg: 'var(--mantine-color-orange-7)', fg: 'white' }
        : { bg: 'var(--mantine-color-dark-6)', fg: 'var(--mantine-color-gray-3)' };
  const idle = display.state === 'idle' || (display.state === 'granted' && !unlocked);
  return (
    <Paper radius="lg" p="xl" style={{ background: palette.bg, color: palette.fg, minHeight: 300, transition: 'background 200ms' }}>
      <Stack align="center" justify="center" gap="sm" h="100%" mih={260}>
        {idle ? (
          <>
            <ThemeIcon size={72} radius="xl" color="gray" variant="light"><IconLock size={40} /></ThemeIcon>
            <Text fz={28} fw={800} ta="center">LOCKED</Text>
            <Text ta="center">Present card, finger or QR code</Text>
          </>
        ) : (
          <>
            <ThemeIcon size={72} radius="xl" color="white" variant="white"
              c={display.state === 'granted' ? 'teal' : display.state === 'denied' ? 'red' : 'orange'}>
              {display.state === 'granted' ? <IconLockOpen size={40} /> : <IconX size={40} />}
            </ThemeIcon>
            <Text fz={40} fw={900} ta="center" lh={1}>{display.state === 'granted' ? 'OPEN' : display.title}</Text>
            {display.state === 'granted' && <Text fz={22} fw={700}>{display.title}{display.name ? `, ${display.name}` : ''}</Text>}
            {display.state !== 'granted' && display.name && <Text fz={18} fw={600}>{display.name}</Text>}
            {display.detail && <Text ta="center" size="sm">{display.detail}</Text>}
            {unlocked && <Badge size="lg" color="white" c="teal" variant="white">Turnstile unlocked · {secondsLeft}s</Badge>}
          </>
        )}
        {offline && <Badge color="yellow" variant="filled" leftSection={<IconWifiOff size={12} />}>OFFLINE MODE</Badge>}
      </Stack>
    </Paper>
  );
}

// ------------------------------------------------------------------ credential presenter

function Presenter({ branchId, busy, onScan }: { branchId: number; busy: boolean; onScan: (i: ScanInput) => void }) {
  const { biometrics } = useFeatures();
  const enrolled = useApiQuery<EnrolledCredential[]>('/api/v1/access/credentials/enrolled',
    { branchId, types: 'FINGERPRINT,FACE' });
  const [template, setTemplate] = useState<string | null>(null);
  const [customRef, setCustomRef] = useState('');
  const [uid, setUid] = useState('');
  const [memberId, setMemberId] = useState<number | null>(null);
  const [qr, setQr] = useState('');
  const templates = useMemo(() => enrolled.data ?? [], [enrolled.data]);
  const selected = templates.find((t) => String(t.credentialId) === template);
  const options = useMemo(() => templates.map((t) => ({ value: String(t.credentialId),
    label: `${t.memberName} · ${t.memberCode} · ${label(t.type)} ${t.displayHint ?? ''}` })), [templates]);
  return (
    <Paper withBorder p="md">
      <Title order={5} mb="sm">Present a credential</Title>
      <Tabs defaultValue="card">
        <Tabs.List grow mb="md">
          <Tabs.Tab value="card" leftSection={<IconId size={14} />}>Card</Tabs.Tab>
          {biometrics && <Tabs.Tab value="bio" leftSection={<IconFingerprint size={14} />}>Biometric</Tabs.Tab>}
          <Tabs.Tab value="qr" leftSection={<IconQrcode size={14} />}>QR</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="card">
          <Stack gap="sm">
            <TextInput label="Card UID" placeholder="04:A1:B2:C3" value={uid} onChange={(e) => setUid(e.currentTarget.value)} />
            <MemberSearch label="…or a demo member's card" value={memberId} branchId={branchId}
              onChange={(id) => {
                setMemberId(id);
                if (id) setUid(demoCardUid(id));
              }} description="Seeded demo cards use UID D3 + member id in hex" />
            <Button leftSection={<IconId size={16} />} disabled={normalizeCardUid(uid).length < 4} loading={busy}
              onClick={() => onScan({ type: 'RFID_CARD', value: uid, hint: `Card …${normalizeCardUid(uid).slice(-4)}` })}>
              Tap card
            </Button>
          </Stack>
        </Tabs.Panel>
        <Tabs.Panel value="bio">
          <Stack gap="sm">
            <Text size="xs" c="dimmed">
              A real gate matches the finger / face on the device and reports only the matched template reference. Pick an
              enrolled member to simulate a match.
            </Text>
            <Select label="Enrolled template (this branch)" searchable clearable value={template} onChange={setTemplate}
              data={options} nothingFoundMessage="No biometric enrolments at this branch" />
            <Button leftSection={selected?.type === 'FACE' ? <IconUserScan size={16} /> : <IconFingerprint size={16} />}
              disabled={!selected?.templateRef} loading={busy}
              onClick={() => selected?.templateRef && onScan({ type: selected.type, value: selected.templateRef,
                hint: `${label(selected.type)} ${selected.memberName}` })}>
              {selected?.type === 'FACE' ? 'Show face' : 'Place finger'}
            </Button>
            <Group gap="xs" align="flex-end">
              <TextInput style={{ flex: 1 }} label="Other template ref" placeholder="FP-..." value={customRef}
                onChange={(e) => setCustomRef(e.currentTarget.value)} />
              <Button variant="light" disabled={!customRef.trim()} loading={busy}
                onClick={() => onScan({ type: 'FINGERPRINT', value: customRef.trim(), hint: `Fingerprint ${customRef.trim()}` })}>
                Match
              </Button>
            </Group>
          </Stack>
        </Tabs.Panel>
        <Tabs.Panel value="qr">
          <Stack gap="sm">
            <Textarea label="QR code content" description="Copy it from the member app (Entry pass); it rotates every 30 s."
              placeholder="GQ1...." autosize minRows={2} value={qr} onChange={(e) => setQr(e.currentTarget.value)} />
            <Button leftSection={<IconQrcode size={16} />} disabled={!qr.trim()} loading={busy}
              onClick={() => onScan({ type: 'QR', value: qr.trim(), hint: 'QR pass' })}>
              Scan QR
            </Button>
          </Stack>
        </Tabs.Panel>
      </Tabs>
    </Paper>
  );
}

// ------------------------------------------------------------------ device commands

function CommandsPanel({ gate, commands, setCommands, offline }: {
  gate: GateIdentity; commands: GateCommand[]; setCommands: (c: GateCommand[]) => void; offline: boolean;
}) {
  const [error, setError] = useState<string | null>(null);
  const poll = async () => {
    try {
      const r = await gateRequest<{ commands: GateCommand[] }>(gate, 'GET', '/api/v1/access/gate/commands');
      setCommands(r.data.commands);
      setError(null);
    } catch (e) {
      setError(errorMessage(e));
    }
  };
  const ack = async (c: GateCommand, success: boolean) => {
    const rnd = Array.from(crypto.getRandomValues(new Uint8Array(3))).map((b) => b.toString(16).padStart(2, '0')).join('');
    const templateRef = c.type === 'ENROLL_TEMPLATE' && success
      ? `${c.credentialType === 'FACE' ? 'FACE' : 'FP'}-${gate.deviceUid.slice(3, 9).toUpperCase()}-${rnd.toUpperCase()}` : undefined;
    try {
      await gateRequest(gate, 'POST', `/api/v1/access/gate/commands/${c.id}/ack`, {
        success, templateRef, message: success ? 'done by simulator' : 'member could not be enrolled (simulated)' });
      notifySuccess(c.type === 'ENROLL_TEMPLATE'
        ? (success ? `Template captured on device: ${templateRef}` : 'Enrolment failure reported')
        : 'Template erased from the device');
      await poll();
    } catch (e) {
      setError(errorMessage(e));
    }
  };
  return (
    <Paper withBorder p="md">
      <Group justify="space-between" mb="xs">
        <Title order={5}>Device commands</Title>
        <Button size="xs" variant="subtle" disabled={offline} onClick={() => void poll()}>Check</Button>
      </Group>
      {error && <Text size="xs" c="red">{error}</Text>}
      {commands.length === 0 ? <Text size="xs" c="dimmed">No pending enrol / delete commands.</Text> : (
        <Stack gap={6}>
          {commands.map((c) => (
            <Paper key={c.id} withBorder p={8}>
              <Group justify="space-between" wrap="nowrap">
                <div>
                  <Text size="sm" fw={600}>{c.type === 'ENROLL_TEMPLATE' ? `Enrol ${label(c.credentialType)}` : 'Delete template'}</Text>
                  <Text size="xs" c="dimmed">Member {c.memberRef ?? '—'}{c.templateRef ? ` · ${c.templateRef}` : ''}</Text>
                </div>
                {c.type === 'ENROLL_TEMPLATE' ? (
                  <Group gap={4} wrap="nowrap">
                    <Tooltip label="The member presents their finger / face; the device stores the template locally">
                      <Button size="compact-xs" leftSection={<IconCheck size={12} />} onClick={() => void ack(c, true)}>Capture</Button>
                    </Tooltip>
                    <Button size="compact-xs" variant="light" color="red" onClick={() => void ack(c, false)}>Fail</Button>
                  </Group>
                ) : (
                  <Button size="compact-xs" variant="light" color="orange" onClick={() => void ack(c, true)}>Erase</Button>
                )}
              </Group>
            </Paper>
          ))}
        </Stack>
      )}
    </Paper>
  );
}

function RotateAndUse({ device, opened, onClose, onSecret }: {
  device: Device; opened: boolean; onClose: () => void; onSecret: (s: string) => void;
}) {
  const rotate = useApiMutation<void, DeviceSecret>({
    request: () => ({ path: `/api/v1/access/devices/${device.id}/rotate-secret` }),
    invalidate: ['/api/v1/access/devices'],
    successMessage: 'New secret issued and stored in this tab',
    onSuccess: (s) => {
      onSecret(s.deviceSecret);
      onClose();
    },
  });
  return (
    <Modal opened={opened} onClose={onClose} title="Rotate secret and use it here">
      <Text size="sm">
        Issues a new secret for <b>{device.name}</b> and stores it in this tab. Any real device still using the old secret
        is locked out until it receives the new one.
      </Text>
      <Group justify="flex-end" mt="md">
        <Button variant="default" onClick={onClose}>Cancel</Button>
        <Button color="orange" loading={rotate.isPending} onClick={() => rotate.mutate()}>Rotate &amp; use</Button>
      </Group>
    </Modal>
  );
}
