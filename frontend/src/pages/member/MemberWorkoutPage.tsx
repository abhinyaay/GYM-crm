import {
  Alert, Badge, Button, Card, Grid, Group, NumberInput, Paper, SimpleGrid, Stack, Table, Text, Textarea, ThemeIcon, Title,
} from '@mantine/core';
import { LineChart } from '@mantine/charts';
import { useForm } from '@mantine/form';
import { IconBarbell, IconCalendar, IconClipboardList, IconTarget } from '@tabler/icons-react';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { ApiError } from '@/api/client';
import type { Page, PlanExercise, Progress, WorkoutPlan } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { PageHeader } from '@/components/PageHeader';
import { Pager } from '@/components/Pager';
import { QueryState } from '@/components/QueryState';
import { StatCard } from '@/components/StatCard';
import { StatusBadge } from '@/components/StatusBadge';
import { date, dateTime, label, WEEKDAYS } from '@/lib/format';
import { errorMessage } from '@/lib/notify';
import { useApiMutation, useApiQuery } from '@/lib/useApi';

// ---------------------------------------------------------------------------------------------
// Shared building blocks (also used by the trainer's client page)
// ---------------------------------------------------------------------------------------------

/** true when the query failed because the member simply has no active plan. */
export function isNotFound(err: unknown): boolean {
  return err instanceof ApiError && err.status === 404;
}

function formatSeconds(s: number): string {
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  const rest = s % 60;
  return rest ? `${m}m ${rest}s` : `${m} min`;
}

/** "4 × 8" / "3 × 45s" */
export function prescription(e: Pick<PlanExercise, 'sets' | 'reps' | 'durationSeconds'>): string {
  if (e.reps != null) return `${e.sets} × ${e.reps}`;
  if (e.durationSeconds != null) return `${e.sets} × ${formatSeconds(e.durationSeconds)}`;
  return `${e.sets} sets`;
}

export function groupByDay<T extends { dayNumber: number; position?: number }>(items: T[]): [number, T[]][] {
  const map = new Map<number, T[]>();
  items.forEach((e) => map.set(e.dayNumber, [...(map.get(e.dayNumber) ?? []), e]));
  return [...map.entries()]
    .sort(([a], [b]) => a - b)
    .map(([day, list]) => [day, [...list].sort((x, y) => (x.position ?? 0) - (y.position ?? 0))]);
}

/** Plan header: name, goal, difficulty, dates and trainer notes. */
export function PlanOverview({ plan, notesTitle = 'Notes from your trainer' }: { plan: WorkoutPlan; notesTitle?: string }) {
  const days = new Set(plan.exercises.map((e) => e.dayNumber)).size;
  return (
    <Stack gap="md">
      <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }}>
        <StatCard label="Goal" value={<Text span fw={700} fz="lg">{label(plan.goal)}</Text>} icon={<IconTarget size={20} />} />
        <StatCard label="Level" value={<Text span fw={700} fz="lg">{label(plan.difficulty)}</Text>} icon={<IconBarbell size={20} />} color="indigo" />
        <StatCard label="Training days / week" value={days} hint={`${plan.exercises.length} exercises`}
          icon={<IconClipboardList size={20} />} color="grape" />
        <StatCard label="Programme" value={`${plan.durationWeeks} wk`}
          hint={plan.startDate ? `${date(plan.startDate)} → ${date(plan.endDate)}` : undefined}
          icon={<IconCalendar size={20} />} color="orange" />
      </SimpleGrid>
      {plan.trainerNotes && (
        <Alert color="teal" variant="light" title={notesTitle}>
          <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>{plan.trainerNotes}</Text>
        </Alert>
      )}
      {plan.description && <Text size="sm" c="dimmed">{plan.description}</Text>}
    </Stack>
  );
}

/** Exercises grouped by weekday (dayNumber 1-7 = Mon..Sun). */
export function PlanDays({ exercises }: { exercises: PlanExercise[] }) {
  const grouped = groupByDay(exercises);
  const trainingDays = new Set(grouped.map(([d]) => d));
  const restDays = WEEKDAYS.filter((_, i) => !trainingDays.has(i + 1));
  if (grouped.length === 0) {
    return <Text c="dimmed" ta="center" py="lg">This plan has no exercises yet.</Text>;
  }
  return (
    <Stack gap="md">
      <SimpleGrid cols={{ base: 1, lg: 2 }}>
        {grouped.map(([day, list]) => (
          <Paper key={day} withBorder radius="md" p="md">
            <Group justify="space-between" mb="xs">
              <Group gap="xs">
                <Badge size="lg" radius="sm" variant="filled">{WEEKDAYS[day - 1] ?? `Day ${day}`}</Badge>
                <Text fw={600}>Day {day}</Text>
              </Group>
              <Text size="xs" c="dimmed">
                {[...new Set(list.map((e) => label(e.muscleGroup)))].join(' · ')}
              </Text>
            </Group>
            <Table.ScrollContainer minWidth={420}>
              <Table verticalSpacing={6} fz="sm">
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>Exercise</Table.Th>
                    <Table.Th>Sets × reps</Table.Th>
                    <Table.Th>Load</Table.Th>
                    <Table.Th>Rest</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {list.map((e, i) => (
                    <Table.Tr key={`${e.exerciseId}-${i}`}>
                      <Table.Td>
                        <Text size="sm" fw={500}>{e.exerciseName}</Text>
                        {e.notes && <Text size="xs" c="dimmed">{e.notes}</Text>}
                      </Table.Td>
                      <Table.Td>{prescription(e)}</Table.Td>
                      <Table.Td>{e.weightKg != null ? `${e.weightKg} kg` : '—'}</Table.Td>
                      <Table.Td>{e.restSeconds != null ? formatSeconds(e.restSeconds) : '—'}</Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </Table.ScrollContainer>
          </Paper>
        ))}
      </SimpleGrid>
      {restDays.length > 0 && <Text size="sm" c="dimmed">Rest / active recovery: {restDays.join(', ')}</Text>}
    </Stack>
  );
}

const fmtNum = (v: number | null, unit = '') => (v == null ? '—' : `${v}${unit}`);

interface ProgressForm {
  weightKg: number | string;
  bodyFatPct: number | string;
  waistCm: number | string;
  restingHeartRate: number | string;
  notes: string;
}

const toNum = (v: number | string) => (v === '' || v == null ? undefined : Number(v));

function inRange(v: number | string, min: number, max: number, name: string) {
  if (v === '' || v == null) return null;
  const n = Number(v);
  return Number.isNaN(n) || n < min || n > max ? `${name} must be between ${min} and ${max}` : null;
}

/** Form to record a body measurement. */
export function RecordProgressForm({ memberId, onSaved }: { memberId: number; onSaved?: () => void }) {
  const form = useForm<ProgressForm>({
    initialValues: { weightKg: '', bodyFatPct: '', waistCm: '', restingHeartRate: '', notes: '' },
    validate: {
      weightKg: (v) => inRange(v, 20, 400, 'Weight'),
      bodyFatPct: (v) => inRange(v, 2, 70, 'Body fat'),
      waistCm: (v) => inRange(v, 30, 250, 'Waist'),
      restingHeartRate: (v) => inRange(v, 25, 220, 'Heart rate'),
      notes: (v) => (v.length > 1000 ? 'At most 1000 characters' : null),
    },
  });
  const record = useApiMutation<ProgressForm, Progress>({
    request: (v) => ({
      path: `/api/v1/workouts/members/${memberId}/progress`,
      body: {
        weightKg: toNum(v.weightKg),
        bodyFatPct: toNum(v.bodyFatPct),
        waistCm: toNum(v.waistCm),
        restingHeartRate: toNum(v.restingHeartRate),
        notes: v.notes.trim() || undefined,
      },
    }),
    invalidate: [`/api/v1/workouts/members/${memberId}`, '/api/v1/dashboard', '/api/v1/trainers'],
    successMessage: 'Measurement recorded',
    silentError: true,
    onSuccess: () => {
      form.reset();
      onSaved?.();
    },
  });
  const v = form.values;
  const empty = v.weightKg === '' && v.bodyFatPct === '' && v.waistCm === '' && v.restingHeartRate === '' && !v.notes.trim();

  return (
    <form onSubmit={form.onSubmit((values) => record.mutate(values))}>
      <Stack gap="sm">
        <SimpleGrid cols={{ base: 1, xs: 2 }}>
          <NumberInput label="Weight (kg)" min={20} max={400} decimalScale={1} step={0.1} {...form.getInputProps('weightKg')} />
          <NumberInput label="Body fat (%)" min={2} max={70} decimalScale={1} step={0.1} {...form.getInputProps('bodyFatPct')} />
          <NumberInput label="Waist (cm)" min={30} max={250} decimalScale={1} step={0.5} {...form.getInputProps('waistCm')} />
          <NumberInput label="Resting heart rate (bpm)" min={25} max={220} allowDecimal={false} {...form.getInputProps('restingHeartRate')} />
        </SimpleGrid>
        <Textarea label="Notes" autosize minRows={2} maxLength={1000} {...form.getInputProps('notes')} />
        {record.error && <Alert color="red">{errorMessage(record.error)}</Alert>}
        <Group justify="flex-end">
          <Button type="submit" loading={record.isPending} disabled={empty}>Save measurement</Button>
        </Group>
      </Stack>
    </form>
  );
}

/** Weight chart + history table + record form for one member. */
export function ProgressSection({ memberId, title = 'Progress' }: { memberId: number; title?: string }) {
  const [page, setPage] = useState(0);
  // newest first; the chart uses the latest 100 points
  const chartQuery = useApiQuery<Page<Progress>>(`/api/v1/workouts/members/${memberId}/progress`, { size: 100 });
  const tableQuery = useApiQuery<Page<Progress>>(`/api/v1/workouts/members/${memberId}/progress`, { page, size: 10 });

  const points = useMemo(
    () =>
      (chartQuery.data?.content ?? [])
        .filter((p) => p.weightKg != null)
        .slice()
        .reverse()
        .map((p) => ({ date: dayjs(p.recordedAt).format('DD MMM'), weight: Number(p.weightKg) })),
    [chartQuery.data],
  );
  const first = points[0]?.weight;
  const last = points[points.length - 1]?.weight;
  const change = first != null && last != null ? Math.round((last - first) * 10) / 10 : null;

  return (
    <Grid gutter="md">
      <Grid.Col span={{ base: 12, md: 8 }}>
        <Paper withBorder radius="md" p="md" h="100%">
          <Group justify="space-between" mb="sm">
            <Title order={4}>{title}</Title>
            {change != null && points.length > 1 && (
              <Badge variant="light" color={change <= 0 ? 'teal' : 'orange'}>
                {change > 0 ? '+' : ''}{change} kg since {points[0].date}
              </Badge>
            )}
          </Group>
          <QueryState isLoading={chartQuery.isLoading} error={chartQuery.error} empty={points.length === 0}
            emptyText="No weight measurements yet — record one to start the chart.">
            <LineChart
              h={260}
              data={points}
              dataKey="date"
              series={[{ name: 'weight', label: 'Weight (kg)', color: 'teal.6' }]}
              curveType="monotone"
              unit=" kg"
              connectNulls
              yAxisProps={{ domain: ['dataMin - 2', 'dataMax + 2'] }}
              valueFormatter={(v) => v.toFixed(1)}
            />
          </QueryState>
          <Title order={5} mt="lg" mb="xs">History</Title>
          <QueryState isLoading={tableQuery.isLoading} error={tableQuery.error}
            empty={tableQuery.data?.content.length === 0} emptyText="No measurements recorded.">
            <Table.ScrollContainer minWidth={560}>
              <Table striped fz="sm">
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>Recorded</Table.Th>
                    <Table.Th>Weight</Table.Th>
                    <Table.Th>Body fat</Table.Th>
                    <Table.Th>Waist</Table.Th>
                    <Table.Th>Resting HR</Table.Th>
                    <Table.Th>Notes</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {tableQuery.data?.content.map((p) => (
                    <Table.Tr key={p.id}>
                      <Table.Td>{dateTime(p.recordedAt)}</Table.Td>
                      <Table.Td>{fmtNum(p.weightKg, ' kg')}</Table.Td>
                      <Table.Td>{fmtNum(p.bodyFatPct, '%')}</Table.Td>
                      <Table.Td>{fmtNum(p.waistCm, ' cm')}</Table.Td>
                      <Table.Td>{fmtNum(p.restingHeartRate, ' bpm')}</Table.Td>
                      <Table.Td maw={220}><Text size="sm" truncate="end">{p.notes ?? '—'}</Text></Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </Table.ScrollContainer>
            <Pager page={tableQuery.data} onChange={setPage} />
          </QueryState>
        </Paper>
      </Grid.Col>
      <Grid.Col span={{ base: 12, md: 4 }}>
        <Paper withBorder radius="md" p="md">
          <Title order={4} mb="sm">Record a measurement</Title>
          <Text size="xs" c="dimmed" mb="sm">All fields are optional — fill in what you measured today.</Text>
          <RecordProgressForm memberId={memberId} onSaved={() => setPage(0)} />
        </Paper>
      </Grid.Col>
    </Grid>
  );
}

// ---------------------------------------------------------------------------------------------

export default function MemberWorkoutPage() {
  const { user } = useAuth();
  const memberId = user?.memberId ?? null;
  const plan = useApiQuery<WorkoutPlan>(memberId ? `/api/v1/workouts/members/${memberId}/active-plan` : null);
  const noPlan = isNotFound(plan.error);

  if (!memberId) {
    return (
      <>
        <PageHeader title="My workout" />
        <Alert color="yellow">Your account is not linked to a member profile.</Alert>
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="My workout"
        subtitle={plan.data ? plan.data.name : 'Your training plan and progress'}
        actions={plan.data && <StatusBadge value={plan.data.status} size="lg" />}
      />
      <Stack gap="xl">
        {noPlan ? (
          <Card withBorder radius="md" p="xl">
            <Stack align="center" gap="xs">
              <ThemeIcon size={56} radius="xl" variant="light">
                <IconBarbell size={30} />
              </ThemeIcon>
              <Title order={4}>No active workout plan yet</Title>
              <Text c="dimmed" ta="center" maw={460}>
                Your trainer will assign a personalised plan after your first assessment. In the meantime you can
                track your measurements below.
              </Text>
            </Stack>
          </Card>
        ) : (
          <QueryState isLoading={plan.isLoading} error={plan.error}>
            {plan.data && (
              <Stack gap="lg">
                <PlanOverview plan={plan.data} />
                <PlanDays exercises={plan.data.exercises} />
              </Stack>
            )}
          </QueryState>
        )}
        <ProgressSection memberId={memberId} title="My progress" />
      </Stack>
    </>
  );
}
