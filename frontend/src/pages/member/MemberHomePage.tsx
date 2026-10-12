import {
  Alert, Anchor, Avatar, Badge, Button, Card, Grid, Group, Paper, Progress as ProgressBar, SimpleGrid, Stack, Table, Text,
  ThemeIcon, Title,
} from '@mantine/core';
import {
  IconActivity, IconArrowRight, IconBarbell, IconCalendarEvent, IconCalendarStats, IconClock, IconDoorEnter,
  IconDoorExit, IconHistory, IconUser,
} from '@tabler/icons-react';
import dayjs from 'dayjs';
import { Link } from 'react-router-dom';
import type { Member, MemberDashboard, MembershipSummary, Visit } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { QueryState } from '@/components/QueryState';
import { StatCard } from '@/components/StatCard';
import { StatusBadge } from '@/components/StatusBadge';
import { date, dateTime, fromNow, label, money } from '@/lib/format';
import { useApiMutation, useApiQuery } from '@/lib/useApi';

const RENEW_WINDOW_DAYS = 14;

function membershipCta(m: MembershipSummary): { text: string; color: string; message: string } | null {
  switch (m.state) {
    case 'NONE':
      return { text: 'Buy a membership', color: 'teal', message: "You don't have an active membership." };
    case 'PENDING_PAYMENT':
      return { text: 'Complete payment', color: 'yellow', message: 'Your membership is waiting for payment.' };
    case 'GRACE':
      return { text: 'Renew now', color: 'orange', message: 'Your membership has ended — you are in the grace period.' };
    default:
      if (m.daysRemaining != null && m.daysRemaining <= RENEW_WINDOW_DAYS && m.state !== 'SCHEDULED') {
        return { text: 'Renew', color: 'orange', message: `Your membership ends in ${m.daysRemaining} day${m.daysRemaining === 1 ? '' : 's'}.` };
      }
      return null;
  }
}

function MembershipCard({ m }: { m: MembershipSummary }) {
  const cta = membershipCta(m);
  const totalDays = m.startDate && m.endDate ? dayjs(m.endDate).diff(dayjs(m.startDate), 'day') + 1 : null;
  const usedPct = totalDays && m.daysRemaining != null
    ? Math.min(100, Math.max(0, ((totalDays - m.daysRemaining) / totalDays) * 100))
    : null;
  return (
    <Card withBorder radius="md" p="lg" h="100%">
      <Group justify="space-between" align="flex-start" mb="xs">
        <div>
          <Text size="xs" c="dimmed" tt="uppercase" fw={600}>Membership</Text>
          <Title order={3} mt={4}>{m.planName ?? 'No active plan'}</Title>
        </div>
        <StatusBadge value={m.state} size="lg" />
      </Group>
      {m.endDate && (
        <Group gap="xl" mt="sm">
          <div>
            <Text size="xs" c="dimmed">Valid until</Text>
            <Text fw={600}>{date(m.endDate)}</Text>
          </div>
          {m.daysRemaining != null && (
            <div>
              <Text size="xs" c="dimmed">Days remaining</Text>
              <Text fw={600} c={m.daysRemaining <= RENEW_WINDOW_DAYS ? 'orange' : undefined}>{m.daysRemaining}</Text>
            </div>
          )}
          {m.startDate && (
            <div>
              <Text size="xs" c="dimmed">Started</Text>
              <Text fw={600}>{date(m.startDate)}</Text>
            </div>
          )}
        </Group>
      )}
      {usedPct != null && (
        <ProgressBar value={usedPct} mt="md" size="sm" color={m.daysRemaining != null && m.daysRemaining <= RENEW_WINDOW_DAYS ? 'orange' : 'teal'}
          aria-label="Membership term used" />
      )}
      {cta ? (
        <Alert color={cta.color} variant="light" mt="md" p="sm">
          <Group justify="space-between" wrap="wrap" gap="xs">
            <Text size="sm">{cta.message}</Text>
            <Button component={Link} to="/me/membership" size="xs" color={cta.color}>{cta.text}</Button>
          </Group>
        </Alert>
      ) : (
        <Group justify="flex-end" mt="md">
          <Button component={Link} to="/me/membership" variant="subtle" size="xs" rightSection={<IconArrowRight size={14} />}>
            Manage membership
          </Button>
        </Group>
      )}
    </Card>
  );
}

function CheckInCard({ dashboard, member }: { dashboard: MemberDashboard; member: Member | undefined }) {
  const inside = dashboard.attendance.currentlyCheckedIn;
  const checkIn = useApiMutation<void, Visit>({
    request: () => ({
      path: '/api/v1/attendance/check-in',
      body: { memberId: dashboard.memberId, branchId: member?.homeBranchId, method: 'MOBILE_APP' },
    }),
    invalidate: ['/api/v1/dashboard', '/api/v1/attendance'],
    successMessage: 'Checked in — have a great workout!',
  });
  const checkOut = useApiMutation<void, Visit>({
    request: () => ({ path: '/api/v1/attendance/check-out', body: { memberId: dashboard.memberId } }),
    invalidate: ['/api/v1/dashboard', '/api/v1/attendance'],
    successMessage: (v) => (v.durationMinutes != null ? `Checked out after ${v.durationMinutes} min` : 'Checked out'),
  });
  return (
    <Card withBorder radius="md" p="lg" h="100%">
      <Text size="xs" c="dimmed" tt="uppercase" fw={600}>Gym access</Text>
      <Group mt="sm" gap="sm" wrap="nowrap">
        <ThemeIcon size={44} radius="xl" variant="light" color={inside ? 'teal' : 'gray'}>
          {inside ? <IconDoorEnter size={24} /> : <IconDoorExit size={24} />}
        </ThemeIcon>
        <div>
          <Text fw={600}>{inside ? 'You are checked in' : 'Not checked in'}</Text>
          <Text size="sm" c="dimmed">{member ? member.homeBranchName : 'Home branch'}</Text>
        </div>
      </Group>
      <Text size="xs" c="dimmed" mt="sm">
        Last visit: {dashboard.attendance.lastVisit ? `${dateTime(dashboard.attendance.lastVisit)} (${fromNow(dashboard.attendance.lastVisit)})` : 'never'}
      </Text>
      {inside ? (
        <Button fullWidth mt="md" color="orange" variant="light" leftSection={<IconDoorExit size={18} />}
          loading={checkOut.isPending} onClick={() => checkOut.mutate()}>
          Check out
        </Button>
      ) : (
        <Button fullWidth mt="md" leftSection={<IconDoorEnter size={18} />} loading={checkIn.isPending}
          disabled={!member} onClick={() => checkIn.mutate()}>
          Check in at {member?.homeBranchName ?? 'home branch'}
        </Button>
      )}
    </Card>
  );
}

export default function MemberHomePage() {
  const dash = useApiQuery<MemberDashboard>('/api/v1/dashboard/member');
  const me = useApiQuery<Member>('/api/v1/members/me');
  const d = dash.data;

  return (
    <>
      <PageHeader
        title={d ? `Welcome back, ${me.data?.firstName ?? d.name.split(' ')[0]}` : 'Welcome'}
        subtitle={d ? `Member ${d.memberCode}` : undefined}
      />
      <QueryState isLoading={dash.isLoading} error={dash.error}>
        {d && (
          <Stack gap="lg">
            <Grid gutter="md">
              <Grid.Col span={{ base: 12, md: 7 }}>
                <MembershipCard m={d.membership} />
              </Grid.Col>
              <Grid.Col span={{ base: 12, md: 5 }}>
                <CheckInCard dashboard={d} member={me.data} />
              </Grid.Col>
            </Grid>

            <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }}>
              <StatCard label="Visits this month" value={d.attendance.visitsThisMonth} icon={<IconCalendarStats size={20} />} />
              <StatCard label="Last 30 days" value={d.attendance.visitsLast30Days} icon={<IconActivity size={20} />} color="indigo" />
              <StatCard label="Total visits" value={d.attendance.totalVisits} icon={<IconHistory size={20} />} color="grape" />
              <StatCard label="Last visit" value={<Text span fw={700} fz="lg">{d.attendance.lastVisit ? fromNow(d.attendance.lastVisit) : '—'}</Text>}
                hint={d.attendance.lastVisit ? date(d.attendance.lastVisit) : 'No visits yet'} icon={<IconClock size={20} />} color="orange" />
            </SimpleGrid>

            <Grid gutter="md">
              <Grid.Col span={{ base: 12, md: 6 }}>
                <Card withBorder radius="md" p="lg" h="100%">
                  <Group justify="space-between" mb="sm">
                    <Group gap="xs">
                      <IconBarbell size={20} />
                      <Title order={4}>Workout plan</Title>
                    </Group>
                    <Anchor component={Link} to="/me/workout" size="sm">View plan</Anchor>
                  </Group>
                  {d.workoutPlan ? (
                    <Stack gap={6}>
                      <Text fw={600}>{d.workoutPlan.name}</Text>
                      <Group gap="xs">
                        <Badge variant="light">{label(d.workoutPlan.goal)}</Badge>
                        <Badge variant="light" color="indigo">{label(d.workoutPlan.difficulty)}</Badge>
                        <Badge variant="light" color="gray">{d.workoutPlan.durationWeeks} weeks</Badge>
                      </Group>
                      <Text size="sm" c="dimmed">
                        {new Set(d.workoutPlan.exercises.map((e) => e.dayNumber)).size} training days ·{' '}
                        {d.workoutPlan.exercises.length} exercises
                        {d.workoutPlan.endDate ? ` · until ${date(d.workoutPlan.endDate)}` : ''}
                      </Text>
                      {d.workoutPlan.trainerNotes && (
                        <Text size="sm" fs="italic" lineClamp={2}>“{d.workoutPlan.trainerNotes}”</Text>
                      )}
                    </Stack>
                  ) : (
                    <Text c="dimmed" size="sm">No active plan yet. Your trainer will set one up for you.</Text>
                  )}
                </Card>
              </Grid.Col>
              <Grid.Col span={{ base: 12, md: 6 }}>
                <Card withBorder radius="md" p="lg" h="100%">
                  <Group gap="xs" mb="sm">
                    <IconUser size={20} />
                    <Title order={4}>Personal trainer</Title>
                  </Group>
                  {d.trainer ? (
                    <Group wrap="nowrap">
                      <Avatar color="teal" radius="xl" size="lg">
                        {d.trainer.trainerName.split(' ').map((p) => p[0]).slice(0, 2).join('')}
                      </Avatar>
                      <div>
                        <Text fw={600}>{d.trainer.trainerName}</Text>
                        {d.trainer.goal && <Text size="sm">Goal: {d.trainer.goal}</Text>}
                        <Text size="xs" c="dimmed">Training together since {date(d.trainer.assignedAt)}</Text>
                      </div>
                    </Group>
                  ) : (
                    <Text c="dimmed" size="sm">No trainer assigned. Ask at the front desk about personal training.</Text>
                  )}
                </Card>
              </Grid.Col>
            </Grid>

            <Grid gutter="md">
              <Grid.Col span={{ base: 12, md: 6 }}>
                <Paper withBorder radius="md" p="lg" h="100%">
                  <Group gap="xs" mb="sm">
                    <IconCalendarEvent size={20} />
                    <Title order={4}>Upcoming sessions</Title>
                  </Group>
                  {d.upcomingSessions.length === 0 ? (
                    <Text c="dimmed" size="sm">No sessions booked.</Text>
                  ) : (
                    <Stack gap="xs">
                      {d.upcomingSessions.map((s) => (
                        <Group key={s.id} justify="space-between" wrap="nowrap">
                          <div>
                            <Text size="sm" fw={500}>{dayjs(s.startsAt).format('ddd, DD MMM')}</Text>
                            <Text size="xs" c="dimmed">
                              {dayjs(s.startsAt).format('HH:mm')}–{dayjs(s.endsAt).format('HH:mm')}
                              {s.notes ? ` · ${s.notes}` : ''}
                            </Text>
                          </div>
                          <StatusBadge value={s.status} />
                        </Group>
                      ))}
                    </Stack>
                  )}
                </Paper>
              </Grid.Col>
              <Grid.Col span={{ base: 12, md: 6 }}>
                <Paper withBorder radius="md" p="lg" h="100%">
                  <Group justify="space-between" mb="sm">
                    <Title order={4}>Recent payments</Title>
                    <Anchor component={Link} to="/me/payments" size="sm">All payments</Anchor>
                  </Group>
                  {d.recentPayments.length === 0 ? (
                    <Text c="dimmed" size="sm">No payments yet.</Text>
                  ) : (
                    <Table.ScrollContainer minWidth={380}>
                      <Table fz="sm" verticalSpacing={6}>
                        <Table.Thead>
                          <Table.Tr>
                            <Table.Th>Date</Table.Th>
                            <Table.Th>Invoice</Table.Th>
                            <Table.Th ta="right">Amount</Table.Th>
                            <Table.Th>Status</Table.Th>
                          </Table.Tr>
                        </Table.Thead>
                        <Table.Tbody>
                          {d.recentPayments.map((p) => (
                            <Table.Tr key={p.id}>
                              <Table.Td>{date(p.processedAt)}</Table.Td>
                              <Table.Td>{p.invoiceNumber}</Table.Td>
                              <Table.Td ta="right">{money(p.amount)}</Table.Td>
                              <Table.Td><StatusBadge value={p.status} /></Table.Td>
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
    </>
  );
}
