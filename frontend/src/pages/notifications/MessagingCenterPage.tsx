import {
  Alert, Anchor, Badge, Button, Code, Divider, Drawer, Group, Modal, Paper, SegmentedControl, Select, SimpleGrid, Stack,
  Table, Text, Textarea, TextInput, ThemeIcon, Title, Tooltip,
} from '@mantine/core';
import { DatePickerInput, type DatesRangeValue } from '@mantine/dates';
import { useDebouncedValue } from '@mantine/hooks';
import {
  IconAlertTriangle, IconCircleCheck, IconMail, IconMessage, IconRefresh, IconSearch, IconSend, IconX,
} from '@tabler/icons-react';
import { keepPreviousData } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import {
  CHANNELS, DELIVERY_STATUS_COLOR, DELIVERY_STATUSES, NOTIFICATION_TYPES, type DeliveryStatus, type NotificationChannel,
  type NotificationLogEntry, type ProviderStatus, type ProvidersResponse, type TestMessageRequest,
} from '@/api/notifications';
import type { Page } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { hasAnyRole, ORG_WIDE } from '@/auth/roles';
import { MemberSearch } from '@/components/MemberSearch';
import { PageHeader } from '@/components/PageHeader';
import { Pager } from '@/components/Pager';
import { QueryState } from '@/components/QueryState';
import { dateTime, fromNow, label } from '@/lib/format';
import { errorMessage } from '@/lib/notify';
import { useApiMutation, useApiQuery } from '@/lib/useApi';

const LOG = '/api/v1/messaging/notifications';

/** Managers: provider status, delivery log with filters, resend of failed messages and test sends. */
export default function MessagingCenterPage() {
  const [testing, setTesting] = useState(false);
  const providers = useApiQuery<ProvidersResponse>('/api/v1/messaging/providers');
  return (
    <>
      <PageHeader
        title="Messaging"
        subtitle="E-mail and SMS delivery: provider status, delivery log, failed messages"
        actions={<Button leftSection={<IconSend size={16} />} onClick={() => setTesting(true)}>Send test message</Button>}
      />
      <QueryState isLoading={providers.isLoading} error={providers.error}>
        {providers.data && <Providers data={providers.data} />}
      </QueryState>
      <DeliveryLog />
      <TestMessageModal opened={testing} onClose={() => setTesting(false)} />
    </>
  );
}

// ------------------------------------------------------------------ providers

function Providers({ data }: { data: ProvidersResponse }) {
  return (
    <SimpleGrid cols={{ base: 1, md: 2 }} mb="lg">
      <ProviderCard status={data.email} icon={<IconMail size={20} />} title="E-mail" />
      <ProviderCard status={data.sms} icon={<IconMessage size={20} />} title="SMS"
        footer={<>Promotional SMS is held back during quiet hours ({data.quietHours}).</>} />
    </SimpleGrid>
  );
}

function ProviderCard({ status, icon, title, footer }: {
  status: ProviderStatus; icon: ReactNode; title: string; footer?: ReactNode;
}) {
  const host = typeof status.details.host === 'string' ? status.details.host : null;
  const localInbox = status.provider === 'smtp' && host !== null && ['mailpit', 'localhost', '127.0.0.1'].includes(host);
  return (
    <Paper withBorder p="md">
      <Group justify="space-between" mb="xs" wrap="nowrap">
        <Group gap="sm" wrap="nowrap">
          <ThemeIcon variant="light" size="lg">{icon}</ThemeIcon>
          <div>
            <Text fw={600}>{title}</Text>
            <Text size="xs" c="dimmed">Provider: <b>{status.provider}</b></Text>
          </div>
        </Group>
        <Group gap={6}>
          {status.live
            ? <Badge color="teal" variant="light">Live</Badge>
            : <Tooltip label="Messages are written to the server log only"><Badge color="gray" variant="light">Log only</Badge></Tooltip>}
          {status.configured
            ? <Badge color="teal" variant="dot">Configured</Badge>
            : <Badge color="red" variant="filled" leftSection={<IconAlertTriangle size={12} />}>Not configured</Badge>}
        </Group>
      </Group>
      <Table fz="xs" verticalSpacing={2} withRowBorders={false}>
        <Table.Tbody>
          {Object.entries(status.details).map(([k, v]) => (
            <Table.Tr key={k}>
              <Table.Td c="dimmed" w={150}>{label(k.replace(/([a-z])([A-Z])/g, '$1_$2').toUpperCase())}</Table.Td>
              <Table.Td>{v === null || v === '' ? '—' : String(v)}</Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
      {!status.live && (
        <Text size="xs" c="dimmed" mt="xs">
          {status.channel === 'EMAIL'
            ? 'Set EMAIL_PROVIDER=smtp and the SMTP_* variables (SES, SendGrid, Gmail or Mailpit) to send real e-mail.'
            : 'Set SMS_PROVIDER=twilio or msg91 and its credentials to send real SMS.'}
        </Text>
      )}
      {localInbox && (
        <Text size="xs" c="dimmed" mt="xs">
          Local inbox: <Anchor href="http://localhost:8025" target="_blank" rel="noreferrer" size="xs">Mailpit at localhost:8025</Anchor>
        </Text>
      )}
      {footer && <Text size="xs" c="dimmed" mt="xs">{footer}</Text>}
    </Paper>
  );
}

// ------------------------------------------------------------------ log

function DeliveryLog() {
  const [channel, setChannel] = useState<string | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [type, setType] = useState<string | null>(null);
  const [memberId, setMemberId] = useState<number | null>(null);
  const [range, setRange] = useState<DatesRangeValue>([null, null]);
  const [q, setQ] = useState('');
  const [debouncedQ] = useDebouncedValue(q, 300);
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<number | null>(null);
  const reset = <T,>(set: (v: T) => void) => (v: T) => { set(v); setPage(0); };

  const log = useApiQuery<Page<NotificationLogEntry>>(LOG, {
    channel: channel ?? undefined, status: status ?? undefined, type: type ?? undefined, memberId: memberId ?? undefined,
    from: range[0] ? dayjs(range[0]).format('YYYY-MM-DD') : undefined,
    to: range[1] ? dayjs(range[1]).format('YYYY-MM-DD') : undefined,
    q: debouncedQ || undefined, page, size: 25,
  }, { placeholderData: keepPreviousData });
  const failed = useApiQuery<Page<NotificationLogEntry>>(LOG, { status: 'FAILED', size: 1 });
  const failedCount = failed.data?.totalElements ?? 0;
  const filtersActive = channel || status || type || memberId || range[0] || q;
  const rows = log.data?.content ?? [];

  return (
    <>
      <Group justify="space-between" mb="sm" mt="md">
        <Group gap="xs">
          <Title order={4}>Delivery log</Title>
          {failedCount > 0 && (
            <Badge color="red" variant="light" style={{ cursor: 'pointer' }} onClick={() => { setStatus('FAILED'); setPage(0); }}>
              {failedCount} failed
            </Badge>
          )}
        </Group>
        <Button variant="subtle" size="xs" leftSection={<IconRefresh size={14} />} onClick={() => log.refetch()}>Refresh</Button>
      </Group>
      <Paper withBorder p="sm" mb="sm">
        <Group gap="sm" align="flex-end" wrap="wrap">
          <TextInput label="Search" placeholder="Address, phone or subject" leftSection={<IconSearch size={14} />}
            value={q} onChange={(e) => reset(setQ)(e.currentTarget.value)} w={{ base: '100%', sm: 220 }} />
          <Select label="Channel" placeholder="Any" clearable data={CHANNELS.map((c) => ({ value: c, label: channelLabel(c) }))}
            value={channel} onChange={reset(setChannel)} w={{ base: '47%', sm: 120 }} />
          <Select label="Status" placeholder="Any" clearable data={DELIVERY_STATUSES.map((s) => ({ value: s, label: label(s) }))}
            value={status} onChange={reset(setStatus)} w={{ base: '47%', sm: 120 }} />
          <Select label="Type" placeholder="Any" clearable searchable
            data={NOTIFICATION_TYPES.map((t) => ({ value: t, label: label(t) }))}
            value={type} onChange={reset(setType)} w={{ base: '100%', sm: 220 }} />
          <MemberSearch label="Member" value={memberId} onChange={(id) => reset(setMemberId)(id)} w={{ base: '100%', sm: 240 }} />
          <DatePickerInput type="range" label="Created" placeholder="Any time" clearable value={range}
            onChange={reset(setRange)} maxDate={new Date()} w={{ base: '100%', sm: 230 }} />
          {filtersActive && (
            <Button variant="subtle" color="gray" size="xs" mb={4} leftSection={<IconX size={14} />}
              onClick={() => {
                setChannel(null); setStatus(null); setType(null); setMemberId(null); setRange([null, null]); setQ(''); setPage(0);
              }}>
              Clear
            </Button>
          )}
        </Group>
      </Paper>
      <QueryState isLoading={log.isLoading} error={log.error} empty={rows.length === 0}
        emptyText={filtersActive ? 'No notifications match these filters.' : 'No notifications yet.'}>
        <Paper withBorder>
          <Table.ScrollContainer minWidth={860}>
            <Table highlightOnHover verticalSpacing="xs" fz="sm">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Created</Table.Th>
                  <Table.Th>Channel</Table.Th>
                  <Table.Th>Type</Table.Th>
                  <Table.Th>Recipient</Table.Th>
                  <Table.Th>Subject</Table.Th>
                  <Table.Th>Status</Table.Th>
                  <Table.Th ta="right">Tries</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {rows.map((n) => (
                  <Table.Tr key={n.id} onClick={() => setSelected(n.id)} style={{ cursor: 'pointer' }}>
                    <Table.Td><Tooltip label={dateTime(n.createdAt)}><Text size="sm">{fromNow(n.createdAt)}</Text></Tooltip></Table.Td>
                    <Table.Td>{channelLabel(n.channel)}</Table.Td>
                    <Table.Td><Text size="sm">{label(n.type)}</Text></Table.Td>
                    <Table.Td>
                      <Text size="sm" lineClamp={1}>{n.memberName ?? n.destination ?? (n.recipientUserId ? `User #${n.recipientUserId}` : '—')}</Text>
                      {n.memberName && n.destination && <Text size="xs" c="dimmed" lineClamp={1}>{n.destination}</Text>}
                    </Table.Td>
                    <Table.Td maw={280}><Text size="sm" lineClamp={1}>{n.title}</Text></Table.Td>
                    <Table.Td>
                      <DeliveryBadge status={n.status} />
                      {n.lastError && n.status !== 'SENT' && <Text size="xs" c="dimmed" lineClamp={1} maw={220}>{n.lastError}</Text>}
                    </Table.Td>
                    <Table.Td ta="right">{n.attempts}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
        </Paper>
        <Pager page={log.data} onChange={setPage} />
      </QueryState>
      <NotificationDrawer id={selected} onClose={() => setSelected(null)} />
    </>
  );
}

function channelLabel(c: NotificationChannel) {
  return c === 'IN_APP' ? 'In-app' : c === 'EMAIL' ? 'E-mail' : c === 'SMS' ? 'SMS' : label(c);
}

function DeliveryBadge({ status }: { status: DeliveryStatus }) {
  return <Badge variant="light" radius="sm" color={DELIVERY_STATUS_COLOR[status]}>{label(status)}</Badge>;
}

function NotificationDrawer({ id, onClose }: { id: number | null; onClose: () => void }) {
  const q = useApiQuery<NotificationLogEntry>(id == null ? null : `${LOG}/${id}`);
  const resend = useApiMutation<number, NotificationLogEntry>({
    request: (nid) => ({ path: `${LOG}/${nid}/resend` }),
    invalidate: [LOG],
    successMessage: (r) => (r.status === 'SENT' ? 'Delivered' : `Re-queued (${label(r.status)})`),
  });
  const n = q.data;
  return (
    <Drawer opened={id != null} onClose={onClose} position="right" size="lg" title={n ? `Notification #${n.id}` : 'Notification'}>
      <QueryState isLoading={q.isLoading} error={q.error}>
        {n && (
          <Stack gap="sm">
            <Group gap="xs">
              <DeliveryBadge status={n.status} />
              <Badge variant="outline" radius="sm">{channelLabel(n.channel)}</Badge>
              <Badge variant="outline" radius="sm" color="gray">{label(n.category)}</Badge>
            </Group>
            <Detail k="Type" v={label(n.type)} />
            <Detail k="Member" v={n.memberId
              ? <Anchor component={Link} to={`/members/${n.memberId}`} size="sm">{n.memberName ?? `#${n.memberId}`}</Anchor>
              : '—'} />
            <Detail k="To" v={n.destination ?? (n.recipientUserId ? `In-app inbox of user #${n.recipientUserId}` : '—')} />
            <Detail k="Created" v={dateTime(n.createdAt)} />
            {n.status === 'PENDING' && <Detail k="Next attempt" v={dateTime(n.nextAttemptAt)} />}
            <Detail k="Sent" v={dateTime(n.sentAt)} />
            <Detail k="Attempts" v={String(n.attempts)} />
            <Detail k="Provider" v={n.provider ?? '—'} />
            {n.providerMessageId && <Detail k="Provider id" v={<Code>{n.providerMessageId}</Code>} />}
            {n.segments != null && <Detail k="SMS segments" v={String(n.segments)} />}
            {n.lastError && (
              <Alert color={n.status === 'SKIPPED' ? 'gray' : 'red'} title={n.status === 'SKIPPED' ? 'Skipped' : 'Last error'} p="sm">
                {n.lastError}
              </Alert>
            )}
            <Divider label="Message" labelPosition="left" />
            <Text fw={600}>{n.title}</Text>
            <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>{n.body}</Text>
            {n.status === 'FAILED' && (
              <Group mt="sm">
                <Button leftSection={<IconRefresh size={16} />} loading={resend.isPending} onClick={() => resend.mutate(n.id)}>
                  Resend now
                </Button>
                <Text size="xs" c="dimmed">Goes to the member&apos;s current {n.channel === 'SMS' ? 'phone number' : 'address'}.</Text>
              </Group>
            )}
          </Stack>
        )}
      </QueryState>
    </Drawer>
  );
}

function Detail({ k, v }: { k: string; v: ReactNode }) {
  return (
    <Group gap="sm" wrap="nowrap" align="flex-start">
      <Text size="sm" c="dimmed" w={120} style={{ flexShrink: 0 }}>{k}</Text>
      {typeof v === 'string' ? <Text size="sm" style={{ wordBreak: 'break-word' }}>{v}</Text> : v}
    </Group>
  );
}

// ------------------------------------------------------------------ test send

function TestMessageModal({ opened, onClose }: { opened: boolean; onClose: () => void }) {
  const { user } = useAuth();
  const orgWide = hasAnyRole(user, ORG_WIDE);
  const [channel, setChannel] = useState<'EMAIL' | 'SMS'>('EMAIL');
  const [target, setTarget] = useState<'member' | 'address'>(orgWide ? 'address' : 'member');
  const [memberId, setMemberId] = useState<number | null>(null);
  const [to, setTo] = useState('');
  const [message, setMessage] = useState('');
  const send = useApiMutation<TestMessageRequest, NotificationLogEntry>({
    request: (body) => ({ path: '/api/v1/messaging/test', body }),
    invalidate: [LOG],
    silentError: true,
  });
  const result = send.data;
  const close = () => { send.reset(); onClose(); };
  const ready = target === 'member' ? memberId != null : to.trim().length > 0;

  return (
    <Modal opened={opened} onClose={close} title="Send a test message" size="md">
      <Stack gap="sm">
        <Text size="sm" c="dimmed">
          Verifies the provider setup end to end. The message is delivered immediately and appears in the delivery log.
        </Text>
        <SegmentedControl fullWidth value={channel} onChange={(v) => { setChannel(v as 'EMAIL' | 'SMS'); send.reset(); }}
          data={[{ value: 'EMAIL', label: 'E-mail' }, { value: 'SMS', label: 'SMS' }]} />
        {orgWide && (
          <SegmentedControl fullWidth size="xs" value={target} onChange={(v) => setTarget(v as 'member' | 'address')}
            data={[{ value: 'address', label: channel === 'SMS' ? 'Phone number' : 'E-mail address' }, { value: 'member', label: 'A member' }]} />
        )}
        {target === 'member'
          ? <MemberSearch label="Member" value={memberId} onChange={(id) => setMemberId(id)} required />
          : <TextInput label={channel === 'SMS' ? 'Mobile number' : 'E-mail address'} required value={to}
              placeholder={channel === 'SMS' ? '+91 98765 43210' : 'you@example.com'}
              onChange={(e) => setTo(e.currentTarget.value)} />}
        <Textarea label="Message (optional)" maxLength={500} autosize minRows={2} value={message}
          onChange={(e) => setMessage(e.currentTarget.value)} />
        {send.error && <Alert color="red" icon={<IconAlertTriangle size={18} />}>{errorMessage(send.error)}</Alert>}
        {result && (
          <Alert color={result.status === 'SENT' ? 'teal' : result.status === 'PENDING' ? 'yellow' : 'red'}
            icon={result.status === 'SENT' ? <IconCircleCheck size={18} /> : <IconAlertTriangle size={18} />}
            title={result.status === 'SENT' ? `Sent via ${result.provider}` : label(result.status)}>
            {result.status === 'SENT'
              ? <>To {result.destination}{result.providerMessageId ? <> · id <Code>{result.providerMessageId}</Code></> : null}
                  {result.segments != null ? ` · ${result.segments} segment(s)` : null}</>
              : result.lastError ?? 'Queued for delivery; check the log shortly.'}
          </Alert>
        )}
        <Group justify="flex-end">
          <Button variant="default" onClick={close}>Close</Button>
          <Button leftSection={<IconSend size={16} />} loading={send.isPending} disabled={!ready}
            onClick={() => send.mutate({
              channel,
              memberId: target === 'member' ? memberId ?? undefined : undefined,
              to: target === 'address' ? to.trim() : undefined,
              message: message.trim() || undefined,
            })}>
            Send
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
