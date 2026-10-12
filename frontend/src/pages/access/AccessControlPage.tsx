import {
  ActionIcon, Alert, Anchor, Badge, Button, Code, CopyButton, Group, Indicator, Menu, Modal, Paper, ScrollArea,
  SegmentedControl, Select,
  SimpleGrid, Stack, Switch, Table, Tabs, Text, TextInput, Title, Tooltip,
} from '@mantine/core';
import { DatePickerInput } from '@mantine/dates';
import {
  IconAlertTriangle, IconCheck, IconCopy, IconDoorEnter, IconDoorExit, IconDots, IconHandStop,
  IconKey, IconPlus, IconRouter, IconScan,
} from '@tabler/icons-react';
import { keepPreviousData } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import {
  DEVICE_DIRECTIONS, DEVICE_TYPES, REASON_TEXT, type AccessEvent, type BranchAccessSettings, type ExitMode, type AccessReason, type AccessStats, type Alarm,
  type Decision, type Device, type DeviceDirection, type DeviceRequest, type DeviceSecret, type DeviceType,
} from '@/api/access';
import type { Page } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { hasAnyRole, MANAGERS, ORG_WIDE } from '@/auth/roles';
import { AccessEventTable } from '@/components/access/MemberAccessPanel';
import { BranchSelect } from '@/components/BranchSelect';
import { MemberSearch } from '@/components/MemberSearch';
import { PageHeader } from '@/components/PageHeader';
import { Pager } from '@/components/Pager';
import { QueryState } from '@/components/QueryState';
import { StatCard } from '@/components/StatCard';
import { StatusBadge } from '@/components/StatusBadge';
import { dateTime, fromNow, label, number } from '@/lib/format';
import { useApiMutation, useApiQuery } from '@/lib/useApi';

/**
 * Access control console: device health, live gate feed, alarms, today's numbers, the searchable event log and
 * device administration (register → secret shown once, rotate, disable).
 */
export default function AccessControlPage() {
  const { user } = useAuth();
  const manager = hasAnyRole(user, MANAGERS);
  const orgWide = hasAnyRole(user, ORG_WIDE);
  const [branchId, setBranchId] = useState<number | null>(null);
  const [creating, setCreating] = useState(false);
  const [secret, setSecret] = useState<DeviceSecret | null>(null);
  const scope = { branchId: branchId ?? undefined };
  const stats = useApiQuery<AccessStats>('/api/v1/access/stats', scope, { refetchInterval: 10_000 });
  const s = stats.data;
  const topDenial = s ? Object.entries(s.deniedByReason).sort((a, b) => b[1] - a[1])[0] : undefined;

  return (
    <>
      <PageHeader
        title="Access control"
        subtitle="Biometric, card and QR gates: live feed, device health and alarms"
        actions={
          <>
            <BranchSelect allowAll value={branchId} onChange={setBranchId} w={{ base: '100%', sm: 240 }}
              clearable={!orgWide} placeholder={orgWide ? 'Select branch' : 'All my branches'} />
            <Button component={Link} to="/access/gate" variant="light" leftSection={<IconScan size={16} />}>
              Gate simulator
            </Button>
            {manager && <Button leftSection={<IconPlus size={16} />} onClick={() => setCreating(true)}>Register device</Button>}
          </>
        }
      />

      <SimpleGrid cols={{ base: 2, md: 5 }} mb="md">
        <StatCard label="Entries today" value={s ? number(s.entriesGranted) : '…'} icon={<IconDoorEnter size={20} />}
          hint={s ? `${number(s.offlineEvents)} reconciled from offline` : undefined} />
        <StatCard label="Exits today" value={s ? number(s.exitsGranted) : '…'} icon={<IconDoorExit size={20} />} color="blue" />
        <StatCard label="Denied today" value={s ? number(s.denied) : '…'} icon={<IconHandStop size={20} />} color="red"
          hint={topDenial ? `Top: ${REASON_TEXT[topDenial[0] as AccessReason] ?? topDenial[0]} (${topDenial[1]})` : undefined} />
        <StatCard label="Devices online" value={s ? `${s.devicesOnline}/${s.devices}` : '…'} icon={<IconRouter size={20} />}
          color={s && s.devicesOffline > 0 ? 'orange' : 'teal'} hint={s && s.devicesOffline > 0 ? `${s.devicesOffline} offline` : undefined} />
        <StatCard label="Open alarms" value={s ? number(s.openAlarms) : '…'} icon={<IconAlertTriangle size={20} />}
          color={s && s.openAlarms > 0 ? 'red' : 'gray'} />
      </SimpleGrid>

      <Tabs defaultValue="live" keepMounted={false}>
        <Tabs.List mb="md">
          <Tabs.Tab value="live">Live</Tabs.Tab>
          <Tabs.Tab value="devices">Devices</Tabs.Tab>
          <Tabs.Tab value="log">Event log</Tabs.Tab>
          <Tabs.Tab value="settings">Entry &amp; exit settings</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="live">
          <SimpleGrid cols={{ base: 1, lg: 3 }} spacing="md">
            <div style={{ gridColumn: 'span 2' }}><LiveFeed branchId={branchId} /></div>
            <Stack gap="md">
              <DenialBreakdown stats={s} />
              <AlarmsPanel branchId={branchId} />
            </Stack>
          </SimpleGrid>
        </Tabs.Panel>
        <Tabs.Panel value="devices">
          <DevicesTable branchId={branchId} manager={manager} onSecret={setSecret} />
        </Tabs.Panel>
        <Tabs.Panel value="log">
          <EventLog branchId={branchId} />
        </Tabs.Panel>
        <Tabs.Panel value="settings">
          <ExitSettings manager={manager} />
        </Tabs.Panel>
      </Tabs>

      <DeviceFormModal opened={creating} onClose={() => setCreating(false)} defaultBranchId={branchId
        ?? (user?.branchIds.length === 1 ? user.branchIds[0] : null)} onCreated={setSecret} />
      <SecretModal secret={secret} onClose={() => setSecret(null)} />
    </>
  );
}

// ------------------------------------------------------------------ entry & exit settings

/** Per-branch choice: verify only entry (exit by push button) or verify exit too (scan out, anti-passback). */
function ExitSettings({ manager }: { manager: boolean }) {
  const settings = useApiQuery<BranchAccessSettings[]>('/api/v1/access/branches/settings');
  const update = useApiMutation<{ branchId: number; exitMode: ExitMode }, BranchAccessSettings>({
    request: ({ branchId, exitMode }) => ({ path: `/api/v1/access/branches/${branchId}/settings`, method: 'PUT', body: { exitMode } }),
    invalidate: ['/api/v1/access/branches'],
    successMessage: (s) => `${s.branchName}: ${s.exitMode === 'BUTTON' ? 'exit by push button' : 'members scan to exit'}`,
  });
  const rows = settings.data ?? [];
  return (
    <Stack gap="md">
      <Alert variant="light" color="blue" title="How members leave">
        <Text size="sm">
          <b>Exit button (free exit)</b>: only entry is verified at the gate. Members leave through the exit side or by
          pressing the exit button; their visit is closed automatically on their next entry or after the assumed visit
          time. <b>Scan to exit</b>: members scan their fingerprint / card / QR again to leave, and anti-passback stops
          a second entry without an exit (prevents pass sharing). The daily entry limit is set per plan on the Plans page.
          Exit buttons and emergency release always open the door, whatever is chosen here.
        </Text>
      </Alert>
      <QueryState isLoading={settings.isLoading} error={settings.error} empty={rows.length === 0} emptyText="No branches.">
        <Paper withBorder radius="md">
          <Table.ScrollContainer minWidth={620}>
          <Table verticalSpacing="sm">
            <Table.Thead>
              <Table.Tr><Table.Th>Branch</Table.Th><Table.Th>How members leave</Table.Th><Table.Th /></Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((r) => (
                <Table.Tr key={r.branchId}>
                  <Table.Td>
                    <Text size="sm" fw={600}>{r.branchName}</Text>
                    <Text size="xs" c="dimmed">{r.branchCode}{r.defaulted ? ' · default setting' : ''}</Text>
                  </Table.Td>
                  <Table.Td>
                    <SegmentedControl size="xs" disabled={!manager || update.isPending} value={r.exitMode}
                      onChange={(v) => update.mutate({ branchId: r.branchId, exitMode: v as ExitMode })}
                      data={[{ value: 'BUTTON', label: 'Exit button (free exit)' }, { value: 'VERIFIED', label: 'Scan to exit' }]} />
                  </Table.Td>
                  <Table.Td>
                    <Text size="xs" c="dimmed">
                      {r.exitMode === 'BUTTON'
                        ? `Visits close automatically after ${Math.round(r.buttonExitVisitMinutes / 60 * 10) / 10} h`
                        : 'Anti-passback on'}
                    </Text>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
          </Table.ScrollContainer>
        </Paper>
      </QueryState>
      {!manager && <Text size="xs" c="dimmed">Only managers and admins can change these settings.</Text>}
    </Stack>
  );
}

// ------------------------------------------------------------------ live feed

function LiveFeed({ branchId }: { branchId: number | null }) {
  const [paused, setPaused] = useState(false);
  const feed = useApiQuery<AccessEvent[]>('/api/v1/access/events/feed', { branchId: branchId ?? undefined, limit: 30 },
    { refetchInterval: paused ? false : 3000, placeholderData: keepPreviousData });
  const events = feed.data ?? [];
  return (
    <Paper withBorder p="md">
      <Group justify="space-between" mb="sm">
        <Group gap="xs">
          <Indicator processing={!paused} color={paused ? 'gray' : 'teal'} size={10} position="middle-start" offset={-2}>
            <Title order={5} pl="md">Live gate feed</Title>
          </Indicator>
        </Group>
        <Switch size="xs" label="Auto-refresh" checked={!paused} onChange={(e) => setPaused(!e.currentTarget.checked)} />
      </Group>
      <QueryState isLoading={feed.isLoading} error={feed.error} empty={events.length === 0}
        emptyText="No gate activity yet. Open the gate simulator to scan a card, finger or QR code.">
        <ScrollArea.Autosize mah={520}>
          <AccessEventTable events={events} />
        </ScrollArea.Autosize>
      </QueryState>
    </Paper>
  );
}

function DenialBreakdown({ stats }: { stats: AccessStats | undefined }) {
  const entries = stats ? Object.entries(stats.deniedByReason).sort((a, b) => b[1] - a[1]) : [];
  const credentials = stats ? Object.entries(stats.grantedByCredential).sort((a, b) => b[1] - a[1]) : [];
  const max = Math.max(1, ...entries.map(([, v]) => v));
  return (
    <Paper withBorder p="md">
      <Title order={5} mb="sm">Denials today by reason</Title>
      {entries.length === 0 ? <Text size="sm" c="dimmed">No denials today.</Text> : (
        <Stack gap={6}>
          {entries.map(([reason, count]) => (
            <div key={reason}>
              <Group justify="space-between"><Text size="xs">{REASON_TEXT[reason as AccessReason] ?? reason}</Text>
                <Text size="xs" fw={600}>{count}</Text></Group>
              <div style={{ height: 6, borderRadius: 3, background: 'var(--mantine-color-red-light)' }}>
                <div style={{ width: `${(count / max) * 100}%`, height: 6, borderRadius: 3, background: 'var(--mantine-color-red-6)' }} />
              </div>
            </div>
          ))}
        </Stack>
      )}
      {credentials.length > 0 && (
        <Group gap={6} mt="md">
          <Text size="xs" c="dimmed">Granted by:</Text>
          {credentials.map(([type, count]) => <Badge key={type} variant="light" size="sm">{label(type)} {count}</Badge>)}
        </Group>
      )}
    </Paper>
  );
}

function AlarmsPanel({ branchId }: { branchId: number | null }) {
  const [openOnly, setOpenOnly] = useState(true);
  const alarms = useApiQuery<Alarm[]>('/api/v1/access/alarms', { branchId: branchId ?? undefined, openOnly, limit: 20 },
    { refetchInterval: 10_000 });
  const ack = useApiMutation<number>({
    request: (id) => ({ path: `/api/v1/access/alarms/${id}/acknowledge` }),
    invalidate: ['/api/v1/access/alarms', '/api/v1/access/stats'],
    successMessage: 'Alarm acknowledged',
  });
  const list = alarms.data ?? [];
  return (
    <Paper withBorder p="md">
      <Group justify="space-between" mb="sm">
        <Title order={5}>Alarms</Title>
        <Switch size="xs" label="Open only" checked={openOnly} onChange={(e) => setOpenOnly(e.currentTarget.checked)} />
      </Group>
      <QueryState isLoading={alarms.isLoading} error={alarms.error} empty={list.length === 0} emptyText="No alarms.">
        <Stack gap={6}>
          {list.map((a) => (
            <Group key={a.id} justify="space-between" wrap="nowrap" p={8} style={{ borderRadius: 6,
              background: a.acknowledgedAt ? 'var(--mantine-color-default-hover)' : 'var(--mantine-color-red-light)' }}>
              <Stack gap={0} style={{ minWidth: 0 }}>
                <Text size="sm" fw={600}>{label(a.type)}</Text>
                <Text size="xs">{a.deviceName ?? `Device #${a.deviceId}`} · {fromNow(a.occurredAt)}</Text>
                {a.details && <Text size="xs" c="dimmed" lineClamp={2}>{a.details}</Text>}
              </Stack>
              {a.acknowledgedAt
                ? <Tooltip label={`Acknowledged ${dateTime(a.acknowledgedAt)}`}><IconCheck size={16} color="teal" /></Tooltip>
                : <Button size="compact-xs" variant="white" loading={ack.isPending} onClick={() => ack.mutate(a.id)}>Ack</Button>}
            </Group>
          ))}
        </Stack>
      </QueryState>
    </Paper>
  );
}

// ------------------------------------------------------------------ devices

function DevicesTable({ branchId, manager, onSecret }: {
  branchId: number | null; manager: boolean; onSecret: (s: DeviceSecret) => void;
}) {
  const devices = useApiQuery<Device[]>('/api/v1/access/devices', { branchId: branchId ?? undefined },
    { refetchInterval: 15_000 });
  const [rotating, setRotating] = useState<Device | null>(null);
  const status = useApiMutation<{ id: number; status: 'ACTIVE' | 'DISABLED' }>({
    request: ({ id, status: s }) => ({ path: `/api/v1/access/devices/${id}/status`, method: 'PATCH', body: { status: s } }),
    invalidate: ['/api/v1/access'],
    successMessage: 'Device updated',
  });
  const list = devices.data ?? [];
  return (
    <Paper withBorder p="md">
      <QueryState isLoading={devices.isLoading} error={devices.error} empty={list.length === 0}
        emptyText="No gate devices registered yet.">
        <ScrollArea>
          <Table verticalSpacing="sm" fz="sm" highlightOnHover>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Device</Table.Th><Table.Th>Branch</Table.Th><Table.Th>Type</Table.Th><Table.Th>Hardware</Table.Th>
                <Table.Th>Status</Table.Th><Table.Th>Heartbeat</Table.Th><Table.Th>Key</Table.Th><Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {list.map((d) => (
                <Table.Tr key={d.id}>
                  <Table.Td>
                    <Text fw={600} size="sm">{d.name}</Text>
                    <Code fz="xs">{d.deviceUid}</Code>
                  </Table.Td>
                  <Table.Td>{d.branchName}</Table.Td>
                  <Table.Td><Text size="sm">{label(d.type)}</Text><Text size="xs" c="dimmed">{label(d.direction)}</Text></Table.Td>
                  <Table.Td>
                    <Text size="xs">{[d.vendor, d.model].filter(Boolean).join(' · ') || '—'}</Text>
                    <Text size="xs" c="dimmed">{d.serialNumber ?? ''}{d.firmwareVersion ? ` · fw ${d.firmwareVersion}` : ''}</Text>
                  </Table.Td>
                  <Table.Td><StatusBadge value={d.status} /></Table.Td>
                  <Table.Td>
                    <Group gap={6} wrap="nowrap">
                      <Badge size="sm" variant="dot" color={d.online ? 'teal' : d.offlineSince ? 'red' : 'gray'}>
                        {d.online ? 'Online' : d.offlineSince ? 'Offline' : d.lastHeartbeatAt ? 'Stale' : 'Never seen'}
                      </Badge>
                    </Group>
                    <Text size="xs" c="dimmed">{d.lastHeartbeatAt ? fromNow(d.lastHeartbeatAt) : 'no heartbeat yet'}</Text>
                  </Table.Td>
                  <Table.Td><Text size="xs">v{d.keyVersion}</Text><Text size="xs" c="dimmed">{dayjs(d.secretIssuedAt).format('DD MMM')}</Text></Table.Td>
                  <Table.Td>
                    {manager && (
                      <Menu position="bottom-end" withinPortal>
                        <Menu.Target><ActionIcon variant="subtle" aria-label="Device actions"><IconDots size={16} /></ActionIcon></Menu.Target>
                        <Menu.Dropdown>
                          <Menu.Item leftSection={<IconKey size={14} />} onClick={() => setRotating(d)}>Rotate secret</Menu.Item>
                          {d.status === 'ACTIVE'
                            ? <Menu.Item color="red" onClick={() => status.mutate({ id: d.id, status: 'DISABLED' })}>Disable</Menu.Item>
                            : <Menu.Item onClick={() => status.mutate({ id: d.id, status: 'ACTIVE' })}>Enable</Menu.Item>}
                        </Menu.Dropdown>
                      </Menu>
                    )}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </ScrollArea>
      </QueryState>
      <RotateModal device={rotating} onClose={() => setRotating(null)} onRotated={onSecret} />
    </Paper>
  );
}

function RotateModal({ device, onClose, onRotated }: {
  device: Device | null; onClose: () => void; onRotated: (s: DeviceSecret) => void;
}) {
  const rotate = useApiMutation<number, DeviceSecret>({
    request: (id) => ({ path: `/api/v1/access/devices/${id}/rotate-secret` }),
    invalidate: ['/api/v1/access/devices'],
    onSuccess: (s) => {
      onClose();
      onRotated(s);
    },
  });
  return (
    <Modal opened={device != null} onClose={onClose} title="Rotate device secret">
      <Text size="sm">
        A new secret is issued for <b>{device?.name}</b>. The current secret stops working immediately, so the device is
        offline until the new secret is installed on it.
      </Text>
      <Group justify="flex-end" mt="md">
        <Button variant="default" onClick={onClose}>Cancel</Button>
        <Button color="orange" loading={rotate.isPending} onClick={() => device && rotate.mutate(device.id)}>Rotate</Button>
      </Group>
    </Modal>
  );
}

function DeviceFormModal({ opened, onClose, defaultBranchId, onCreated }: {
  opened: boolean; onClose: () => void; defaultBranchId: number | null; onCreated: (s: DeviceSecret) => void;
}) {
  const [form, setForm] = useState<Partial<DeviceRequest>>({ type: 'TURNSTILE', direction: 'ENTRY' });
  const branch = form.branchId ?? defaultBranchId;
  const create = useApiMutation<void, DeviceSecret>({
    request: () => ({ path: '/api/v1/access/devices', body: { ...form, branchId: branch } }),
    invalidate: ['/api/v1/access'],
    onSuccess: (s) => {
      setForm({ type: 'TURNSTILE', direction: 'ENTRY' });
      onClose();
      onCreated(s);
    },
  });
  const set = (k: keyof DeviceRequest) => (v: string | null) => setForm((f) => ({ ...f, [k]: v ?? undefined }));
  return (
    <Modal opened={opened} onClose={onClose} title="Register gate device" size="lg">
      <Stack>
        <SimpleGrid cols={{ base: 1, sm: 2 }}>
          <BranchSelect label="Branch" value={branch} onChange={(id) => setForm((f) => ({ ...f, branchId: id ?? undefined }))} required />
          <TextInput label="Name" placeholder="Main entrance turnstile" value={form.name ?? ''} required
            onChange={(e) => set('name')(e.currentTarget.value)} />
          <Select label="Type" data={DEVICE_TYPES.map((t) => ({ value: t, label: label(t) }))} value={form.type}
            onChange={(v) => setForm((f) => ({ ...f, type: (v as DeviceType) ?? 'TURNSTILE' }))} allowDeselect={false} />
          <Select label="Direction" data={DEVICE_DIRECTIONS.map((t) => ({ value: t, label: label(t) }))} value={form.direction}
            onChange={(v) => setForm((f) => ({ ...f, direction: (v as DeviceDirection) ?? 'ENTRY' }))} allowDeselect={false} />
          <TextInput label="Vendor" placeholder="ZKTeco / eSSL / Suprema" value={form.vendor ?? ''} onChange={(e) => set('vendor')(e.currentTarget.value)} />
          <TextInput label="Model" value={form.model ?? ''} onChange={(e) => set('model')(e.currentTarget.value)} />
          <TextInput label="Serial number" value={form.serialNumber ?? ''} onChange={(e) => set('serialNumber')(e.currentTarget.value)} />
          <TextInput label="Firmware" value={form.firmwareVersion ?? ''} onChange={(e) => set('firmwareVersion')(e.currentTarget.value)} />
        </SimpleGrid>
        <Text size="xs" c="dimmed">
          The device authenticates every request with an HMAC secret. It is shown once after registration; store it in the
          device (or vendor bridge) configuration.
        </Text>
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>Cancel</Button>
          <Button disabled={!branch || !form.name} loading={create.isPending} onClick={() => create.mutate()}>Register</Button>
        </Group>
      </Stack>
    </Modal>
  );
}

function SecretModal({ secret, onClose }: { secret: DeviceSecret | null; onClose: () => void }) {
  return (
    <Modal opened={secret != null} onClose={onClose} title="Device secret: shown once" size="lg" closeOnClickOutside={false}>
      {secret && (
        <Stack>
          <Alert color="orange" icon={<IconAlertTriangle size={18} />}>
            Copy this secret into the device now. GymOps will not show it again; if it is lost, rotate the secret.
          </Alert>
          <div>
            <Text size="xs" c="dimmed">Device ID (X-Device-Id)</Text>
            <Group gap="xs"><Code fz="sm">{secret.device.deviceUid}</Code><Copy value={secret.device.deviceUid} /></Group>
          </div>
          <div>
            <Text size="xs" c="dimmed">Secret (HMAC-SHA256 key)</Text>
            <Group gap="xs" wrap="nowrap"><Code fz="sm" style={{ wordBreak: 'break-all' }}>{secret.deviceSecret}</Code>
              <Copy value={secret.deviceSecret} /></Group>
          </div>
          <Text size="xs" c="dimmed">{secret.signatureScheme}</Text>
          <Group justify="space-between">
            <Anchor component={Link} to={`/access/gate?device=${secret.device.id}`} size="sm"
              onClick={() => {
                try {
                  sessionStorage.setItem(`gymops.gate.secret.${secret.device.deviceUid}`, secret.deviceSecret);
                } catch { /* storage unavailable */ }
              }}>
              Use it in the gate simulator (this tab only)
            </Anchor>
            <Button onClick={onClose}>I have stored the secret</Button>
          </Group>
        </Stack>
      )}
    </Modal>
  );
}

function Copy({ value }: { value: string }) {
  return (
    <CopyButton value={value} timeout={1500}>
      {({ copied, copy }) => (
        <Tooltip label={copied ? 'Copied' : 'Copy'}>
          <ActionIcon variant="subtle" color={copied ? 'teal' : 'gray'} onClick={copy} aria-label="Copy">
            {copied ? <IconCheck size={16} /> : <IconCopy size={16} />}
          </ActionIcon>
        </Tooltip>
      )}
    </CopyButton>
  );
}

// ------------------------------------------------------------------ event log

function EventLog({ branchId }: { branchId: number | null }) {
  const [page, setPage] = useState(0);
  const [deviceId, setDeviceId] = useState<string | null>(null);
  const [decision, setDecision] = useState<Decision | null>(null);
  const [reason, setReason] = useState<string | null>(null);
  const [memberId, setMemberId] = useState<number | null>(null);
  const [range, setRange] = useState<[Date | null, Date | null]>([dayjs().subtract(6, 'day').toDate(), new Date()]);
  const devices = useApiQuery<Device[]>('/api/v1/access/devices', { branchId: branchId ?? undefined });
  const q = useApiQuery<Page<AccessEvent>>('/api/v1/access/events', {
    branchId: branchId ?? undefined, deviceId: deviceId ?? undefined, decision: decision ?? undefined,
    reason: reason ?? undefined, memberId: memberId ?? undefined,
    from: range[0] ? dayjs(range[0]).format('YYYY-MM-DD') : undefined,
    to: range[1] ? dayjs(range[1]).format('YYYY-MM-DD') : undefined, page, size: 25,
  }, { placeholderData: keepPreviousData });
  const reset = <T,>(setter: (v: T) => void) => (v: T) => {
    setter(v);
    setPage(0);
  };
  return (
    <Paper withBorder p="md">
      <Group mb="md" gap="sm" align="flex-end" wrap="wrap">
        <Select label="Device" clearable placeholder="All devices" value={deviceId} onChange={reset(setDeviceId)} w={200}
          data={(devices.data ?? []).map((d) => ({ value: String(d.id), label: d.name }))} />
        <Select label="Decision" clearable placeholder="Any" value={decision} w={130}
          onChange={(v) => reset(setDecision)(v as Decision | null)} data={['GRANTED', 'DENIED']} />
        <Select label="Reason" clearable searchable placeholder="Any" value={reason} onChange={reset(setReason)} w={220}
          data={Object.entries(REASON_TEXT).map(([value, text]) => ({ value, label: text }))} />
        <MemberSearch label="Member" value={memberId} onChange={(id) => reset(setMemberId)(id)} branchId={branchId} w={260} />
        <DatePickerInput type="range" label="Dates" value={range} onChange={(v) => reset(setRange)(v as [Date | null, Date | null])}
          w={240} valueFormat="DD MMM" />
      </Group>
      <QueryState isLoading={q.isLoading} error={q.error} empty={(q.data?.content.length ?? 0) === 0}
        emptyText="No events match these filters.">
        <AccessEventTable events={q.data?.content ?? []} />
        <Pager page={q.data} onChange={setPage} />
      </QueryState>
    </Paper>
  );
}
