import { BarChart } from '@mantine/charts';
import { Anchor, Group, Progress, SimpleGrid, Stack, Table, Text } from '@mantine/core';
import { IconCalendarCheck, IconClockHour4, IconUserX, IconUsersGroup } from '@tabler/icons-react';
import { Link } from 'react-router-dom';
import { QueryState } from '@/components/QueryState';
import { StatCard } from '@/components/StatCard';
import { StatusBadge } from '@/components/StatusBadge';
import { number, pct } from '@/lib/format';
import { ChartCard, Definition, EmptyChart, MetaLine } from './components';
import type { AnalyticsFilters, TrainersResponse } from './types';
import { filterQuery, useAnalytics } from './useAnalytics';

const hours = (v: number | null | undefined) => (v == null ? '—' : `${Math.round(v * 10) / 10} h`);

export function TrainersTab({ filters, branchNames }: { filters: AnalyticsFilters; branchNames: Record<number, string> }) {
  const q = useAnalytics<TrainersResponse>('/analytics/trainers', filterQuery(filters));
  const d = q.data;
  const s = d?.summary;
  const trainers = [...(d?.trainers ?? [])].sort((a, b) => (b.utilisationPct ?? -1) - (a.utilisationPct ?? -1));
  const chartH = Math.max(180, trainers.length * 40);
  const shortName = (n: string | null, id: number) => (n ?? `Trainer ${id}`).replace(/\s*\(Coach\)$/, '');

  return (
    <QueryState isLoading={q.isLoading} error={q.error}>
      {d && s && (
        <Stack gap="md">
          <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }}>
            <StatCard label="Trainers" value={number(s.trainers)} icon={<IconUsersGroup size={20} />} color="blue"
              hint={`${number(s.activeClients)} active clients`} />
            <StatCard label="Sessions completed" value={number(s.sessionsCompleted)} icon={<IconCalendarCheck size={20} />} />
            <StatCard label="No-shows" value={number(s.sessionsNoShow)} icon={<IconUserX size={20} />} color="red"
              hint={pct(s.sessionsCompleted + s.sessionsNoShow ? s.sessionsNoShow / (s.sessionsCompleted + s.sessionsNoShow) : null) + ' of completed + no-show'} />
            <StatCard label="Utilisation" value={s.utilisationPct == null ? '—' : `${s.utilisationPct.toFixed(1)}%`} icon={<IconClockHour4 size={20} />} color="grape"
              hint={`${hours(s.bookedHours)} booked of ${hours(s.availableHours)} available`} />
          </SimpleGrid>

          {trainers.length === 0 ? (
            <ChartCard title="Trainers"><EmptyChart h={120} text="No trainers in scope." /></ChartCard>
          ) : (
            <>
              <SimpleGrid cols={{ base: 1, lg: 2 }}>
                <ChartCard title="Utilisation by trainer" description="Booked hours (completed + scheduled sessions) ÷ available hours from weekly availability slots.">
                  <BarChart h={chartH} orientation="vertical" gridAxis="x" yAxisProps={{ width: 120 }}
                    xAxisProps={{ tickFormatter: (v: number) => `${v}%` }}
                    data={trainers.map((t) => ({ trainer: shortName(t.name, t.trainerId), utilisation: t.utilisationPct ?? 0 }))}
                    dataKey="trainer" valueFormatter={(v) => `${v.toFixed(1)}%`}
                    series={[{ name: 'utilisation', color: 'grape.6', label: 'Utilisation' }]} />
                </ChartCard>
                <ChartCard title="Sessions by status" description="Sessions bucketed by local start date.">
                  <BarChart h={chartH} orientation="vertical" type="stacked" gridAxis="x" withLegend yAxisProps={{ width: 120 }}
                    data={trainers.map((t) => ({
                      trainer: shortName(t.name, t.trainerId),
                      completed: t.sessionsCompleted,
                      scheduled: t.sessionsScheduled,
                      cancelled: t.sessionsCancelled,
                      noShow: t.sessionsNoShow,
                    }))}
                    dataKey="trainer"
                    series={[
                      { name: 'completed', color: 'teal.6', label: 'Completed' },
                      { name: 'scheduled', color: 'blue.5', label: 'Scheduled' },
                      { name: 'cancelled', color: 'gray.5', label: 'Cancelled' },
                      { name: 'noShow', color: 'red.6', label: 'No-show' },
                    ]} />
                </ChartCard>
              </SimpleGrid>

              <ChartCard title="Trainer performance">
                <Table.ScrollContainer minWidth={1100}>
                  <Table verticalSpacing="xs" striped highlightOnHover>
                    <Table.Thead>
                      <Table.Tr>
                        <Table.Th>Trainer</Table.Th>
                        <Table.Th>Clients</Table.Th>
                        <Table.Th ta="right">Completed</Table.Th>
                        <Table.Th ta="right">Scheduled</Table.Th>
                        <Table.Th ta="right">Cancelled</Table.Th>
                        <Table.Th ta="right">No-show</Table.Th>
                        <Table.Th ta="right">Completion</Table.Th>
                        <Table.Th ta="right">No-show rate</Table.Th>
                        <Table.Th ta="right">Booked / available</Table.Th>
                        <Table.Th w={150}>Utilisation</Table.Th>
                        <Table.Th ta="right">Avg client visits</Table.Th>
                      </Table.Tr>
                    </Table.Thead>
                    <Table.Tbody>
                      {trainers.map((t) => (
                        <Table.Tr key={t.trainerId}>
                          <Table.Td>
                            <Group gap={6} wrap="nowrap">
                              <Anchor component={Link} to={`/trainers/${t.trainerId}`} size="sm" fw={600}>{t.name ?? `Trainer ${t.trainerId}`}</Anchor>
                              {t.status && t.status !== 'ACTIVE' && <StatusBadge value={t.status} size="xs" />}
                            </Group>
                            <Text size="xs" c="dimmed">{t.employeeCode ?? '—'} · {t.branchName ?? (t.branchId != null ? branchNames[t.branchId] : null) ?? '—'}</Text>
                          </Table.Td>
                          <Table.Td>
                            <Text size="sm">{number(t.activeClients)}{t.maxActiveClients != null && <Text span c="dimmed" size="xs"> / {t.maxActiveClients}</Text>}</Text>
                          </Table.Td>
                          <Table.Td ta="right">{number(t.sessionsCompleted)}</Table.Td>
                          <Table.Td ta="right">{number(t.sessionsScheduled)}</Table.Td>
                          <Table.Td ta="right">{number(t.sessionsCancelled)}</Table.Td>
                          <Table.Td ta="right">{number(t.sessionsNoShow)}</Table.Td>
                          <Table.Td ta="right" c="teal" fw={600}>{pct(t.completionRate)}</Table.Td>
                          <Table.Td ta="right" c={(t.noShowRate ?? 0) > 0.1 ? 'red' : undefined}>{pct(t.noShowRate)}</Table.Td>
                          <Table.Td ta="right" style={{ whiteSpace: 'nowrap' }}>{hours(t.bookedHours)} / {hours(t.availableHours)}</Table.Td>
                          <Table.Td>
                            <Group gap={6} wrap="nowrap">
                              <Progress value={Math.min(100, t.utilisationPct ?? 0)} color="grape" style={{ flex: 1 }} />
                              <Text size="xs" w={44} ta="right">{t.utilisationPct == null ? '—' : `${t.utilisationPct.toFixed(1)}%`}</Text>
                            </Group>
                          </Table.Td>
                          <Table.Td ta="right">{t.avgClientVisits?.toFixed(1) ?? '—'}</Table.Td>
                        </Table.Tr>
                      ))}
                    </Table.Tbody>
                  </Table>
                </Table.ScrollContainer>
              </ChartCard>
            </>
          )}

          <Definition>
            Completion rate = COMPLETED ÷ (COMPLETED + CANCELLED + NO_SHOW); no-show rate uses the same denominator. Active clients
            are current assignments; average client visits = check-ins by those clients in the range ÷ number of clients.
          </Definition>
          <MetaLine meta={d.meta} branchNames={branchNames} />
        </Stack>
      )}
    </QueryState>
  );
}
