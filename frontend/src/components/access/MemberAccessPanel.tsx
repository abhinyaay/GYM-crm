import {
  ActionIcon, Alert, Badge, Button, Checkbox, Group, Menu, Modal, Paper, ScrollArea, Select, Stack, Table, Text, TextInput,
  Title, Tooltip,
} from '@mantine/core';
import {
  IconDots, IconFingerprint, IconId, IconQrcode, IconScan, IconShieldCheck, IconShieldOff, IconUserScan,
} from '@tabler/icons-react';
import { useState } from 'react';
import type {
  AccessEvent, CommandView, ConsentNotice, ConsentStatus, ConsentWithdrawal, Credential, CredentialType, Device,
} from '@/api/access';
import { REASON_TEXT } from '@/api/access';
import type { Page } from '@/api/types';
import { Pager } from '@/components/Pager';
import { QueryState } from '@/components/QueryState';
import { StatusBadge } from '@/components/StatusBadge';
import { dateTime, fromNow, label } from '@/lib/format';
import { notifySuccess } from '@/lib/notify';
import { useFeatures } from '@/lib/features';
import { useApiMutation, useApiQuery } from '@/lib/useApi';

const ICONS: Record<CredentialType, typeof IconId> = {
  FINGERPRINT: IconFingerprint, FACE: IconUserScan, RFID_CARD: IconId, QR: IconQrcode,
};

/**
 * Staff view of one member's gate access (member detail → "Gate access"): DPDP biometric consent, credentials
 * (RFID card, fingerprint / face via a device enrolment command, QR pass), and the member's gate events.
 */
export function MemberAccessPanel({ memberId }: { memberId: number }) {
  const { biometrics } = useFeatures();
  return (
    <Stack gap="md">
      {biometrics && <ConsentCard memberId={memberId} />}
      <CredentialsCard memberId={memberId} />
      <EventsCard memberId={memberId} />
    </Stack>
  );
}

// ------------------------------------------------------------------ consent

function ConsentCard({ memberId }: { memberId: number }) {
  const status = useApiQuery<ConsentStatus>(`/api/v1/access/members/${memberId}/consent`);
  const [granting, setGranting] = useState(false);
  const [withdrawing, setWithdrawing] = useState(false);
  const s = status.data;
  return (
    <Paper withBorder p="md">
      <Group justify="space-between" mb="xs" wrap="wrap">
        <Group gap="xs">
          <Title order={5}>Biometric consent</Title>
          {s && (s.active
            ? <Badge color="teal" variant="light" leftSection={<IconShieldCheck size={12} />}>Active</Badge>
            : <Badge color="gray" variant="light" leftSection={<IconShieldOff size={12} />}>Not given</Badge>)}
        </Group>
        {s && (s.active
          ? <Button size="xs" variant="light" color="red" onClick={() => setWithdrawing(true)}>Withdraw consent</Button>
          : <Button size="xs" onClick={() => setGranting(true)}>Record consent</Button>)}
      </Group>
      <Text size="xs" c="dimmed" mb="sm">
        Required (DPDP Act 2023) before enrolling a fingerprint or face. Matching happens on the gate device; GymOps stores
        only the device&apos;s template reference, never the fingerprint or face itself. Cards and the QR pass need no consent.
      </Text>
      <QueryState isLoading={status.isLoading} error={status.error}>
        {s?.current && (
          <Text size="sm">
            Notice {s.current.noticeVersion} accepted {dateTime(s.current.grantedAt)} ({label(s.current.captureMethod)})
          </Text>
        )}
        {s && s.history.length > 0 && (
          <ScrollArea mt="sm">
            <Table fz="xs" striped>
              <Table.Thead>
                <Table.Tr><Table.Th>Granted</Table.Th><Table.Th>Notice</Table.Th><Table.Th>How</Table.Th>
                  <Table.Th>Withdrawn</Table.Th><Table.Th>Reason</Table.Th></Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {s.history.map((c) => (
                  <Table.Tr key={c.id}>
                    <Table.Td>{dateTime(c.grantedAt)}</Table.Td>
                    <Table.Td>{c.noticeVersion}</Table.Td>
                    <Table.Td>{label(c.captureMethod)}</Table.Td>
                    <Table.Td>{c.withdrawnAt ? dateTime(c.withdrawnAt) : '—'}</Table.Td>
                    <Table.Td>{c.withdrawalReason ?? '—'}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </ScrollArea>
        )}
      </QueryState>
      <GrantConsentModal opened={granting} onClose={() => setGranting(false)}
        path={`/api/v1/access/members/${memberId}/consent`} staff />
      <WithdrawConsentModal opened={withdrawing} onClose={() => setWithdrawing(false)}
        path={`/api/v1/access/members/${memberId}/consent/withdraw`} />
    </Paper>
  );
}

/** Shows the exact notice and records explicit consent (also used by the member's own pass page). */
export function GrantConsentModal({ opened, onClose, path, staff = false }: {
  opened: boolean; onClose: () => void; path: string; staff?: boolean;
}) {
  const notice = useApiQuery<ConsentNotice>(opened ? '/api/v1/access/consent/notice' : null);
  const [accepted, setAccepted] = useState(false);
  const grant = useApiMutation<void>({
    request: () => ({ path, body: { noticeVersion: notice.data?.version, accepted: true,
      captureMethod: staff ? 'STAFF_ASSISTED' : 'SELF_SERVICE' } }),
    invalidate: ['/api/v1/access'],
    successMessage: 'Consent recorded',
    onSuccess: () => {
      setAccepted(false);
      onClose();
    },
  });
  return (
    <Modal opened={opened} onClose={onClose} title="Biometric consent notice" size="lg">
      <QueryState isLoading={notice.isLoading} error={notice.error}>
        <ScrollArea.Autosize mah={320}>
          <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>{notice.data?.text}</Text>
        </ScrollArea.Autosize>
        <Text size="xs" c="dimmed" mt="xs">Version {notice.data?.version} · purpose {notice.data?.purpose}</Text>
        <Checkbox mt="md" checked={accepted} onChange={(e) => setAccepted(e.currentTarget.checked)}
          label={staff ? 'The member has read this notice and explicitly agrees' : 'I have read this notice and I agree'} />
        <Group justify="flex-end" mt="md">
          <Button variant="default" onClick={onClose}>Cancel</Button>
          <Button disabled={!accepted || !notice.data} loading={grant.isPending} onClick={() => grant.mutate()}>
            Record consent
          </Button>
        </Group>
      </QueryState>
    </Modal>
  );
}

export function WithdrawConsentModal({ opened, onClose, path }: { opened: boolean; onClose: () => void; path: string }) {
  const [reason, setReason] = useState('');
  const withdraw = useApiMutation<void, ConsentWithdrawal>({
    request: () => ({ path, body: { reason: reason || undefined } }),
    invalidate: ['/api/v1/access'],
    onSuccess: (r) => {
      notifySuccess(`Consent withdrawn: ${r.credentialsRevoked} biometric credential(s) revoked, `
        + `${r.deleteCommandsQueued} template deletion command(s) queued to the gates`);
      setReason('');
      onClose();
    },
  });
  return (
    <Modal opened={opened} onClose={onClose} title="Withdraw biometric consent">
      <Text size="sm" mb="sm">
        Fingerprint / face entry stops immediately and every gate device is told to erase the template. Cards and the QR
        pass keep working.
      </Text>
      <TextInput label="Reason (optional)" value={reason} onChange={(e) => setReason(e.currentTarget.value)} maxLength={200} />
      <Group justify="flex-end" mt="md">
        <Button variant="default" onClick={onClose}>Cancel</Button>
        <Button color="red" loading={withdraw.isPending} onClick={() => withdraw.mutate()}>Withdraw consent</Button>
      </Group>
    </Modal>
  );
}

// ------------------------------------------------------------------ credentials

function CredentialsCard({ memberId }: { memberId: number }) {
  const creds = useApiQuery<Credential[]>(`/api/v1/access/members/${memberId}/credentials`, undefined, {
    refetchInterval: (q) => ((q.state.data as Credential[] | undefined)?.some((c) => c.status === 'PENDING') ? 3000 : false),
  });
  const [cardOpen, setCardOpen] = useState(false);
  const [bioOpen, setBioOpen] = useState(false);
  const action = useApiMutation<{ id: number; op: 'suspend' | 'reactivate' | 'revoke' }>({
    request: ({ id, op }) => ({ path: `/api/v1/access/credentials/${id}/${op}`, body: op === 'revoke' ? { reason: 'Revoked at the desk' } : undefined }),
    invalidate: ['/api/v1/access'],
    successMessage: 'Credential updated',
  });
  const issueQr = useApiMutation<void>({
    request: () => ({ path: `/api/v1/access/members/${memberId}/credentials/qr` }),
    invalidate: ['/api/v1/access'],
    successMessage: 'Entry pass re-issued',
  });
  const { biometrics } = useFeatures();
  const list = creds.data ?? [];
  const qrRevokedOnly = list.some((c) => c.type === 'QR') && !list.some((c) => c.type === 'QR' && c.status !== 'REVOKED');
  return (
    <Paper withBorder p="md">
      <Group justify="space-between" mb="sm" wrap="wrap">
        <Title order={5}>Credentials</Title>
        <Group gap="xs">
          <Button size="xs" variant="light" leftSection={<IconId size={14} />} onClick={() => setCardOpen(true)}>Register card</Button>
          {biometrics && (
            <Button size="xs" variant="light" leftSection={<IconFingerprint size={14} />} onClick={() => setBioOpen(true)}>
              Enrol fingerprint / face
            </Button>
          )}
          {qrRevokedOnly && (
            <Button size="xs" variant="light" leftSection={<IconQrcode size={14} />} loading={issueQr.isPending}
              onClick={() => issueQr.mutate()}>Re-issue QR pass</Button>
          )}
        </Group>
      </Group>
      <QueryState isLoading={creds.isLoading} error={creds.error} empty={list.length === 0}
        emptyText="No credentials yet. The member's QR pass appears here once they open it in the app.">
        <ScrollArea>
          <Table verticalSpacing="xs" fz="sm">
            <Table.Thead>
              <Table.Tr><Table.Th>Credential</Table.Th><Table.Th>Status</Table.Th><Table.Th>Enrolled</Table.Th>
                <Table.Th>Device / commands</Table.Th><Table.Th /></Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {list.map((c) => {
                const Icon = ICONS[c.type];
                return (
                  <Table.Tr key={c.id}>
                    <Table.Td>
                      <Group gap={8} wrap="nowrap">
                        <Icon size={18} />
                        <Stack gap={0}>
                          <Text size="sm" fw={500}>{label(c.type)}{c.label ? ` · ${c.label}` : ''}</Text>
                          <Text size="xs" c="dimmed">{c.displayHint ?? '—'}</Text>
                        </Stack>
                      </Group>
                    </Table.Td>
                    <Table.Td>
                      <StatusBadge value={c.status} />
                      {c.suspensionSource === 'MEMBER_STATUS' && <Text size="xs" c="dimmed">with member account</Text>}
                      {c.revokeReason && <Text size="xs" c="dimmed">{c.revokeReason}</Text>}
                    </Table.Td>
                    <Table.Td><Text size="xs">{dateTime(c.activatedAt ?? c.createdAt)}</Text></Table.Td>
                    <Table.Td>
                      {c.enrolledDeviceName && <Text size="xs">{c.enrolledDeviceName}</Text>}
                      {c.status === 'PENDING' && (
                        <Text size="xs" c="orange">Waiting for the device: complete it on the gate (Gate simulator → Device commands)</Text>
                      )}
                      <CommandBadges commands={c.commands} />
                    </Table.Td>
                    <Table.Td>
                      {c.status !== 'REVOKED' && (
                        <Menu position="bottom-end" withinPortal>
                          <Menu.Target><ActionIcon variant="subtle" aria-label="Credential actions"><IconDots size={16} /></ActionIcon></Menu.Target>
                          <Menu.Dropdown>
                            {c.status === 'ACTIVE' && (
                              <Menu.Item onClick={() => action.mutate({ id: c.id, op: 'suspend' })}>Suspend</Menu.Item>
                            )}
                            {c.status === 'SUSPENDED' && (
                              <Menu.Item onClick={() => action.mutate({ id: c.id, op: 'reactivate' })}>Reactivate</Menu.Item>
                            )}
                            <Menu.Item color="red" onClick={() => action.mutate({ id: c.id, op: 'revoke' })}>Revoke</Menu.Item>
                          </Menu.Dropdown>
                        </Menu>
                      )}
                    </Table.Td>
                  </Table.Tr>
                );
              })}
            </Table.Tbody>
          </Table>
        </ScrollArea>
      </QueryState>
      <CardModal opened={cardOpen} onClose={() => setCardOpen(false)} memberId={memberId} />
      <BiometricModal opened={bioOpen} onClose={() => setBioOpen(false)} memberId={memberId} />
    </Paper>
  );
}

function CommandBadges({ commands }: { commands: CommandView[] }) {
  if (commands.length === 0) return null;
  const deletes = commands.filter((c) => c.type === 'DELETE_TEMPLATE');
  const done = deletes.filter((c) => c.status === 'SUCCEEDED').length;
  const enrol = commands.find((c) => c.type === 'ENROLL_TEMPLATE');
  return (
    <Group gap={4} mt={2}>
      {enrol && <Tooltip label={`Enrolment command #${enrol.id}`}><Badge size="xs" variant="outline">enrol: {label(enrol.status)}</Badge></Tooltip>}
      {deletes.length > 0 && (
        <Tooltip label="Template deletion commands acknowledged by devices">
          <Badge size="xs" variant="outline" color={done === deletes.length ? 'teal' : 'orange'}>
            erased on {done}/{deletes.length} devices
          </Badge>
        </Tooltip>
      )}
    </Group>
  );
}

function CardModal({ opened, onClose, memberId }: { opened: boolean; onClose: () => void; memberId: number }) {
  const [uid, setUid] = useState('');
  const [cardLabel, setCardLabel] = useState('');
  const enrol = useApiMutation<void>({
    request: () => ({ path: `/api/v1/access/members/${memberId}/credentials/card`, body: { cardUid: uid, label: cardLabel || undefined } }),
    invalidate: ['/api/v1/access'],
    successMessage: 'Card registered',
    onSuccess: () => {
      setUid('');
      setCardLabel('');
      onClose();
    },
  });
  return (
    <Modal opened={opened} onClose={onClose} title="Register RFID card">
      <Stack>
        <TextInput label="Card UID (hex)" description="Tap the card on the desk reader, or type the UID printed on it. Only a SHA-256 hash is stored."
          placeholder="04:A1:B2:C3" value={uid} onChange={(e) => setUid(e.currentTarget.value)} required />
        <TextInput label="Label (optional)" placeholder="Blue key fob" value={cardLabel} onChange={(e) => setCardLabel(e.currentTarget.value)} />
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>Cancel</Button>
          <Button disabled={uid.trim().length < 4} loading={enrol.isPending} onClick={() => enrol.mutate()}>Register</Button>
        </Group>
      </Stack>
    </Modal>
  );
}

function BiometricModal({ opened, onClose, memberId }: { opened: boolean; onClose: () => void; memberId: number }) {
  const devices = useApiQuery<Device[]>(opened ? '/api/v1/access/devices' : null);
  const consent = useApiQuery<ConsentStatus>(opened ? `/api/v1/access/members/${memberId}/consent` : null);
  const [type, setType] = useState<CredentialType>('FINGERPRINT');
  const [deviceId, setDeviceId] = useState<string | null>(null);
  const [bioLabel, setBioLabel] = useState('');
  const enrol = useApiMutation<void>({
    request: () => ({ path: `/api/v1/access/members/${memberId}/credentials/biometric`,
      body: { type, deviceId: Number(deviceId), label: bioLabel || undefined } }),
    invalidate: ['/api/v1/access'],
    successMessage: 'Enrolment sent to the device: ask the member to present their finger / face at the gate',
    onSuccess: () => {
      setBioLabel('');
      onClose();
    },
  });
  const active = (devices.data ?? []).filter((d) => d.status === 'ACTIVE');
  return (
    <Modal opened={opened} onClose={onClose} title="Enrol fingerprint or face">
      <Stack>
        {consent.data && !consent.data.active && (
          <Alert color="orange" title="Consent required">Record the member&apos;s biometric consent first.</Alert>
        )}
        <Select label="Biometric" data={[{ value: 'FINGERPRINT', label: 'Fingerprint' }, { value: 'FACE', label: 'Face' }]}
          value={type} onChange={(v) => setType((v as CredentialType) ?? 'FINGERPRINT')} allowDeselect={false} />
        <Select label="Capture on device" placeholder="Choose the gate / kiosk the member stands at" value={deviceId}
          onChange={setDeviceId} data={active.map((d) => ({ value: String(d.id), label: `${d.name} · ${d.branchName}` }))} />
        <TextInput label="Label (optional)" placeholder="Right index finger" value={bioLabel} onChange={(e) => setBioLabel(e.currentTarget.value)} />
        <Text size="xs" c="dimmed">
          The device captures and stores the template locally and reports back only its template reference.
        </Text>
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>Cancel</Button>
          <Button leftSection={<IconScan size={16} />} disabled={!deviceId || !consent.data?.active}
            loading={enrol.isPending} onClick={() => enrol.mutate()}>Send to device</Button>
        </Group>
      </Stack>
    </Modal>
  );
}

// ------------------------------------------------------------------ events

function EventsCard({ memberId }: { memberId: number }) {
  const [page, setPage] = useState(0);
  const q = useApiQuery<Page<AccessEvent>>(`/api/v1/access/members/${memberId}/events`, { page, size: 10 });
  return (
    <Paper withBorder p="md">
      <Title order={5} mb="sm">Gate events</Title>
      <QueryState isLoading={q.isLoading} error={q.error} empty={(q.data?.content.length ?? 0) === 0}
        emptyText="No gate events yet.">
        <AccessEventTable events={q.data?.content ?? []} showMember={false} />
        <Pager page={q.data} onChange={setPage} />
      </QueryState>
    </Paper>
  );
}

/** Shared event table (member panel, access control page). */
export function AccessEventTable({ events, showMember = true }: { events: AccessEvent[]; showMember?: boolean }) {
  return (
    <ScrollArea>
      <Table verticalSpacing={6} fz="sm" striped highlightOnHover>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>When</Table.Th><Table.Th>Gate</Table.Th>{showMember && <Table.Th>Member</Table.Th>}
            <Table.Th>Credential</Table.Th><Table.Th>Dir.</Table.Th><Table.Th>Decision</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {events.map((e) => (
            <Table.Tr key={e.id}>
              <Table.Td><Tooltip label={dateTime(e.occurredAt)}><Text size="xs">{fromNow(e.occurredAt)}</Text></Tooltip></Table.Td>
              <Table.Td><Text size="xs">{e.deviceName ?? `#${e.deviceId}`}</Text></Table.Td>
              {showMember && (
                <Table.Td>
                  <Text size="xs">{e.memberName ?? <Text span c="dimmed" size="xs">unknown</Text>}</Text>
                  {e.memberCode && <Text size="xs" c="dimmed">{e.memberCode}</Text>}
                </Table.Td>
              )}
              <Table.Td><Text size="xs">{label(e.credentialType)}</Text><Text size="xs" c="dimmed">{e.credentialHint}</Text></Table.Td>
              <Table.Td><Text size="xs">{e.direction === 'ENTRY' ? 'In' : 'Out'}</Text></Table.Td>
              <Table.Td>
                <Group gap={4} wrap="nowrap">
                  <Badge size="sm" color={e.decision === 'GRANTED' ? 'teal' : 'red'} variant="light">{e.decision}</Badge>
                  {e.offline && <Badge size="xs" color="gray" variant="outline">offline</Badge>}
                </Group>
                {e.reasonCode && e.reasonCode !== 'OK' && (
                  <Text size="xs" c={e.decision === 'DENIED' ? 'red' : 'orange'}>{REASON_TEXT[e.reasonCode] ?? e.reasonCode}</Text>
                )}
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </ScrollArea>
  );
}
