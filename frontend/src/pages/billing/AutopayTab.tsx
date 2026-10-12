import {
  Alert, Anchor, Badge, Button, Group, Paper, Select, SimpleGrid, Stack, Table, Text, Title,
} from '@mantine/core';
import { IconPlayerPlay } from '@tabler/icons-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import type { AutopayAttempt, AutopayRunSummary, AutopayStatus, UpcomingRenewal } from '@/api/payments';
import type { Page } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { hasAnyRole, MANAGERS, ORG_WIDE } from '@/auth/roles';
import { Pager } from '@/components/Pager';
import { QueryState } from '@/components/QueryState';
import { StatCard } from '@/components/StatCard';
import { StatusBadge } from '@/components/StatusBadge';
import { date, dateTime, label, money } from '@/lib/format';
import { useApiMutation, useApiQuery } from '@/lib/useApi';

const STATUSES: AutopayStatus[] = ['SCHEDULED', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'SKIPPED', 'CANCELLED'];

/** Recurring billing for staff: renewals coming up, every debit attempt, retries and a manual run. */
export function AutopayTab() {
  const { user } = useAuth();
  const canRetry = hasAnyRole(user, MANAGERS);
  const canRun = hasAnyRole(user, ORG_WIDE);
  const [days, setDays] = useState('14');
  const [status, setStatus] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const upcoming = useApiQuery<UpcomingRenewal[]>('/api/v1/autopay/upcoming', { days });
  const attempts = useApiQuery<Page<AutopayAttempt>>('/api/v1/autopay/attempts', { status: status ?? undefined, page, size: 20 });
  const [summary, setSummary] = useState<AutopayRunSummary | null>(null);

  const run = useApiMutation<void, AutopayRunSummary>({
    request: () => ({ path: '/api/v1/autopay/run' }),
    invalidate: ['/api/v1/autopay', '/api/v1/payments', '/api/v1/subscriptions', '/api/v1/members'],
    onSuccess: setSummary,
  });
  const retry = useApiMutation<number, AutopayAttempt>({
    request: (id) => ({ path: `/api/v1/autopay/attempts/${id}/retry` }),
    invalidate: ['/api/v1/autopay', '/api/v1/payments', '/api/v1/subscriptions'],
    successMessage: (a) => `Retry ${label(a.status).toLowerCase()}`,
  });

  const up = upcoming.data ?? [];
  const rows = attempts.data?.content ?? [];
  const atRisk = up.filter((u) => !u.methodUsable).length;
  const latestFailedByInvoice = new Map<number, number>();
  rows.forEach((a) => {
    const seen = latestFailedByInvoice.get(a.invoiceId);
    if (seen === undefined || a.attemptNo > (rows.find((r) => r.id === seen)?.attemptNo ?? 0)) latestFailedByInvoice.set(a.invoiceId, a.id);
  });

  return (
    <Stack gap="lg">
      <SimpleGrid cols={{ base: 1, sm: 3 }}>
        <StatCard label={`Autopay renewals in ${days} days`} value={up.length} />
        <StatCard label="Expected collections" value={money(up.reduce((s, u) => s + u.planPrice, 0))} hint="plan price, excl. GST" />
        <StatCard label="Method problems" value={atRisk} hint="expired / removed / no mandate" />
      </SimpleGrid>

      <Paper withBorder p="md">
        <Group justify="space-between" mb="sm">
          <div>
            <Title order={4}>Upcoming autopay renewals</Title>
            <Text size="sm" c="dimmed">
              Renewal invoices are raised 3 days before the term ends; members get a notice 24 h before each debit.
            </Text>
          </div>
          <Group>
            <Select w={130} value={days} onChange={(v) => v && setDays(v)} allowDeselect={false}
              data={[{ value: '7', label: '7 days' }, { value: '14', label: '14 days' }, { value: '30', label: '30 days' }]} />
            {canRun && (
              <Button leftSection={<IconPlayerPlay size={16} />} variant="light" loading={run.isPending} onClick={() => run.mutate()}>
                Run autopay now
              </Button>
            )}
          </Group>
        </Group>
        {summary && (
          <Alert color={summary.failed || summary.failures ? 'yellow' : 'teal'} mb="sm" withCloseButton onClose={() => setSummary(null)}>
            Renewals scheduled: {summary.renewalsScheduled} · debits attempted: {summary.debitsAttempted} ·
            succeeded: {summary.succeeded} · failed: {summary.failed}{summary.failures ? ` · errors: ${summary.failures}` : ''}
          </Alert>
        )}
        <QueryState isLoading={upcoming.isLoading} error={upcoming.error} empty={up.length === 0}
          emptyText="No autopay renewals in this window.">
          <Table.ScrollContainer minWidth={640}>
            <Table verticalSpacing="xs" fz="sm">
              <Table.Thead>
                <Table.Tr><Table.Th>Member</Table.Th><Table.Th>Plan</Table.Th><Table.Th>Term ends</Table.Th>
                  <Table.Th ta="right">Plan price</Table.Th><Table.Th>Method</Table.Th></Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {up.map((u) => (
                  <Table.Tr key={u.subscriptionId}>
                    <Table.Td>
                      <Anchor component={Link} to={`/members/${u.memberId}`} size="sm">{u.memberName}</Anchor>
                      <Text size="xs" c="dimmed">{u.memberCode}</Text>
                    </Table.Td>
                    <Table.Td>{u.planName}</Table.Td>
                    <Table.Td>{date(u.endDate)}</Table.Td>
                    <Table.Td ta="right">{money(u.planPrice)}</Table.Td>
                    <Table.Td>
                      {u.paymentMethodLabel ?? '—'}
                      {!u.methodUsable && <Badge ml={6} size="xs" color="red" variant="light">will fail</Badge>}
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
        </QueryState>
      </Paper>

      <Paper withBorder p="md">
        <Group justify="space-between" mb="sm">
          <Title order={4}>Debit attempts</Title>
          <Select placeholder="Any status" clearable w={170} value={status}
            onChange={(v) => { setStatus(v); setPage(0); }} data={STATUSES.map((s) => ({ value: s, label: label(s) }))} />
        </Group>
        <QueryState isLoading={attempts.isLoading} error={attempts.error} empty={rows.length === 0}
          emptyText="No autopay debits yet.">
          <Table.ScrollContainer minWidth={820}>
            <Table verticalSpacing="xs" fz="sm" striped>
              <Table.Thead>
                <Table.Tr><Table.Th>Scheduled</Table.Th><Table.Th>Member</Table.Th><Table.Th>Invoice</Table.Th>
                  <Table.Th>Method</Table.Th><Table.Th ta="right">Amount</Table.Th><Table.Th>Status</Table.Th><Table.Th /></Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {rows.map((a) => (
                  <Table.Tr key={a.id}>
                    <Table.Td>{dateTime(a.scheduledAt)}<Text size="xs" c="dimmed">Attempt {a.attemptNo}</Text></Table.Td>
                    <Table.Td><Anchor component={Link} to={`/members/${a.memberId}`} size="sm">{a.memberName}</Anchor></Table.Td>
                    <Table.Td>{a.invoiceNumber}</Table.Td>
                    <Table.Td>{a.paymentMethodLabel}</Table.Td>
                    <Table.Td ta="right">{money(a.amount)}</Table.Td>
                    <Table.Td>
                      <StatusBadge value={a.status} />
                      {a.failureReason && <Text size="xs" c="dimmed">{a.failureReason}</Text>}
                    </Table.Td>
                    <Table.Td>
                      {canRetry && a.status === 'FAILED' && latestFailedByInvoice.get(a.invoiceId) === a.id && (
                        <Button size="compact-xs" variant="light" loading={retry.isPending} onClick={() => retry.mutate(a.id)}>
                          Retry now
                        </Button>
                      )}
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
          <Pager page={attempts.data} onChange={setPage} />
        </QueryState>
      </Paper>
    </Stack>
  );
}
