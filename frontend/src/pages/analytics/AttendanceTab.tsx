import { AreaChart, BarChart, LineChart } from '@mantine/charts';
import { Alert, Badge, Group, Paper, Progress, SimpleGrid, Stack, Text, ThemeIcon } from '@mantine/core';
import { IconClock, IconDoorEnter, IconFlame, IconUsers, IconCalendarStats } from '@tabler/icons-react';
import { QueryState } from '@/components/QueryState';
import { StatCard } from '@/components/StatCard';
import { label, number, WEEKDAYS } from '@/lib/format';
import { ChartCard, Definition, EmptyChart, MetaLine, periodLabel, WeekHourHeatmap } from './components';
import type { AnalyticsFilters, AttendanceResponse, PeakHoursResponse } from './types';
import { filterQuery, useAnalytics } from './useAnalytics';

const minutes = (v: number | null | undefined) => (v == null ? '—' : `${Math.round(v)} min`);
const hh = (h: number) => `${String(h).padStart(2, '0')}:00`;

export function AttendanceTab({ filters, branchNames }: { filters: AnalyticsFilters; branchNames: Record<number, string> }) {
  const att = useAnalytics<AttendanceResponse>('/analytics/attendance', filterQuery(filters, true));
  const peak = useAnalytics<PeakHoursResponse>('/analytics/attendance/peak-hours', filterQuery(filters));
  const d = att.data;
  const s = d?.summary;

  const series = (d?.series ?? []).map((p) => ({
    label: periodLabel(p.period, d?.meta.granularity),
    visits: p.visits,
    uniqueMembers: p.uniqueMembers,
    avgDuration: p.avgDurationMinutes,
  }));
  const weekday = [...(d?.byWeekday ?? [])]
    .sort((a, b) => a.isoDay - b.isoDay)
    .map((w) => ({ day: WEEKDAYS[w.isoDay - 1] ?? w.weekday, avgPerDay: w.avgVisitsPerDay ?? 0, visits: w.visits, share: w.share }));

  return (
    <Stack gap="md">
      <QueryState isLoading={att.isLoading} error={att.error}>
        {d && s && (
          <Stack gap="md">
            <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }}>
              <StatCard label="Visits" value={number(s.totalVisits)} icon={<IconDoorEnter size={20} />} color="blue"
                hint={`${s.avgVisitsPerDay?.toFixed(1) ?? '—'} per day on average`} />
              <StatCard label="Unique members" value={number(s.uniqueMembers)} icon={<IconUsers size={20} />}
                hint={`${s.avgVisitsPerMember?.toFixed(1) ?? '—'} visits per member`} />
              <StatCard label="Avg visit duration" value={minutes(s.avgDurationMinutes)} icon={<IconClock size={20} />} color="grape"
                hint={`from ${number(s.visitsWithDuration)} visits with a genuine check-out`} />
              <StatCard label="Auto checked-out" value={number(s.autoCheckedOutVisits)} icon={<IconCalendarStats size={20} />} color="orange"
                hint={`${s.totalVisits ? ((s.autoCheckedOutVisits / s.totalVisits) * 100).toFixed(1) : '0'}% of visits had no check-out`} />
            </SimpleGrid>

            <ChartCard title="Visits and unique members" description="Check-ins bucketed in each branch's local time zone.">
              {series.length === 0 || series.every((p) => p.visits === 0) ? <EmptyChart h={280} /> : (
                <AreaChart h={280} data={series} dataKey="label" curveType="monotone" withLegend gridAxis="xy" fillOpacity={0.15}
                  withDots={series.length <= 31}
                  series={[{ name: 'visits', color: 'blue.6', label: 'Visits' }, { name: 'uniqueMembers', color: 'teal.6', label: 'Unique members' }]} />
              )}
            </ChartCard>

            <SimpleGrid cols={{ base: 1, lg: 2 }}>
              <ChartCard title="Visits by weekday" description="Average visits per calendar day of that weekday in the range.">
                {weekday.every((w) => w.visits === 0) ? <EmptyChart /> : (
                  <BarChart h={240} data={weekday} dataKey="day" gridAxis="y"
                    valueFormatter={(v) => v.toFixed(1)}
                    series={[{ name: 'avgPerDay', color: 'indigo.6', label: 'Avg visits / day' }]} />
                )}
              </ChartCard>
              <ChartCard title="Average visit duration" description="Visits that were auto checked-out or are still open are excluded.">
                {series.every((p) => p.avgDuration == null) ? <EmptyChart /> : (
                  <LineChart h={240} data={series} dataKey="label" curveType="monotone" connectNulls gridAxis="xy" unit=" min"
                    withDots={series.length <= 31} valueFormatter={(v) => `${Math.round(v)}`}
                    series={[{ name: 'avgDuration', color: 'grape.6', label: 'Avg duration' }]} />
                )}
              </ChartCard>
            </SimpleGrid>
          </Stack>
        )}
      </QueryState>

      <QueryState isLoading={peak.isLoading} error={peak.error}>
        {peak.data && (
          <Stack gap="md">
            <ChartCard
              title="Peak hours"
              description={`Average check-ins per weekday and local hour (${number(peak.data.totalCheckIns)} check-ins). Cell = check-ins in that hour ÷ number of such weekdays in the range.`}
            >
              {peak.data.totalCheckIns === 0 ? <EmptyChart h={200} text="No check-ins in this period." /> : (
                <WeekHourHeatmap rows={peak.data.heatmap} />
              )}
            </ChartCard>

            <SimpleGrid cols={{ base: 1, md: 3 }}>
              {peak.data.peakWindows.map((w, i) => (
                <Paper key={`${w.isoDay}-${w.hour}`} withBorder p="md">
                  <Group gap="sm" wrap="nowrap">
                    <ThemeIcon variant="light" color={i === 0 ? 'red' : 'orange'} size={38}>
                      <IconFlame size={20} />
                    </ThemeIcon>
                    <div>
                      <Text size="xs" c="dimmed" tt="uppercase" fw={600}>Peak #{i + 1}</Text>
                      <Text fw={700}>{label(w.weekday)} · {w.window}</Text>
                      <Text size="xs" c="dimmed">{w.avgCheckIns?.toFixed(2) ?? '—'} avg check-ins in that hour</Text>
                    </div>
                  </Group>
                </Paper>
              ))}
            </SimpleGrid>

            {peak.data.occupancy ? (
              <ChartCard
                title={`Occupancy vs capacity — ${peak.data.occupancy.branchName ?? 'branch'}`}
                description={`Average head-count per hour of day (all days in range, empty days count as 0). Visits without a genuine check-out are assumed to last ${minutes(peak.data.occupancy.assumedVisitMinutes)}. Utilisation = occupancy ÷ capacity.`}
                action={<Badge variant="light" color="gray">Capacity {number(peak.data.occupancy.capacity)}</Badge>}
              >
                <SimpleGrid cols={{ base: 1, md: 3 }} mb="md">
                  <div>
                    <Text size="xs" c="dimmed" tt="uppercase" fw={600}>Peak hour</Text>
                    <Text fw={700} fz="lg">{peak.data.occupancy.peakHour == null ? '—' : `${hh(peak.data.occupancy.peakHour)}–${hh((peak.data.occupancy.peakHour + 1) % 24)}`}</Text>
                  </div>
                  <div>
                    <Text size="xs" c="dimmed" tt="uppercase" fw={600}>Peak avg occupancy</Text>
                    <Text fw={700} fz="lg">{peak.data.occupancy.peakAvgOccupancy?.toFixed(1) ?? '—'} people</Text>
                  </div>
                  <div>
                    <Text size="xs" c="dimmed" tt="uppercase" fw={600}>Peak utilisation</Text>
                    <Group gap="xs" wrap="nowrap">
                      <Progress value={Math.min(100, peak.data.occupancy.peakUtilisationPct ?? 0)} style={{ flex: 1 }}
                        color={(peak.data.occupancy.peakUtilisationPct ?? 0) > 85 ? 'red' : 'teal'} />
                      <Text fw={700}>{peak.data.occupancy.peakUtilisationPct?.toFixed(1) ?? '—'}%</Text>
                    </Group>
                  </div>
                </SimpleGrid>
                <BarChart h={240} gridAxis="y"
                  data={peak.data.occupancy.hours.map((h) => ({ hour: hh(h.hour), occupancy: h.avgOccupancy ?? 0 }))}
                  dataKey="hour"
                  yAxisProps={{ tickFormatter: (v: number) => String(v) }}
                  valueFormatter={(v) => {
                    const cap = peak.data?.occupancy?.capacity;
                    return cap ? `${v.toFixed(2)} people · ${((v / cap) * 100).toFixed(1)}% of capacity` : v.toFixed(2);
                  }}
                  series={[{ name: 'occupancy', color: 'cyan.6', label: 'Avg head-count' }]}
                />
              </ChartCard>
            ) : (
              <Alert variant="light" color="blue">
                Select a single branch to see hourly occupancy against that branch's capacity.
              </Alert>
            )}
            <Definition>
              Peak-hour figures use each branch's local time zone. Top windows are the three weekday × hour cells with the highest average check-ins.
            </Definition>
            <MetaLine meta={peak.data.meta} branchNames={branchNames} />
          </Stack>
        )}
      </QueryState>
    </Stack>
  );
}
