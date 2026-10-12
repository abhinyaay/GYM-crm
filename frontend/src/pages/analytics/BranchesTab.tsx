import { BarChart } from '@mantine/charts';
import { Anchor, Badge, Group, Progress, SegmentedControl, SimpleGrid, Stack, Table, Text } from '@mantine/core';
import { IconBuildingStore, IconCash, IconDoorEnter, IconMessageReport } from '@tabler/icons-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { QueryState } from '@/components/QueryState';
import { StatCard } from '@/components/StatCard';
import { StatusBadge } from '@/components/StatusBadge';
import { money, moneyShort, number } from '@/lib/format';
import { ChartCard, Definition, MetaLine } from './components';
import type { AnalyticsFilters, BranchesResponse, BranchStats } from './types';
import { filterQuery, useAnalytics } from './useAnalytics';

type Metric = 'netRevenue' | 'activeMembers' | 'visits' | 'revenuePerActiveMember';

const METRICS: { value: Metric; label: string; color: string; format: (v: number) => string }[] = [
  { value: 'netRevenue', label: 'Net revenue', color: 'teal.6', format: (v) => moneyShort(v) },
  { value: 'activeMembers', label: 'Active members', color: 'blue.6', format: (v) => number(v) },
  { value: 'visits', label: 'Visits', color: 'indigo.6', format: (v) => number(v) },
  { value: 'revenuePerActiveMember', label: 'Revenue / member', color: 'grape.6', format: (v) => moneyShort(v) },
];

const RANK_COLOR = ['yellow', 'gray', 'orange'];

export function BranchesTab({ filters, branchNames }: { filters: AnalyticsFilters; branchNames: Record<number, string> }) {
  const q = useAnalytics<BranchesResponse>('/analytics/branches', filterQuery(filters));
  const [metric, setMetric] = useState<Metric>('netRevenue');
  const d = q.data;
  const s = d?.summary;
  const ranked = [...(d?.branches ?? [])].sort((a, b) => a.revenueRank - b.revenueRank);
  const m = METRICS.find((x) => x.value === metric) ?? METRICS[0];
  const chartData = [...ranked]
    .sort((a, b) => (b[metric] ?? 0) - (a[metric] ?? 0))
    .map((b: BranchStats) => ({ branch: b.name ?? b.code ?? `Branch ${b.branchId}`, value: b[metric] ?? 0 }));

  return (
    <QueryState isLoading={q.isLoading} error={q.error}>
      {d && s && (
        <Stack gap="md">
          <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }}>
            <StatCard label="Branches" value={number(s.branches)} icon={<IconBuildingStore size={20} />} color="blue"
              hint={`${number(s.activeMembers)} active members`} />
            <StatCard label="Net revenue" value={moneyShort(s.netRevenue)} icon={<IconCash size={20} />}
              hint={money(s.netRevenue)} />
            <StatCard label="Visits" value={number(s.visits)} icon={<IconDoorEnter size={20} />} color="indigo"
              hint={s.activeMembers ? `${(s.visits / s.activeMembers).toFixed(1)} per active member` : undefined} />
            <StatCard label="Open complaints" value={number(s.openComplaints)} icon={<IconMessageReport size={20} />} color="red"
              hint={<Anchor component={Link} to="/complaints" size="xs">view complaints</Anchor>} />
          </SimpleGrid>

          <ChartCard
            title="Branch comparison"
            description="Pick a metric to compare branches."
            action={
              <SegmentedControl size="xs" value={metric} onChange={(v) => setMetric(v as Metric)}
                data={METRICS.map((x) => ({ value: x.value, label: x.label }))} />
            }
          >
            <BarChart h={Math.max(160, chartData.length * 52)} orientation="vertical" gridAxis="x" yAxisProps={{ width: 150 }}
              data={chartData} dataKey="branch" valueFormatter={m.format}
              series={[{ name: 'value', color: m.color, label: m.label }]} />
          </ChartCard>

          <ChartCard title="Ranked by net revenue" description="Tied branches share the better rank.">
            <Table.ScrollContainer minWidth={1100}>
              <Table verticalSpacing="sm" striped highlightOnHover>
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th w={60}>Rank</Table.Th>
                    <Table.Th>Branch</Table.Th>
                    <Table.Th ta="right">Net revenue</Table.Th>
                    <Table.Th ta="right">Revenue / member</Table.Th>
                    <Table.Th ta="right">Active members</Table.Th>
                    <Table.Th ta="right">New members</Table.Th>
                    <Table.Th ta="right">Visits</Table.Th>
                    <Table.Th ta="right">Visits / member</Table.Th>
                    <Table.Th w={170}>Peak occupancy</Table.Th>
                    <Table.Th ta="right">Open complaints</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {ranked.map((b) => (
                    <Table.Tr key={b.branchId}>
                      <Table.Td>
                        <Badge circle size="lg" variant={b.revenueRank <= 3 ? 'filled' : 'light'} color={RANK_COLOR[b.revenueRank - 1] ?? 'gray'}>
                          {b.revenueRank}
                        </Badge>
                      </Table.Td>
                      <Table.Td>
                        <Group gap={6} wrap="nowrap">
                          <Anchor component={Link} to={`/branches/${b.branchId}`} fw={600} size="sm">
                            {b.name ?? branchNames[b.branchId] ?? `Branch ${b.branchId}`}
                          </Anchor>
                          {b.status && b.status !== 'ACTIVE' && <StatusBadge value={b.status} size="xs" />}
                        </Group>
                        <Text size="xs" c="dimmed">{b.code ?? '—'} · capacity {number(b.capacity)}</Text>
                      </Table.Td>
                      <Table.Td ta="right" fw={600}>{money(b.netRevenue)}</Table.Td>
                      <Table.Td ta="right">{money(b.revenuePerActiveMember)}</Table.Td>
                      <Table.Td ta="right">{number(b.activeMembers)}</Table.Td>
                      <Table.Td ta="right">{number(b.newMembers)}</Table.Td>
                      <Table.Td ta="right">{number(b.visits)}</Table.Td>
                      <Table.Td ta="right">{b.visitsPerActiveMember?.toFixed(1) ?? '—'}</Table.Td>
                      <Table.Td>
                        <Group gap={6} wrap="nowrap">
                          <Progress value={Math.min(100, b.peakOccupancyPct ?? 0)} color={(b.peakOccupancyPct ?? 0) > 85 ? 'red' : 'cyan'} style={{ flex: 1 }} />
                          <Text size="xs" w={48} ta="right">{b.peakOccupancyPct == null ? '—' : `${b.peakOccupancyPct.toFixed(1)}%`}</Text>
                        </Group>
                        <Text size="xs" c="dimmed">{b.peakAvgOccupancy?.toFixed(1) ?? '—'} people at peak hour</Text>
                      </Table.Td>
                      <Table.Td ta="right" c={b.openComplaints > 0 ? 'red' : undefined} fw={b.openComplaints > 0 ? 600 : undefined}>
                        {number(b.openComplaints)}
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </Table.ScrollContainer>
          </ChartCard>

          <Definition>
            Revenue by invoice branch; members by home branch. Peak occupancy = highest hourly average head-count ÷ capacity.
            Open complaints = OPEN, IN_PROGRESS, ESCALATED or REOPENED right now (not limited to the date range).
          </Definition>
          <MetaLine meta={d.meta} branchNames={branchNames} />
        </Stack>
      )}
    </QueryState>
  );
}
