import { AreaChart, BarChart } from '@mantine/charts';
import { Anchor, Box, Group, Progress, ScrollArea, SimpleGrid, Stack, Table, Text, Tooltip } from '@mantine/core';
import { IconCalendarEvent, IconHourglass, IconUserCheck, IconUsersGroup } from '@tabler/icons-react';
import dayjs from 'dayjs';
import { Link } from 'react-router-dom';
import { QueryState } from '@/components/QueryState';
import { StatCard } from '@/components/StatCard';
import { label, number, pct, WEEKDAYS } from '@/lib/format';
import type { ClassesResponse, ClassTimeSlotStats } from './classesTypes';
import { ChartCard, Definition, EmptyChart, heatColor, heatText, MetaLine } from './components';
import type { AnalyticsFilters } from './types';
import { filterQuery, useAnalytics } from './useAnalytics';

const hh = (h: number) => `${String(h).padStart(2, '0')}:00`;
const shortName = (n: string | null, id: number) => (n ?? `Trainer ${id}`).replace(/\s*\(Coach\)$/, '');

/** Weekday × start-hour grid of average fill rate; only hours that have classes are shown. */
function TimeSlotGrid({ slots }: { slots: ClassTimeSlotStats[] }) {
  const hours = [...new Set(slots.map((s) => s.hour))].sort((a, b) => a - b);
  const byKey = new Map(slots.map((s) => [`${s.weekday}-${s.hour}`, s]));
  const grid = { display: 'grid', gridTemplateColumns: `44px repeat(${hours.length}, minmax(52px, 1fr))`, gap: 4 } as const;
  return (
    <ScrollArea type="auto" offsetScrollbars>
      <Box miw={Math.max(360, 60 + hours.length * 56)}>
        <Box style={grid} mb={4}>
          <span />
          {hours.map((h) => (
            <Text key={h} size="xs" c="dimmed" ta="center">{hh(h)}</Text>
          ))}
        </Box>
        {WEEKDAYS.map((day, i) => (
          <Box key={day} style={grid} mb={4}>
            <Text size="xs" c="dimmed" fw={500} style={{ alignSelf: 'center' }}>{day}</Text>
            {hours.map((h) => {
              const s = byKey.get(`${i + 1}-${h}`);
              const fill = s?.fillRate ?? 0;
              return (
                <Tooltip key={h} withArrow openDelay={80} disabled={!s}
                  label={s ? `${day} ${hh(h)} · ${s.sessions} classes · ${s.occupied}/${s.capacity} seats (${pct(s.fillRate)})`
                    + (s.waitlistJoins ? ` · ${s.waitlistJoins} waitlisted` : '') : ''}>
                  <Box h={30} style={{
                    borderRadius: 4, background: s ? heatColor(fill, 'indigo') : 'var(--mantine-color-default-hover)',
                    color: heatText(fill), fontSize: 11, display: 'flex', alignItems: 'center', justifyContent: 'center',
                  }}>
                    {s ? pct(s.fillRate, 0) : ''}
                  </Box>
                </Tooltip>
              );
            })}
          </Box>
        ))}
      </Box>
    </ScrollArea>
  );
}

export function ClassesTab({ filters, branchNames }: { filters: AnalyticsFilters; branchNames: Record<number, string> }) {
  const q = useAnalytics<ClassesResponse>('/analytics/classes', filterQuery(filters));
  const d = q.data;
  const s = d?.summary;
  const types = d?.byClassType ?? [];
  const instructors = d?.instructors ?? [];
  const daily = (d?.daily ?? []).map((p) => ({
    label: dayjs(p.date).format('DD MMM'),
    occupied: p.occupied,
    empty: Math.max(0, p.capacity - p.occupied),
    attended: p.attended,
  }));

  return (
    <QueryState isLoading={q.isLoading} error={q.error}>
      {d && s && (
        <Stack gap="md">
          <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }}>
            <StatCard label="Classes held" value={number(s.sessions)} icon={<IconCalendarEvent size={20} />} color="blue"
              hint={`${number(s.cancelledSessions)} cancelled · ${number(s.sessionsFull)} full`} />
            <StatCard label="Fill rate" value={pct(s.fillRate)} icon={<IconUsersGroup size={20} />} color="indigo"
              hint={`${number(s.occupied)} of ${number(s.capacity)} seats · ${s.avgOccupiedPerSession?.toFixed(1) ?? '—'} per class`} />
            <StatCard label="Attendance rate" value={pct(s.attendanceRate)} icon={<IconUserCheck size={20} />}
              hint={`${number(s.attended)} attended · ${number(s.noShows)} no-shows · late cancel ${pct(s.lateCancelRate)}`} />
            <StatCard label="Waitlist demand" value={number(s.waitlistJoins)} icon={<IconHourglass size={20} />} color="orange"
              hint={`${number(s.waitlistPromotions)} promoted into a freed seat`} />
          </SimpleGrid>

          <ChartCard title="Seats per day" description="Occupied vs empty seats of non-cancelled classes, by local class date.">
            {daily.every((p) => p.occupied === 0 && p.empty === 0) ? <EmptyChart h={260} /> : (
              <AreaChart h={260} data={daily} dataKey="label" type="stacked" curveType="monotone" withLegend gridAxis="xy"
                fillOpacity={0.25} withDots={daily.length <= 31}
                series={[
                  { name: 'occupied', color: 'indigo.6', label: 'Occupied seats' },
                  { name: 'empty', color: 'gray.4', label: 'Empty seats' },
                ]} />
            )}
          </ChartCard>

          <SimpleGrid cols={{ base: 1, lg: 2 }}>
            <ChartCard title="Fill rate by class type" description="Occupied seats ÷ capacity across the range.">
              {types.length === 0 ? <EmptyChart /> : (
                <BarChart h={Math.max(200, types.length * 42)} orientation="vertical" gridAxis="x" yAxisProps={{ width: 130 }}
                  xAxisProps={{ tickFormatter: (v: number) => `${v}%` }}
                  data={types.map((t) => ({ type: t.name ?? `Type ${t.classTypeId}`, fill: (t.fillRate ?? 0) * 100,
                    attendance: (t.attendanceRate ?? 0) * 100 }))}
                  dataKey="type" valueFormatter={(v) => `${v.toFixed(1)}%`} withLegend
                  series={[
                    { name: 'fill', color: 'indigo.6', label: 'Fill rate' },
                    { name: 'attendance', color: 'teal.6', label: 'Attendance rate' },
                  ]} />
              )}
            </ChartCard>
            <ChartCard title="Popular time slots" description="Average fill rate by weekday and local start hour.">
              {d.timeSlots.length === 0 ? <EmptyChart /> : <TimeSlotGrid slots={d.timeSlots} />}
            </ChartCard>
          </SimpleGrid>

          <ChartCard title="Class types">
            {types.length === 0 ? <EmptyChart h={100} text="No classes in this period." /> : (
              <Table.ScrollContainer minWidth={820}>
                <Table verticalSpacing="xs" striped highlightOnHover>
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th>Class</Table.Th>
                      <Table.Th ta="right">Classes</Table.Th>
                      <Table.Th ta="right">Seats</Table.Th>
                      <Table.Th w={170}>Fill</Table.Th>
                      <Table.Th ta="right">Attendance</Table.Th>
                      <Table.Th ta="right">Late cancels</Table.Th>
                      <Table.Th ta="right">Waitlisted</Table.Th>
                    </Table.Tr>
                  </Table.Thead>
                  <Table.Tbody>
                    {types.map((t) => (
                      <Table.Tr key={t.classTypeId}>
                        <Table.Td>
                          <Text size="sm" fw={600}>{t.name ?? `Type ${t.classTypeId}`}</Text>
                          <Text size="xs" c="dimmed">{label(t.category)}</Text>
                        </Table.Td>
                        <Table.Td ta="right">{number(t.sessions)}</Table.Td>
                        <Table.Td ta="right">{number(t.occupied)} / {number(t.capacity)}</Table.Td>
                        <Table.Td>
                          <Group gap={6} wrap="nowrap">
                            <Progress value={(t.fillRate ?? 0) * 100} color="indigo" style={{ flex: 1 }} />
                            <Text size="xs" w={44} ta="right">{pct(t.fillRate)}</Text>
                          </Group>
                        </Table.Td>
                        <Table.Td ta="right" c="teal" fw={600}>{pct(t.attendanceRate)}</Table.Td>
                        <Table.Td ta="right">{number(t.lateCancels)} <Text span size="xs" c="dimmed">({pct(t.lateCancelRate)})</Text></Table.Td>
                        <Table.Td ta="right">{number(t.waitlistJoins)}</Table.Td>
                      </Table.Tr>
                    ))}
                  </Table.Tbody>
                </Table>
              </Table.ScrollContainer>
            )}
          </ChartCard>

          <ChartCard title="Instructors">
            {instructors.length === 0 ? <EmptyChart h={100} text="No classes taught in this period." /> : (
              <Table.ScrollContainer minWidth={720}>
                <Table verticalSpacing="xs" striped highlightOnHover>
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th>Instructor</Table.Th>
                      <Table.Th ta="right">Classes taught</Table.Th>
                      <Table.Th ta="right">Seats filled</Table.Th>
                      <Table.Th w={170}>Avg fill</Table.Th>
                      <Table.Th ta="right">Attended / no-show</Table.Th>
                      <Table.Th ta="right">Attendance rate</Table.Th>
                    </Table.Tr>
                  </Table.Thead>
                  <Table.Tbody>
                    {instructors.map((t) => (
                      <Table.Tr key={t.trainerId}>
                        <Table.Td>
                          <Anchor component={Link} to={`/trainers/${t.trainerId}`} size="sm" fw={600}>
                            {shortName(t.name, t.trainerId)}
                          </Anchor>
                        </Table.Td>
                        <Table.Td ta="right">{number(t.sessionsTaught)}</Table.Td>
                        <Table.Td ta="right">{number(t.occupied)}</Table.Td>
                        <Table.Td>
                          <Group gap={6} wrap="nowrap">
                            <Progress value={(t.avgFillRate ?? 0) * 100} color="indigo" style={{ flex: 1 }} />
                            <Text size="xs" w={44} ta="right">{pct(t.avgFillRate)}</Text>
                          </Group>
                        </Table.Td>
                        <Table.Td ta="right">{number(t.attended)} / {number(t.noShows)}</Table.Td>
                        <Table.Td ta="right" c={(t.attendanceRate ?? 1) < 0.8 ? 'orange' : 'teal'} fw={600}>{pct(t.attendanceRate)}</Table.Td>
                      </Table.Tr>
                    ))}
                  </Table.Tbody>
                </Table>
              </Table.ScrollContainer>
            )}
          </ChartCard>

          <Definition>
            Fill rate = occupied seats (booked, attended or no-show) ÷ capacity of classes that were not cancelled. Attendance rate =
            attended ÷ (attended + no-show). Late-cancel rate = late cancellations ÷ all bookings except waitlist places. Waitlist
            demand counts every booking that joined a waitlist (including those later promoted).
          </Definition>
          <MetaLine meta={d.meta} branchNames={branchNames} />
        </Stack>
      )}
    </QueryState>
  );
}
