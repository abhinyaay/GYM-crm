import { BarChart, LineChart } from '@mantine/charts';
import { Group, Paper, Progress, SimpleGrid, Stack, Table, Text } from '@mantine/core';
import { IconCalendarX, IconId, IconRefresh, IconTrendingDown } from '@tabler/icons-react';
import dayjs from 'dayjs';
import { QueryState } from '@/components/QueryState';
import { StatCard } from '@/components/StatCard';
import { number, pct } from '@/lib/format';
import { ChartCard, Definition, DonutWithLegend, EmptyChart, MetaLine, PALETTE } from './components';
import type { AnalyticsFilters, MembershipsResponse } from './types';
import { filterQuery, useAnalytics } from './useAnalytics';

const asPct = (v: number | null) => (v == null ? null : Math.round(v * 10000) / 100);

export function MembershipsTab({ filters, branchNames }: { filters: AnalyticsFilters; branchNames: Record<number, string> }) {
  const q = useAnalytics<MembershipsResponse>('/analytics/memberships', filterQuery(filters));
  const d = q.data;
  const s = d?.summary;

  const monthly = (d?.monthly ?? []).map((m) => ({
    label: dayjs(`${m.month}-01`).format('MMM YY'),
    renewal: asPct(m.renewalRate),
    churn: asPct(m.churnRate),
    newSubs: m.newSubscriptions,
    ended: m.endedSubscriptions,
  }));

  return (
    <QueryState isLoading={q.isLoading} error={q.error}>
      {d && s && (
        <Stack gap="md">
          <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }}>
            <StatCard label="Active subscriptions" value={number(s.activeSubscriptions)} icon={<IconId size={20} />} color="blue"
              hint="with current access today" />
            <StatCard label="Renewal rate" value={pct(s.renewalRate)} icon={<IconRefresh size={20} />}
              hint={`${number(s.renewedSubscriptions)} renewed of ${number(s.endedSubscriptions)} ended`} />
            <StatCard label="Avg monthly churn" value={pct(s.avgMonthlyChurnRate)} icon={<IconTrendingDown size={20} />}
              color={(s.avgMonthlyChurnRate ?? 0) > 0.1 ? 'red' : 'orange'} hint="mean of the monthly churn rates below" />
            <StatCard label="Cancellations" value={number(s.cancellations)} icon={<IconCalendarX size={20} />} color="red"
              hint="by cancellation date in range" />
          </SimpleGrid>

          <ChartCard
            title="Renewal and churn rate by month"
            description="Renewal rate = renewed ÷ subscriptions that ended in the month. Churn rate = members whose access ended without renewal ÷ members active at the start of the month."
          >
            {monthly.length === 0 ? <EmptyChart h={280} /> : (
              <LineChart h={280} data={monthly} dataKey="label" unit="%" connectNulls gridAxis="xy" withLegend curveType="monotone"
                valueFormatter={(v) => v.toFixed(1)}
                series={[{ name: 'renewal', color: 'teal.6', label: 'Renewal rate' }, { name: 'churn', color: 'red.6', label: 'Churn rate' }]} />
            )}
          </ChartCard>

          <ChartCard title="Monthly detail" description="Members whose grace window is still open are reported as pending, not churned. Future months are not reported.">
            <Table.ScrollContainer minWidth={820}>
              <Table verticalSpacing="xs" striped highlightOnHover>
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>Month</Table.Th>
                    <Table.Th ta="right">Ended</Table.Th>
                    <Table.Th ta="right">Renewed</Table.Th>
                    <Table.Th ta="right">Renewal rate</Table.Th>
                    <Table.Th ta="right">Members at start</Table.Th>
                    <Table.Th ta="right">Churned</Table.Th>
                    <Table.Th ta="right">Pending</Table.Th>
                    <Table.Th ta="right">Churn rate</Table.Th>
                    <Table.Th ta="right">New subs</Table.Th>
                    <Table.Th ta="right">Frozen (days)</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {d.monthly.map((m) => (
                    <Table.Tr key={m.month}>
                      <Table.Td fw={500}>{dayjs(`${m.month}-01`).format('MMM YYYY')}</Table.Td>
                      <Table.Td ta="right">{number(m.endedSubscriptions)}</Table.Td>
                      <Table.Td ta="right">{number(m.renewedSubscriptions)}</Table.Td>
                      <Table.Td ta="right" c="teal" fw={600}>{pct(m.renewalRate)}</Table.Td>
                      <Table.Td ta="right">{number(m.membersAtStart)}</Table.Td>
                      <Table.Td ta="right">{number(m.churnedMembers)}</Table.Td>
                      <Table.Td ta="right" c={m.pendingMembers ? 'orange' : 'dimmed'}>{number(m.pendingMembers)}</Table.Td>
                      <Table.Td ta="right" c="red" fw={600}>{pct(m.churnRate)}</Table.Td>
                      <Table.Td ta="right">{number(m.newSubscriptions)}</Table.Td>
                      <Table.Td ta="right">{number(m.subscriptionsFrozen)} ({number(m.frozenDays)})</Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </Table.ScrollContainer>
          </ChartCard>

          <SimpleGrid cols={{ base: 1, lg: 2 }}>
            <ChartCard title="Plan mix" description="Subscriptions with current access, by plan (as of today).">
              <DonutWithLegend
                chartLabel={number(s.activeSubscriptions)}
                data={d.planMix.map((p, i) => ({
                  name: p.planName ?? `Plan ${p.planId}`,
                  value: p.activeSubscriptions,
                  color: PALETTE[i % PALETTE.length],
                  display: number(p.activeSubscriptions),
                  hint: pct(p.share),
                }))}
              />
            </ChartCard>
            <ChartCard title="Upcoming expiries" description="Cumulative windows from today, split by whether a committed follow-on subscription exists.">
              {d.upcomingExpiries.every((w) => w.expiring === 0) ? <EmptyChart /> : (
                <BarChart h={220} type="stacked" gridAxis="y" withLegend
                  data={d.upcomingExpiries.map((w) => ({ window: `≤ ${w.withinDays} days`, withRenewal: w.withRenewal, withoutRenewal: w.withoutRenewal }))}
                  dataKey="window"
                  series={[{ name: 'withRenewal', color: 'teal.6', label: 'Renewal in place' }, { name: 'withoutRenewal', color: 'orange.6', label: 'No renewal yet' }]} />
              )}
              <SimpleGrid cols={3} mt="sm">
                {d.upcomingExpiries.map((w) => (
                  <div key={w.withinDays}>
                    <Text size="xs" c="dimmed">Next {w.withinDays} days</Text>
                    <Text fw={700}>{number(w.expiring)}</Text>
                    <Text size="xs" c="orange">{number(w.withoutRenewal)} without renewal</Text>
                  </div>
                ))}
              </SimpleGrid>
            </ChartCard>
          </SimpleGrid>

          <SimpleGrid cols={{ base: 1, lg: 2 }}>
            <ChartCard title="Freeze usage" description="Current subscriptions that have used part of their freeze allowance.">
              <SimpleGrid cols={2} spacing="lg">
                <Paper bg="var(--mantine-color-default-hover)" p="sm">
                  <Text size="xs" c="dimmed" tt="uppercase" fw={600}>Currently frozen</Text>
                  <Text fz={24} fw={700}>{number(d.freezeUsage.currentlyFrozen)}</Text>
                </Paper>
                <Paper bg="var(--mantine-color-default-hover)" p="sm">
                  <Text size="xs" c="dimmed" tt="uppercase" fw={600}>Avg freeze days used</Text>
                  <Text fz={24} fw={700}>{d.freezeUsage.avgFreezeDaysUsed?.toFixed(1) ?? '—'}</Text>
                </Paper>
              </SimpleGrid>
              <Text size="sm" mt="md">
                {number(d.freezeUsage.activeSubscriptionsWithFreezeUsed)} active subscriptions have used a freeze
              </Text>
              <Group gap="xs" wrap="nowrap" mt={4}>
                <Progress value={(d.freezeUsage.shareWithFreezeUsed ?? 0) * 100} color="cyan" style={{ flex: 1 }} />
                <Text size="sm" fw={600}>{pct(d.freezeUsage.shareWithFreezeUsed)}</Text>
              </Group>
            </ChartCard>
            <ChartCard title="Cancellations by plan" description="After activation = the member had already started using the subscription.">
              {d.cancellationsByPlan.length === 0 ? <EmptyChart h={160} text="No cancellations in this period." /> : (
                <BarChart h={Math.max(160, d.cancellationsByPlan.length * 44)} orientation="vertical" type="stacked" gridAxis="x" withLegend
                  yAxisProps={{ width: 130 }}
                  data={d.cancellationsByPlan.map((c) => ({
                    plan: c.planName ?? `Plan ${c.planId}`,
                    afterActivation: c.afterActivation,
                    beforeActivation: c.cancellations - c.afterActivation,
                  }))}
                  dataKey="plan"
                  series={[{ name: 'afterActivation', color: 'red.6', label: 'After activation' }, { name: 'beforeActivation', color: 'gray.5', label: 'Before activation' }]} />
              )}
            </ChartCard>
          </SimpleGrid>

          <Definition>
            Monthly figures are per calendar month overlapping the date range; plan mix, upcoming expiries and freeze usage are
            measured as of today. A term counts as renewed when a committed subscription follows it within the plan's grace period.
          </Definition>
          <MetaLine meta={d.meta} branchNames={branchNames} />
        </Stack>
      )}
    </QueryState>
  );
}
