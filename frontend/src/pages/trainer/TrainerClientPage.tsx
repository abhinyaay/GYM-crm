import {
  ActionIcon, Alert, Anchor, Badge, Button, Card, Grid, Group, Modal, NumberInput, Paper, ScrollArea, Select, SimpleGrid,
  Stack, Table, Tabs, Text, Textarea, TextInput, ThemeIcon, Title, Tooltip,
} from '@mantine/core';
import { DatePickerInput } from '@mantine/dates';
import {
  IconArrowLeft, IconBarbell, IconCalendarEvent, IconCalendarPlus, IconChartLine, IconEdit, IconPlus, IconTemplate,
  IconTrash,
} from '@tabler/icons-react';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ApiError } from '@/api/client';
import type {
  Difficulty, Exercise, Member, Page, Trainer, TrainerAssignment, TrainingSession, WorkoutGoal, WorkoutPlan,
} from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { PageHeader } from '@/components/PageHeader';
import { QueryState } from '@/components/QueryState';
import { StatusBadge } from '@/components/StatusBadge';
import { date, label, WEEKDAYS } from '@/lib/format';
import { errorMessage } from '@/lib/notify';
import { useApiMutation, useApiQuery } from '@/lib/useApi';
import { isNotFound, PlanDays, PlanOverview, ProgressSection, prescription } from '../member/MemberWorkoutPage';
import { BookSessionModal, SessionActions } from './TrainerSchedulePage';

const GOALS: WorkoutGoal[] = ['FAT_LOSS', 'MUSCLE_GAIN', 'STRENGTH', 'ENDURANCE', 'GENERAL_FITNESS', 'REHABILITATION'];
const DIFFICULTIES: Difficulty[] = ['BEGINNER', 'INTERMEDIATE', 'ADVANCED'];
const DAY_OPTIONS = WEEKDAYS.map((d, i) => ({ value: String(i + 1), label: d }));

type Num = number | string;

interface DraftExercise {
  key: number;
  exerciseId: string | null;
  dayNumber: string;
  sets: Num;
  reps: Num;
  weightKg: Num;
  durationSeconds: Num;
  restSeconds: Num;
  notes: string;
}

interface DraftPlan {
  name: string;
  description: string;
  goal: WorkoutGoal;
  difficulty: Difficulty;
  durationWeeks: Num;
  trainerNotes: string;
  exercises: DraftExercise[];
}

let draftKey = 0;
const blank = (v: Num) => v === '' || v == null;
const optNum = (v: Num) => (blank(v) ? undefined : Number(v));

function toDraft(plan: WorkoutPlan): DraftPlan {
  return {
    name: plan.name,
    description: plan.description ?? '',
    goal: plan.goal,
    difficulty: plan.difficulty,
    durationWeeks: plan.durationWeeks,
    trainerNotes: plan.trainerNotes ?? '',
    exercises: [...plan.exercises]
      .sort((a, b) => a.dayNumber - b.dayNumber || a.position - b.position)
      .map((e) => ({
        key: ++draftKey,
        exerciseId: String(e.exerciseId),
        dayNumber: String(e.dayNumber),
        sets: e.sets,
        reps: e.reps ?? '',
        weightKg: e.weightKg ?? '',
        durationSeconds: e.durationSeconds ?? '',
        restSeconds: e.restSeconds ?? '',
        notes: e.notes ?? '',
      })),
  };
}

/** Client-side mirror of the backend's WorkoutPlanRequest validation. Returns one message per problem. */
function validate(d: DraftPlan): string[] {
  const errors: string[] = [];
  if (!d.name.trim()) errors.push('Plan name is required');
  const weeks = Number(d.durationWeeks);
  if (!Number.isInteger(weeks) || weeks < 1 || weeks > 52) errors.push('Duration must be 1–52 weeks');
  if (d.exercises.length > 60) errors.push('At most 60 exercises');
  d.exercises.forEach((e, i) => {
    const n = `Row ${i + 1}`;
    if (!e.exerciseId) errors.push(`${n}: choose an exercise`);
    const sets = Number(e.sets);
    if (blank(e.sets) || sets < 1 || sets > 20) errors.push(`${n}: sets must be 1–20`);
    if (blank(e.reps) && blank(e.durationSeconds)) errors.push(`${n}: enter reps or a duration`);
    if (!blank(e.reps) && (Number(e.reps) < 1 || Number(e.reps) > 100)) errors.push(`${n}: reps must be 1–100`);
    if (!blank(e.durationSeconds) && (Number(e.durationSeconds) < 1 || Number(e.durationSeconds) > 7200)) {
      errors.push(`${n}: duration must be 1–7200 s`);
    }
    if (!blank(e.weightKg) && (Number(e.weightKg) < 0 || Number(e.weightKg) > 500)) errors.push(`${n}: load must be 0–500 kg`);
    if (!blank(e.restSeconds) && (Number(e.restSeconds) < 0 || Number(e.restSeconds) > 600)) errors.push(`${n}: rest must be 0–600 s`);
  });
  return errors;
}

function EditPlanModal({ plan, opened, onClose }: { plan: WorkoutPlan; opened: boolean; onClose: () => void }) {
  const [draft, setDraft] = useState<DraftPlan>(() => toDraft(plan));
  const [showErrors, setShowErrors] = useState(false);
  const catalogue = useApiQuery<Page<Exercise>>('/api/v1/workouts/exercises', { size: 100 });
  const exerciseOptions = useMemo(() => {
    const groups = new Map<string, { value: string; label: string }[]>();
    (catalogue.data?.content ?? []).forEach((e) => {
      const g = label(e.muscleGroup);
      groups.set(g, [...(groups.get(g) ?? []), { value: String(e.id), label: e.name }]);
    });
    return [...groups.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([group, items]) => ({ group, items }));
  }, [catalogue.data]);

  const save = useApiMutation<void, WorkoutPlan>({
    request: () => ({
      path: `/api/v1/workouts/plans/${plan.id}`,
      method: 'PUT',
      body: {
        name: draft.name.trim(),
        description: draft.description.trim() || null,
        goal: draft.goal,
        difficulty: draft.difficulty,
        durationWeeks: Number(draft.durationWeeks),
        trainerNotes: draft.trainerNotes.trim() || null,
        exercises: draft.exercises.map((e) => ({
          exerciseId: Number(e.exerciseId),
          dayNumber: Number(e.dayNumber),
          sets: Number(e.sets),
          reps: optNum(e.reps),
          weightKg: optNum(e.weightKg),
          durationSeconds: optNum(e.durationSeconds),
          restSeconds: optNum(e.restSeconds),
          notes: e.notes.trim() || undefined,
        })),
      },
    }),
    invalidate: ['/api/v1/workouts', '/api/v1/dashboard'],
    successMessage: 'Workout plan updated',
    silentError: true,
    onSuccess: onClose,
  });

  const errors = validate(draft);
  const set = <K extends keyof DraftPlan>(k: K, v: DraftPlan[K]) => setDraft((d) => ({ ...d, [k]: v }));
  const setRow = (key: number, patch: Partial<DraftExercise>) =>
    setDraft((d) => ({ ...d, exercises: d.exercises.map((e) => (e.key === key ? { ...e, ...patch } : e)) }));
  const addRow = () =>
    setDraft((d) => {
      const lastDay = d.exercises[d.exercises.length - 1]?.dayNumber ?? '1';
      return {
        ...d,
        exercises: [...d.exercises, {
          key: ++draftKey, exerciseId: null, dayNumber: lastDay, sets: 3, reps: 10, weightKg: '', durationSeconds: '',
          restSeconds: 60, notes: '',
        }],
      };
    });
  const removeRow = (key: number) => setDraft((d) => ({ ...d, exercises: d.exercises.filter((e) => e.key !== key) }));

  const submit = () => {
    setShowErrors(true);
    if (errors.length === 0) save.mutate();
  };

  return (
    <Modal opened={opened} onClose={onClose} title="Edit workout plan" size="95%" centered>
      <Stack>
        <SimpleGrid cols={{ base: 1, sm: 2, md: 4 }}>
          <TextInput label="Plan name" withAsterisk maxLength={120} value={draft.name}
            onChange={(e) => set('name', e.currentTarget.value)} />
          <Select label="Goal" data={GOALS.map((g) => ({ value: g, label: label(g) }))} value={draft.goal}
            onChange={(v) => v && set('goal', v as WorkoutGoal)} allowDeselect={false} />
          <Select label="Difficulty" data={DIFFICULTIES.map((g) => ({ value: g, label: label(g) }))} value={draft.difficulty}
            onChange={(v) => v && set('difficulty', v as Difficulty)} allowDeselect={false} />
          <NumberInput label="Duration (weeks)" min={1} max={52} allowDecimal={false} value={draft.durationWeeks}
            onChange={(v) => set('durationWeeks', v)} />
        </SimpleGrid>
        <SimpleGrid cols={{ base: 1, md: 2 }}>
          <Textarea label="Description" autosize minRows={2} maxLength={1000} value={draft.description}
            onChange={(e) => set('description', e.currentTarget.value)} />
          <Textarea label="Trainer notes (visible to the member)" autosize minRows={2} maxLength={2000} value={draft.trainerNotes}
            onChange={(e) => set('trainerNotes', e.currentTarget.value)} />
        </SimpleGrid>

        <Group justify="space-between">
          <Title order={5}>Exercises ({draft.exercises.length})</Title>
          <Button size="xs" variant="light" leftSection={<IconPlus size={14} />} onClick={addRow}
            disabled={draft.exercises.length >= 60}>
            Add exercise
          </Button>
        </Group>
        <Text size="xs" c="dimmed">Each exercise needs reps or a duration (seconds). Order within a day follows the row order.</Text>
        <ScrollArea.Autosize mah="50vh">
          <Table.ScrollContainer minWidth={1000}>
            <Table verticalSpacing={4} fz="sm">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th w={260}>Exercise</Table.Th>
                  <Table.Th w={90}>Day</Table.Th>
                  <Table.Th w={80}>Sets</Table.Th>
                  <Table.Th w={80}>Reps</Table.Th>
                  <Table.Th w={100}>Duration (s)</Table.Th>
                  <Table.Th w={90}>Load (kg)</Table.Th>
                  <Table.Th w={90}>Rest (s)</Table.Th>
                  <Table.Th>Notes</Table.Th>
                  <Table.Th w={40} />
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {draft.exercises.map((e, i) => (
                  <Table.Tr key={e.key}>
                    <Table.Td>
                      <Select aria-label={`Exercise for row ${i + 1}`} size="xs" searchable placeholder="Choose…"
                        data={exerciseOptions} value={e.exerciseId} onChange={(v) => setRow(e.key, { exerciseId: v })}
                        error={showErrors && !e.exerciseId} />
                    </Table.Td>
                    <Table.Td>
                      <Select aria-label={`Day for row ${i + 1}`} size="xs" data={DAY_OPTIONS} value={e.dayNumber}
                        allowDeselect={false} onChange={(v) => v && setRow(e.key, { dayNumber: v })} />
                    </Table.Td>
                    <Table.Td>
                      <NumberInput aria-label={`Sets for row ${i + 1}`} size="xs" min={1} max={20} allowDecimal={false}
                        value={e.sets} onChange={(v) => setRow(e.key, { sets: v })} />
                    </Table.Td>
                    <Table.Td>
                      <NumberInput aria-label={`Reps for row ${i + 1}`} size="xs" min={1} max={100} allowDecimal={false}
                        value={e.reps} onChange={(v) => setRow(e.key, { reps: v })}
                        error={showErrors && blank(e.reps) && blank(e.durationSeconds)} />
                    </Table.Td>
                    <Table.Td>
                      <NumberInput aria-label={`Duration in seconds for row ${i + 1}`} size="xs" min={1} max={7200}
                        allowDecimal={false} value={e.durationSeconds} onChange={(v) => setRow(e.key, { durationSeconds: v })}
                        error={showErrors && blank(e.reps) && blank(e.durationSeconds)} />
                    </Table.Td>
                    <Table.Td>
                      <NumberInput aria-label={`Load in kg for row ${i + 1}`} size="xs" min={0} max={500} decimalScale={1}
                        value={e.weightKg} onChange={(v) => setRow(e.key, { weightKg: v })} />
                    </Table.Td>
                    <Table.Td>
                      <NumberInput aria-label={`Rest in seconds for row ${i + 1}`} size="xs" min={0} max={600}
                        allowDecimal={false} value={e.restSeconds} onChange={(v) => setRow(e.key, { restSeconds: v })} />
                    </Table.Td>
                    <Table.Td>
                      <TextInput aria-label={`Notes for row ${i + 1}`} size="xs" maxLength={300} value={e.notes}
                        onChange={(ev) => setRow(e.key, { notes: ev.currentTarget.value })} />
                    </Table.Td>
                    <Table.Td>
                      <ActionIcon variant="subtle" color="red" aria-label={`Remove row ${i + 1}`} onClick={() => removeRow(e.key)}>
                        <IconTrash size={16} />
                      </ActionIcon>
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
          {draft.exercises.length === 0 && <Text c="dimmed" ta="center" py="md">No exercises — add one above.</Text>}
        </ScrollArea.Autosize>

        {showErrors && errors.length > 0 && (
          <Alert color="red" title="Please fix the following">
            <Stack gap={2}>{errors.slice(0, 8).map((m) => <Text key={m} size="sm">{m}</Text>)}</Stack>
            {errors.length > 8 && <Text size="sm">…and {errors.length - 8} more</Text>}
          </Alert>
        )}
        {save.error && <Alert color="red">{errorMessage(save.error)}</Alert>}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>Cancel</Button>
          <Button loading={save.isPending} onClick={submit}>Save plan</Button>
        </Group>
      </Stack>
    </Modal>
  );
}

function AssignTemplateModal({ memberId, opened, onClose, replacing }: {
  memberId: number;
  opened: boolean;
  onClose: () => void;
  replacing: boolean;
}) {
  const templates = useApiQuery<WorkoutPlan[]>(opened ? '/api/v1/workouts/templates' : null);
  const [templateId, setTemplateId] = useState<number | null>(null);
  const [startDate, setStartDate] = useState<Date | null>(null);
  const assign = useApiMutation<void, WorkoutPlan>({
    request: () => ({
      path: `/api/v1/workouts/templates/${templateId}/assign`,
      body: { memberId, startDate: startDate ? dayjs(startDate).format('YYYY-MM-DD') : undefined },
    }),
    invalidate: ['/api/v1/workouts', '/api/v1/dashboard'],
    successMessage: (p) => `Assigned "${p.name}"`,
    silentError: true,
    onSuccess: () => close(),
  });
  const close = () => {
    setTemplateId(null);
    setStartDate(null);
    assign.reset();
    onClose();
  };
  const list = templates.data ?? [];
  return (
    <Modal opened={opened} onClose={close} title="Assign a workout template" size="lg" centered>
      <Stack>
        {replacing && (
          <Alert color="orange" variant="light">The member's current active plan will be archived and replaced.</Alert>
        )}
        <QueryState isLoading={templates.isLoading} error={templates.error} empty={list.length === 0}
          emptyText="No templates yet. Create one on the Workouts page.">
          <ScrollArea.Autosize mah="45vh">
            <Stack gap="xs">
              {list.map((t) => {
                const selected = templateId === t.id;
                return (
                  <Card key={t.id} withBorder radius="md" p="sm" onClick={() => setTemplateId(t.id)}
                    role="radio" aria-checked={selected} tabIndex={0}
                    onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && setTemplateId(t.id)}
                    style={{ cursor: 'pointer', outline: selected ? '2px solid var(--mantine-color-teal-6)' : undefined }}>
                    <Group justify="space-between" wrap="nowrap">
                      <Text fw={600}>{t.name}</Text>
                      <Group gap={4}>
                        <Badge size="sm" variant="light">{label(t.goal)}</Badge>
                        <Badge size="sm" variant="light" color="indigo">{label(t.difficulty)}</Badge>
                      </Group>
                    </Group>
                    <Text size="xs" c="dimmed">
                      {t.durationWeeks} weeks · {new Set(t.exercises.map((e) => e.dayNumber)).size} days/week ·{' '}
                      {t.exercises.length} exercises
                    </Text>
                    {t.description && <Text size="sm" mt={4} lineClamp={2}>{t.description}</Text>}
                  </Card>
                );
              })}
            </Stack>
          </ScrollArea.Autosize>
        </QueryState>
        <DatePickerInput label="Start date" placeholder="Today" clearable value={startDate} onChange={setStartDate} />
        {assign.error && <Alert color="red">{errorMessage(assign.error)}</Alert>}
        <Group justify="flex-end">
          <Button variant="default" onClick={close}>Cancel</Button>
          <Button disabled={templateId == null} loading={assign.isPending} onClick={() => assign.mutate()}>Assign</Button>
        </Group>
      </Stack>
    </Modal>
  );
}

export default function TrainerClientPage() {
  const { memberId: param } = useParams();
  const memberId = Number(param);
  const valid = Number.isInteger(memberId) && memberId > 0;
  const { user } = useAuth();
  const [tab, setTab] = useState<string | null>('plan');
  const [editing, setEditing] = useState(false);
  const [assigning, setAssigning] = useState(false);
  const [booking, setBooking] = useState(false);

  const member = useApiQuery<Member>(valid ? `/api/v1/members/${memberId}` : null);
  const plan = useApiQuery<WorkoutPlan>(valid ? `/api/v1/workouts/members/${memberId}/active-plan` : null);
  // managers can open this page too: fall back to the plan's trainer
  const trainerId = user?.trainerId ?? plan.data?.trainerId ?? null;
  const clients = useApiQuery<TrainerAssignment[]>(trainerId ? `/api/v1/trainers/${trainerId}/clients` : null);
  const trainer = useApiQuery<Trainer>(trainerId ? `/api/v1/trainers/${trainerId}` : null);
  const from = dayjs().format('YYYY-MM-DD');
  const to = dayjs().add(60, 'day').format('YYYY-MM-DD');
  const schedule = useApiQuery<TrainingSession[]>(trainerId ? `/api/v1/trainers/${trainerId}/schedule` : null, { from, to });

  const assignment = clients.data?.find((c) => c.memberId === memberId && c.status === 'ACTIVE')
    ?? clients.data?.find((c) => c.memberId === memberId);
  const memberForbidden = member.error instanceof ApiError && member.error.status === 403;
  const name = member.data ? `${member.data.firstName} ${member.data.lastName}` : assignment?.memberName ?? `Member #${memberId}`;
  const code = member.data?.memberCode ?? assignment?.memberCode;
  const sessions = (schedule.data ?? []).filter((s) => s.memberId === memberId);
  const isClient = assignment?.status === 'ACTIVE';
  const noPlan = isNotFound(plan.error);

  if (!valid) {
    return (
      <>
        <PageHeader title="Client" />
        <Alert color="red">Invalid member id.</Alert>
      </>
    );
  }

  return (
    <>
      <Anchor component={Link} to="/trainer" size="sm" mb="xs" style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
        <IconArrowLeft size={14} /> My clients
      </Anchor>
      <PageHeader
        title={name}
        subtitle={[code, assignment?.goal ? `Goal: ${assignment.goal}` : null].filter(Boolean).join(' · ') || undefined}
        actions={
          <>
            {member.data && <StatusBadge value={member.data.membership.state} size="lg" />}
            {trainerId && isClient && (
              <Button leftSection={<IconCalendarPlus size={16} />} onClick={() => setBooking(true)}>Book session</Button>
            )}
          </>
        }
      />

      {member.error && !memberForbidden && <Alert color="red" mb="md">{errorMessage(member.error)}</Alert>}
      {clients.data && !assignment && (
        <Alert color="yellow" mb="md">This member is not one of your clients. Some actions may be refused.</Alert>
      )}

      <SimpleGrid cols={{ base: 1, sm: 2, md: 4 }} mb="lg">
        <Paper withBorder radius="md" p="md">
          <Text size="xs" c="dimmed" tt="uppercase" fw={600}>Membership</Text>
          {member.data ? (
            <>
              <Text fw={600} mt={4}>{member.data.membership.planName ?? 'None'}</Text>
              <Text size="xs" c="dimmed">
                {member.data.membership.endDate ? `Until ${date(member.data.membership.endDate)}` : label(member.data.membership.state)}
              </Text>
            </>
          ) : (
            <Text size="sm" c="dimmed" mt={4}>{memberForbidden ? 'Not visible to you' : '—'}</Text>
          )}
        </Paper>
        <Paper withBorder radius="md" p="md">
          <Text size="xs" c="dimmed" tt="uppercase" fw={600}>Contact</Text>
          {member.data ? (
            <>
              <Text size="sm" mt={4}>{member.data.phone}</Text>
              <Text size="xs" c="dimmed" truncate="end">{member.data.email}</Text>
            </>
          ) : <Text size="sm" c="dimmed" mt={4}>—</Text>}
        </Paper>
        <Paper withBorder radius="md" p="md">
          <Text size="xs" c="dimmed" tt="uppercase" fw={600}>Client since</Text>
          <Text fw={600} mt={4}>{assignment ? date(assignment.assignedAt) : '—'}</Text>
          <Text size="xs" c="dimmed">{member.data ? `Home: ${member.data.homeBranchName}` : ''}</Text>
        </Paper>
        <Paper withBorder radius="md" p="md">
          <Text size="xs" c="dimmed" tt="uppercase" fw={600}>Upcoming sessions</Text>
          <Text fw={600} mt={4}>{sessions.filter((s) => s.status === 'SCHEDULED').length}</Text>
          <Text size="xs" c="dimmed">next 60 days</Text>
        </Paper>
      </SimpleGrid>

      <Tabs value={tab} onChange={setTab}>
        <Tabs.List>
          <Tabs.Tab value="plan" leftSection={<IconBarbell size={16} />}>Workout plan</Tabs.Tab>
          <Tabs.Tab value="progress" leftSection={<IconChartLine size={16} />}>Progress</Tabs.Tab>
          <Tabs.Tab value="sessions" leftSection={<IconCalendarEvent size={16} />}>Sessions</Tabs.Tab>
        </Tabs.List>

        <Tabs.Panel value="plan" pt="md">
          {noPlan ? (
            <Card withBorder radius="md" p="xl">
              <Stack align="center" gap="xs">
                <ThemeIcon size={52} radius="xl" variant="light"><IconTemplate size={28} /></ThemeIcon>
                <Title order={4}>No active workout plan</Title>
                <Text c="dimmed" ta="center">Assign one of your templates to get {name.split(' ')[0]} started.</Text>
                <Button mt="xs" leftSection={<IconTemplate size={16} />} onClick={() => setAssigning(true)}>Assign template</Button>
              </Stack>
            </Card>
          ) : (
            <QueryState isLoading={plan.isLoading} error={plan.error}>
              {plan.data && (
                <Stack gap="lg">
                  <Group justify="space-between" wrap="wrap">
                    <div>
                      <Group gap="xs">
                        <Title order={3}>{plan.data.name}</Title>
                        <StatusBadge value={plan.data.status} />
                      </Group>
                      {plan.data.sourceTemplateId && (
                        <Text size="xs" c="dimmed">Based on template #{plan.data.sourceTemplateId}</Text>
                      )}
                    </div>
                    <Group gap="xs">
                      <Button variant="default" leftSection={<IconTemplate size={16} />} onClick={() => setAssigning(true)}>
                        Assign template
                      </Button>
                      <Button leftSection={<IconEdit size={16} />} onClick={() => setEditing(true)}>Edit plan</Button>
                    </Group>
                  </Group>
                  <PlanOverview plan={plan.data} notesTitle="Trainer notes (visible to the member)" />
                  <PlanDays exercises={plan.data.exercises} />
                </Stack>
              )}
            </QueryState>
          )}
        </Tabs.Panel>

        <Tabs.Panel value="progress" pt="md">
          {tab === 'progress' && <ProgressSection memberId={memberId} title="Body weight" />}
        </Tabs.Panel>

        <Tabs.Panel value="sessions" pt="md">
          <Grid gutter="md">
            <Grid.Col span={{ base: 12, md: 8 }}>
              <Paper withBorder radius="md" p="md">
                <Group justify="space-between" mb="sm">
                  <Title order={4}>Sessions (next 60 days)</Title>
                  {trainerId && isClient && (
                    <Button size="xs" leftSection={<IconCalendarPlus size={14} />} onClick={() => setBooking(true)}>Book</Button>
                  )}
                </Group>
                <QueryState isLoading={schedule.isLoading} error={schedule.error} empty={sessions.length === 0}
                  emptyText="No sessions booked with this client.">
                  <Table.ScrollContainer minWidth={520}>
                    <Table verticalSpacing="xs" fz="sm">
                      <Table.Thead>
                        <Table.Tr>
                          <Table.Th>When</Table.Th>
                          <Table.Th>Notes</Table.Th>
                          <Table.Th>Status</Table.Th>
                          <Table.Th ta="right">Actions</Table.Th>
                        </Table.Tr>
                      </Table.Thead>
                      <Table.Tbody>
                        {sessions.map((s) => (
                          <Table.Tr key={s.id}>
                            <Table.Td>
                              <Text size="sm" fw={500}>{dayjs(s.startsAt).format('ddd DD MMM')}</Text>
                              <Text size="xs" c="dimmed">{dayjs(s.startsAt).format('HH:mm')}–{dayjs(s.endsAt).format('HH:mm')}</Text>
                            </Table.Td>
                            <Table.Td><Text size="sm" lineClamp={2}>{s.notes ?? '—'}</Text></Table.Td>
                            <Table.Td><StatusBadge value={s.status} /></Table.Td>
                            <Table.Td>
                              <Group justify="flex-end">
                                {trainerId && <SessionActions trainerId={trainerId} session={s} />}
                              </Group>
                            </Table.Td>
                          </Table.Tr>
                        ))}
                      </Table.Tbody>
                    </Table>
                  </Table.ScrollContainer>
                </QueryState>
              </Paper>
            </Grid.Col>
            <Grid.Col span={{ base: 12, md: 4 }}>
              {plan.data && (
                <Paper withBorder radius="md" p="md">
                  <Title order={5} mb="xs">Plan at a glance</Title>
                  <Stack gap={4}>
                    {plan.data.exercises
                      .slice()
                      .sort((a, b) => a.dayNumber - b.dayNumber || a.position - b.position)
                      .slice(0, 12)
                      .map((e, i) => (
                        <Group key={i} justify="space-between" wrap="nowrap">
                          <Tooltip label={e.muscleGroup}>
                            <Text size="xs" truncate="end">{WEEKDAYS[e.dayNumber - 1]} · {e.exerciseName}</Text>
                          </Tooltip>
                          <Text size="xs" c="dimmed">{prescription(e)}</Text>
                        </Group>
                      ))}
                  </Stack>
                </Paper>
              )}
            </Grid.Col>
          </Grid>
        </Tabs.Panel>
      </Tabs>

      {plan.data && editing && (
        <EditPlanModal key={plan.data.id + ':' + plan.data.version} plan={plan.data} opened={editing} onClose={() => setEditing(false)} />
      )}
      <AssignTemplateModal memberId={memberId} opened={assigning} onClose={() => setAssigning(false)} replacing={!!plan.data} />
      {trainerId && (
        <BookSessionModal
          trainerId={trainerId}
          opened={booking}
          onClose={() => setBooking(false)}
          memberId={memberId}
          availability={trainer.data?.availability}
        />
      )}
    </>
  );
}
