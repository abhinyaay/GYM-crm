import { BarChart } from '@mantine/charts';
import { Select, SimpleGrid, Stack, Table, Text, Tooltip } from '@mantine/core';
import { IconUserCheck, IconUserOff, IconUserPlus, IconUsers } from '@tabler/icons-react';
import dayjs from 'dayjs';
import { useState } from 'react';
import { QueryState } from '@/components/QueryState';
import { StatCard } from '@/components/StatCard';
import { label, number, pct } from '@/lib/format';
import {
  ChartCard, Definition, DonutWithLegend, EmptyChart, heatColor, heatText, MetaLine, periodLabel,
} from './components';
import type { AnalyticsFilters, Cohort, MembersResponse } from './types';
import { filterQuery, useAnalytics } from './useAnalytics';

const SEGMENT_COLORS: Record<string, string> = {
  HIGHLY_ENGAGED: 'teal.6', REGULAR: 'blue.6', LOW: 'orange.6', INACTIVE: 'red.6',
};
const SEGMENT_RANGES: Record<string, string> = {
  HIGHLY_ENGAGED: '12+ visits', REGULAR: '4–11 visits', LOW: '1–3 visits', INACTIVE: '0 visits',
};

export function MembersTab({ filters, branchNames }: { filters: AnalyticsFilters; branchNames: Record<number, string> }) {
  const [cohortMonths, setCohortMonths] = useState('12');
  const q = useAnalytics<MembersResponse>('/analytics/members', { ...filterQuery(filters, true), cohortMonths });
  const d = q.data;
  const s = d?.summary;
  const series = (d?.series ?? []).map((p) => ({ label: periodLabel(p.period, d?.meta.granularity), newMembers: p.newMembers }));

  return (
    <QueryState isLoading={q.isLoading} error={q.error}>
      {d && s && (
        <Stack gap="md">
          <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }}>
            <StatCard label="Members on record" value={number(s.totalMembers)} icon={<IconUsers size={20} />} color="blue" />
            <StatCard label="Active members" value={number(s.activeMembers)} icon={<IconUserCheck size={20} />}
              hint={Object.entries(s.activeByStatus).map(([k, v]) => `${number(v)} ${label(k).toLowerCase()}`).join(' · ') || 'with current access'} />
            <StatCard label="New registrations" value={number(s.newMembers)} icon={<IconUserPlus size={20} />} color="grape"
              hint="registered in the selected range" />
            <StatCard label="Without access" value={number(s.membersWithoutAccess)} icon={<IconUserOff size={20} />} color="orange"
              hint={`${pct(s.totalMembers ? s.membersWithoutAccess / s.totalMembers : null)} of members on record`} />
          </SimpleGrid>

          <SimpleGrid cols={{ base: 1, lg: 2 }}>
            <ChartCard title="New registrations" description="Members registered per period (business time zone).">
              {series.every((p) => p.newMembers === 0) ? <EmptyChart h={240} /> : (
                <BarChart h={240} data={series} dataKey="label" gridAxis="y"
                  series={[{ name: 'newMembers', color: 'grape.6', label: 'New members' }]} />
              )}
            </ChartCard>
            <ChartCard title="Engagement of active members" description="Check-ins in the last 30 days (incl. today), members with current access only.">
              <DonutWithLegend
                chartLabel={number(s.activeMembers)}
                data={d.engagement.map((e) => ({
                  name: label(e.segment),
                  value: e.members,
                  color: SEGMENT_COLORS[e.segment] ?? 'gray.6',
                  display: number(e.members),
                  hint: `${SEGMENT_RANGES[e.segment] ?? `${e.minVisits}+ visits`} · ${pct(e.share)}`,
                }))}
              />
            </ChartCard>
          </SimpleGrid>

          <ChartCard
            title="Retention cohorts"
            description="Share of each registration-month cohort holding a committed subscription that overlaps month +k."
            action={
              <Select size="xs" w={140} value={cohortMonths} onChange={(v) => v && setCohortMonths(v)} allowDeselect={false}
                aria-label="Number of cohorts"
                data={['3', '6', '12', '18', '24'].map((n) => ({ value: n, label: `${n} cohorts` }))} />
            }
          >
            {d.cohorts.length === 0 ? <EmptyChart h={120} text="No registrations in the cohort window." /> : <CohortTable cohorts={d.cohorts} />}
            <Definition>
              Month 0 is the registration month, so it can be below 100% when a member registered without buying a plan.
              Cells to the right of the diagonal are in the future and are left blank.
            </Definition>
          </ChartCard>

          <Definition>
            Active = members with current access (ACTIVE or FROZEN within term, or in GRACE). Cohorts are not affected by the date
            range; they cover the last {cohortMonths} registration months.
          </Definition>
          <MetaLine meta={d.meta} branchNames={branchNames} />
        </Stack>
      )}
    </QueryState>
  );
}

function CohortTable({ cohorts }: { cohorts: Cohort[] }) {
  const maxOffset = Math.max(0, ...cohorts.flatMap((c) => c.retention.map((r) => r.monthOffset)));
  const offsets = Array.from({ length: maxOffset + 1 }, (_, i) => i);
  return (
    <Table.ScrollContainer minWidth={Math.max(480, 160 + offsets.length * 64)}>
      <Table withColumnBorders={false} verticalSpacing={4} horizontalSpacing={4} style={{ borderCollapse: 'separate', borderSpacing: 3 }}>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Cohort</Table.Th>
            <Table.Th ta="right">Members</Table.Th>
            {offsets.map((o) => (
              <Table.Th key={o} ta="center" fz="xs">M{o}</Table.Th>
            ))}
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {cohorts.map((c) => {
            const byOffset = new Map(c.retention.map((r) => [r.monthOffset, r]));
            return (
              <Table.Tr key={c.cohort}>
                <Table.Td fw={500} style={{ whiteSpace: 'nowrap' }}>{dayjs(`${c.cohort}-01`).format('MMM YYYY')}</Table.Td>
                <Table.Td ta="right">{number(c.members)}</Table.Td>
                {offsets.map((o) => {
                  const cell = byOffset.get(o);
                  if (!cell) return <Table.Td key={o} />;
                  const rate = cell.rate ?? 0;
                  return (
                    <Tooltip key={o} label={`${dayjs(`${c.cohort}-01`).add(o, 'month').format('MMM YYYY')}: ${cell.retainedMembers} of ${c.members} retained`} withArrow>
                      <Table.Td ta="center" style={{ background: heatColor(rate), color: heatText(rate), borderRadius: 4, cursor: 'default' }}>
                        <Text size="xs" fw={600} c="inherit">{cell.rate == null ? '—' : `${Math.round(cell.rate * 100)}%`}</Text>
                      </Table.Td>
                    </Tooltip>
                  );
                })}
              </Table.Tr>
            );
          })}
        </Table.Tbody>
      </Table>
    </Table.ScrollContainer>
  );
}
