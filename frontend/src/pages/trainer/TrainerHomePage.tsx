import {
  Anchor, Badge, Button, Grid, Group, Paper, SimpleGrid, Stack, Table, Text, TextInput, Title,
} from '@mantine/core';
import { IconBarbell, IconCalendarEvent, IconCalendarPlus, IconSearch, IconTrendingDown, IconUsers } from '@tabler/icons-react';
import dayjs from 'dayjs';
import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import type { Trainer, TrainerAssignment, TrainerDashboard } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { PageHeader } from '@/components/PageHeader';
import { QueryState } from '@/components/QueryState';
import { StatCard } from '@/components/StatCard';
import { StatusBadge } from '@/components/StatusBadge';
import { date, fromNow } from '@/lib/format';
import { useApiQuery } from '@/lib/useApi';
import { BookSessionModal, SessionActions } from './TrainerSchedulePage';

function WeightChange({ kg }: { kg: number | null }) {
  if (kg == null) return <Text size="sm" c="dimmed">—</Text>;
  const color = kg < 0 ? 'teal' : kg > 0 ? 'orange' : 'gray';
  return (
    <Badge variant="light" color={color}>
      {kg > 0 ? '+' : ''}{Number(kg).toFixed(1)} kg
    </Badge>
  );
}

export default function TrainerHomePage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const trainerId = user?.trainerId ?? null;
  const dash = useApiQuery<TrainerDashboard>('/api/v1/dashboard/trainer');
  const trainer = useApiQuery<Trainer>(trainerId ? `/api/v1/trainers/${trainerId}` : null);
  const clients = useApiQuery<TrainerAssignment[]>(trainerId ? `/api/v1/trainers/${trainerId}/clients` : null);
  const [search, setSearch] = useState('');
  const [booking, setBooking] = useState(false);
  const d = dash.data;

  const sessions = [...(d?.todaysSessions ?? [])].sort((a, b) => a.startsAt.localeCompare(b.startsAt));
  const upcomingToday = sessions.filter((s) => s.status === 'SCHEDULED' && dayjs(s.endsAt).isAfter(dayjs())).length;
  const q = search.trim().toLowerCase();
  const rows = (d?.memberProgress ?? []).filter(
    (m) => !q || m.memberName.toLowerCase().includes(q) || m.memberCode.toLowerCase().includes(q),
  );
  const tid = d?.trainerId ?? trainerId;
  const load = d && d.maxActiveClients > 0 ? (d.assignedMembers / d.maxActiveClients) * 100 : 0;

  return (
    <>
      <PageHeader
        title={d ? `Good ${dayjs().hour() < 12 ? 'morning' : dayjs().hour() < 17 ? 'afternoon' : 'evening'}, ${d.trainerName.split(' ')[0]}` : 'My day'}
        subtitle={dayjs().format('dddd, DD MMMM YYYY')}
        actions={
          tid && (
            <>
              <Button variant="default" component={Link} to="/trainer/schedule" leftSection={<IconCalendarEvent size={16} />}>
                Full schedule
              </Button>
              <Button leftSection={<IconCalendarPlus size={16} />} onClick={() => setBooking(true)}>Book session</Button>
            </>
          )
        }
      />
      <QueryState isLoading={dash.isLoading} error={dash.error}>
        {d && (
          <Stack gap="lg">
            <SimpleGrid cols={{ base: 1, sm: 2, md: 4 }}>
              <StatCard
                label="Assigned clients"
                value={`${d.assignedMembers} / ${d.maxActiveClients}`}
                hint={`${Math.round(load)}% of capacity`}
                icon={<IconUsers size={20} />}
              />
              <StatCard label="Today's sessions" value={sessions.length} hint={`${upcomingToday} still to come`}
                icon={<IconCalendarEvent size={20} />} color="indigo" />
              <StatCard label="Active workout plans" value={d.activeWorkoutPlans} icon={<IconBarbell size={20} />} color="grape" />
              <StatCard label="Clients losing weight"
                value={d.memberProgress.filter((m) => (m.weightChangeKg ?? 0) < 0).length}
                hint={`of ${d.memberProgress.filter((m) => m.weightChangeKg != null).length} with measurements`}
                icon={<IconTrendingDown size={20} />} color="orange" />
            </SimpleGrid>

            <Grid gutter="md">
              <Grid.Col span={{ base: 12, lg: 4 }}>
                <Paper withBorder radius="md" p="md" h="100%">
                  <Group justify="space-between" mb="sm">
                    <Title order={4}>Today's sessions</Title>
                    <Anchor component={Link} to="/trainer/schedule" size="sm">Week view</Anchor>
                  </Group>
                  {sessions.length === 0 ? (
                    <Text c="dimmed" size="sm" ta="center" py="lg">No sessions today.</Text>
                  ) : (
                    <Stack gap="xs">
                      {sessions.map((s) => {
                        const now = dayjs();
                        const live = s.status === 'SCHEDULED' && now.isAfter(s.startsAt) && now.isBefore(s.endsAt);
                        return (
                          <Paper key={s.id} withBorder p="sm" radius="md"
                            style={live ? { borderColor: 'var(--mantine-color-teal-6)' } : undefined}>
                            <Group justify="space-between" wrap="nowrap" align="flex-start">
                              <div>
                                <Group gap="xs">
                                  <Text fw={700} size="sm">
                                    {dayjs(s.startsAt).format('HH:mm')}–{dayjs(s.endsAt).format('HH:mm')}
                                  </Text>
                                  {live && <Badge size="xs" color="teal" variant="filled">Now</Badge>}
                                </Group>
                                <Anchor component={Link} to={`/trainer/clients/${s.memberId}`} size="sm" fw={500}>
                                  {s.memberName}
                                </Anchor>
                                {s.notes && <Text size="xs" c="dimmed" lineClamp={2}>{s.notes}</Text>}
                              </div>
                              <Stack gap={6} align="flex-end">
                                <StatusBadge value={s.status} />
                                {tid && <SessionActions trainerId={tid} session={s} />}
                              </Stack>
                            </Group>
                          </Paper>
                        );
                      })}
                    </Stack>
                  )}
                </Paper>
              </Grid.Col>

              <Grid.Col span={{ base: 12, lg: 8 }}>
                <Paper withBorder radius="md" p="md" h="100%">
                  <Group justify="space-between" mb="sm" wrap="wrap">
                    <Title order={4}>My clients</Title>
                    <TextInput
                      size="xs"
                      placeholder="Search name or code"
                      aria-label="Search clients"
                      leftSection={<IconSearch size={14} />}
                      value={search}
                      onChange={(e) => setSearch(e.currentTarget.value)}
                    />
                  </Group>
                  {rows.length === 0 ? (
                    <Text c="dimmed" size="sm" ta="center" py="lg">
                      {d.memberProgress.length === 0 ? 'No clients assigned yet.' : 'No clients match your search.'}
                    </Text>
                  ) : (
                    <Table.ScrollContainer minWidth={640}>
                      <Table highlightOnHover verticalSpacing="xs" fz="sm">
                        <Table.Thead>
                          <Table.Tr>
                            <Table.Th>Member</Table.Th>
                            <Table.Th>Workout plan</Table.Th>
                            <Table.Th>Weight</Table.Th>
                            <Table.Th>Change</Table.Th>
                            <Table.Th>Last visit</Table.Th>
                          </Table.Tr>
                        </Table.Thead>
                        <Table.Tbody>
                          {rows.map((m) => (
                            <Table.Tr key={m.memberId} style={{ cursor: 'pointer' }}
                              onClick={() => navigate(`/trainer/clients/${m.memberId}`)}>
                              <Table.Td>
                                <Anchor component={Link} to={`/trainer/clients/${m.memberId}`} size="sm" fw={500}
                                  onClick={(e) => e.stopPropagation()}>
                                  {m.memberName}
                                </Anchor>
                                <Text size="xs" c="dimmed">{m.memberCode}</Text>
                              </Table.Td>
                              <Table.Td>
                                {m.workoutPlan ? <Text size="sm" lineClamp={1}>{m.workoutPlan}</Text>
                                  : <Badge size="sm" color="orange" variant="light">No plan</Badge>}
                              </Table.Td>
                              <Table.Td>
                                {m.latestWeightKg != null ? `${Number(m.latestWeightKg).toFixed(1)} kg` : '—'}
                                {m.lastMeasuredAt && <Text size="xs" c="dimmed">{fromNow(m.lastMeasuredAt)}</Text>}
                              </Table.Td>
                              <Table.Td><WeightChange kg={m.weightChangeKg} /></Table.Td>
                              <Table.Td>
                                {m.lastVisit ? (
                                  <>
                                    <Text size="sm">{date(m.lastVisit)}</Text>
                                    <Text size="xs" c={dayjs().diff(m.lastVisit, 'day') > 14 ? 'red' : 'dimmed'}>
                                      {fromNow(m.lastVisit)}
                                    </Text>
                                  </>
                                ) : <Text size="sm" c="dimmed">Never</Text>}
                              </Table.Td>
                            </Table.Tr>
                          ))}
                        </Table.Tbody>
                      </Table>
                    </Table.ScrollContainer>
                  )}
                </Paper>
              </Grid.Col>
            </Grid>
          </Stack>
        )}
      </QueryState>
      {tid && (
        <BookSessionModal
          trainerId={tid}
          opened={booking}
          onClose={() => setBooking(false)}
          clients={clients.data}
          availability={trainer.data?.availability}
        />
      )}
    </>
  );
}
