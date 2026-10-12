import { Alert, Anchor, Group, Paper, SimpleGrid, Table, Text, Title } from '@mantine/core';
import { AreaChart } from '@mantine/charts';
import {
  IconAlertTriangle, IconCalendarDue, IconCash, IconDoorEnter, IconMessageReport, IconReceipt, IconRefresh,
  IconSnowflake, IconUserPlus, IconUsers,
} from '@tabler/icons-react';
import dayjs from 'dayjs';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import type { AdminDashboard, Page, Subscription } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { BranchSelect } from '@/components/BranchSelect';
import { PageHeader } from '@/components/PageHeader';
import { QueryState } from '@/components/QueryState';
import { StatCard } from '@/components/StatCard';
import { date, dateTime, money, moneyShort, number, pct } from '@/lib/format';
import { useApiQuery } from '@/lib/useApi';

interface RevenuePoint { period: string; gross: number; refunds: number; net: number }

export default function DashboardPage() {
  const { user } = useAuth();
  const [branchId, setBranchId] = useState<number | null>(
    user && user.branchIds.length === 1 ? user.branchIds[0] : null,
  );
  const dash = useApiQuery<AdminDashboard>('/api/v1/dashboard/admin', { branchId: branchId ?? undefined });
  const expiring = useApiQuery<Page<Subscription>>('/api/v1/subscriptions', {
    status: 'ACTIVE', endingFrom: dayjs().format('YYYY-MM-DD'), endingTo: dayjs().add(7, 'day').format('YYYY-MM-DD'),
    branchId: branchId ?? undefined, size: 8, sort: 'endDate,asc',
  });
  const revenue = useApiQuery<{ series: RevenuePoint[] }>('/analytics/revenue', {
    branchId: branchId ?? undefined, granularity: 'week',
    from: dayjs().subtract(12, 'week').format('YYYY-MM-DD'), to: dayjs().format('YYYY-MM-DD'),
  }, { retry: false });
  const d = dash.data;

  return (
    <>
      <PageHeader
        title="Dashboard"
        subtitle={d ? `Business date ${date(d.businessDate)} · updated ${dateTime(d.generatedAt)} (cached up to 60 s)` : undefined}
        actions={<BranchSelect allowAll value={branchId} onChange={setBranchId} w={260} aria-label="Branch" />}
      />
      <QueryState isLoading={dash.isLoading} error={dash.error}>
        {d && (
          <>
            {d.degraded.length > 0 && (
              <Alert color="yellow" icon={<IconAlertTriangle size={18} />} mb="md">
                Some figures are temporarily unavailable ({d.degraded.join(', ')}): the analytics service did not respond.
                Operational numbers below are live.
              </Alert>
            )}
            <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} mb="md">
              <StatCard label="Active memberships" value={number(d.activeMemberships)} icon={<IconUsers size={20} />}
                hint={`${number(d.totalMembers)} members on record`} />
              <StatCard label="Revenue this month" value={moneyShort(d.revenueThisMonth)} icon={<IconCash size={20} />}
                hint={`${money(d.refundsThisMonth)} refunded`} color="green" />
              <StatCard label="Check-ins today" value={number(d.checkInsToday)} icon={<IconDoorEnter size={20} />} color="blue" />
              <StatCard label="Member churn (month)" value={d.memberChurn ? pct(d.memberChurn.churnRate, 2) : '—'}
                icon={<IconRefresh size={20} />} color="orange"
                hint={d.memberChurn ? `${d.memberChurn.churnedMembers} of ${d.memberChurn.membersAtStart} · renewal rate ${pct(d.memberChurn.renewalRate, 0)}` : 'analytics unavailable'} />
              <StatCard label="Pending payments" value={number(d.pendingInvoices)} icon={<IconReceipt size={20} />} color="yellow"
                hint={`${money(d.pendingAmount)} outstanding · ${d.overdueInvoices} overdue`} />
              <StatCard label="New registrations" value={number(d.newRegistrationsThisMonth)} icon={<IconUserPlus size={20} />} color="grape"
                hint={`${d.renewalsThisMonth} renewals this month`} />
              <StatCard label="Expiring in 7 days" value={number(d.expiringNext7Days)} icon={<IconCalendarDue size={20} />} color="red"
                hint={`${d.expiredThisMonth} expired this month · ${d.membershipsInGrace} in grace`} />
              <StatCard label="Frozen / open complaints" value={`${d.frozenMemberships} / ${d.openComplaints}`}
                icon={<IconSnowflake size={20} />} color="cyan" hint={<Anchor component={Link} to="/complaints" size="xs"><IconMessageReport size={12} /> view complaints</Anchor>} />
            </SimpleGrid>
          </>
        )}
      </QueryState>
      <SimpleGrid cols={{ base: 1, lg: 2 }}>
        <Paper withBorder p="md">
          <Group justify="space-between" mb="sm">
            <Title order={4}>Weekly revenue</Title>
            <Anchor component={Link} to="/insights" size="sm">Open analytics</Anchor>
          </Group>
          {revenue.error ? (
            <Text c="dimmed" size="sm">Analytics unavailable right now.</Text>
          ) : (
            <AreaChart h={240} data={revenue.data?.series ?? []} dataKey="period" valueFormatter={(v) => moneyShort(v)}
              series={[{ name: 'net', color: 'teal.6', label: 'Net revenue' }, { name: 'refunds', color: 'red.5', label: 'Refunds' }]}
              curveType="monotone" withLegend />
          )}
        </Paper>
        <Paper withBorder p="md">
          <Group justify="space-between" mb="sm">
            <Title order={4}>Memberships ending this week</Title>
            <Text size="sm" c="dimmed">{expiring.data?.totalElements ?? 0} total</Text>
          </Group>
          <QueryState isLoading={expiring.isLoading} error={expiring.error} empty={expiring.data?.content.length === 0}
            emptyText="No memberships end in the next 7 days.">
            <Table verticalSpacing="xs">
              <Table.Thead><Table.Tr><Table.Th>Member</Table.Th><Table.Th>Plan</Table.Th><Table.Th>Ends</Table.Th></Table.Tr></Table.Thead>
              <Table.Tbody>
                {expiring.data?.content.map((s) => (
                  <Table.Tr key={s.id}>
                    <Table.Td><Anchor component={Link} to={`/members/${s.memberId}`} size="sm">{s.memberCode}</Anchor></Table.Td>
                    <Table.Td>{s.planName}</Table.Td>
                    <Table.Td>{date(s.endDate)}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </QueryState>
        </Paper>
      </SimpleGrid>
    </>
  );
}
