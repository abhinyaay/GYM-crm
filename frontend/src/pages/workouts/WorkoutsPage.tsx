import {
  ActionIcon, Alert, Badge, Button, Card, Divider, Drawer, Group, Modal, NumberInput, Paper, SegmentedControl, Select,
  SimpleGrid, Stack, Table, Tabs, Text, Textarea, TextInput, Title, Tooltip,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { useDebouncedValue, useMediaQuery } from '@mantine/hooks';
import {
  IconArrowDown, IconArrowUp, IconBarbell, IconCopy, IconEdit, IconEye, IconPlus, IconSearch, IconTemplate, IconTrash,
} from '@tabler/icons-react';
import { useMemo, useState } from 'react';
import type { Difficulty, Exercise, Page, PlanExercise, Trainer, WorkoutGoal, WorkoutPlan } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { hasAnyRole, MANAGERS } from '@/auth/roles';
import { PageHeader } from '@/components/PageHeader';
import { Pager } from '@/components/Pager';
import { QueryState } from '@/components/QueryState';
import { StatusBadge } from '@/components/StatusBadge';
import { label } from '@/lib/format';
import { errorMessage } from '@/lib/notify';
import { useApiMutation, useApiQuery } from '@/lib/useApi';

const GOALS: WorkoutGoal[] = ['FAT_LOSS', 'MUSCLE_GAIN', 'STRENGTH', 'ENDURANCE', 'GENERAL_FITNESS', 'REHABILITATION'];
const DIFFICULTIES: Difficulty[] = ['BEGINNER', 'INTERMEDIATE', 'ADVANCED'];
const DEFAULT_GROUPS = ['ARMS', 'BACK', 'CARDIO', 'CHEST', 'CORE', 'FULL_BODY', 'GLUTES', 'HAMSTRINGS', 'LEGS', 'SHOULDERS'];
const DIFFICULTY_COLOR: Record<Difficulty, string> = { BEGINNER: 'teal', INTERMEDIATE: 'yellow', ADVANCED: 'red' };
const opts = (values: string[]) => values.map((v) => ({ value: v, label: label(v) }));

/** Whole exercise catalogue (≤100 rows) for pickers and the muscle-group filter. */
function useCatalogue() {
  const q = useApiQuery<Page<Exercise>>('/api/v1/workouts/exercises', { size: 100 });
  const exercises = q.data?.content ?? [];
  const groups = [...new Set([...DEFAULT_GROUPS, ...exercises.map((e) => e.muscleGroup)])].sort();
  return { exercises, groups };
}

export default function WorkoutsPage() {
  const [tab, setTab] = useState<string | null>('templates');
  return (
    <>
      <PageHeader title="Workouts" subtitle="Exercise catalogue and reusable workout templates" />
      <Tabs value={tab} onChange={setTab} keepMounted={false}>
        <Tabs.List mb="md">
          <Tabs.Tab value="templates" leftSection={<IconTemplate size={16} />}>Templates</Tabs.Tab>
          <Tabs.Tab value="exercises" leftSection={<IconBarbell size={16} />}>Exercises</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="templates"><TemplatesTab /></Tabs.Panel>
        <Tabs.Panel value="exercises"><ExercisesTab /></Tabs.Panel>
      </Tabs>
    </>
  );
}

// ------------------------------------------------------------------ exercises

function ExercisesTab() {
  const { groups } = useCatalogue();
  const [q, setQ] = useState('');
  const [debounced] = useDebouncedValue(q.trim(), 300);
  const [muscleGroup, setMuscleGroup] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [creating, setCreating] = useState(false);
  const query = useApiQuery<Page<Exercise>>('/api/v1/workouts/exercises', {
    q: debounced || undefined, muscleGroup: muscleGroup ?? undefined, page, size: 25,
  });
  const rows = query.data?.content ?? [];

  return (
    <>
      <Group mb="md" justify="space-between" align="flex-end" wrap="wrap">
        <Group align="flex-end" wrap="wrap">
          <TextInput label="Search" placeholder="Exercise name" leftSection={<IconSearch size={14} />} value={q}
            onChange={(e) => { setQ(e.currentTarget.value); setPage(0); }} w={240} />
          <Select label="Muscle group" placeholder="All groups" clearable searchable data={opts(groups)} value={muscleGroup}
            onChange={(v) => { setMuscleGroup(v); setPage(0); }} w={200} />
        </Group>
        <Button leftSection={<IconPlus size={16} />} onClick={() => setCreating(true)}>New exercise</Button>
      </Group>
      <QueryState isLoading={query.isLoading} error={query.error} empty={rows.length === 0} emptyText="No exercises found.">
        <Table.ScrollContainer minWidth={640}>
          <Table striped highlightOnHover verticalSpacing="sm">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Exercise</Table.Th>
                <Table.Th>Muscle group</Table.Th>
                <Table.Th>Equipment</Table.Th>
                <Table.Th>Description</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((e) => (
                <Table.Tr key={e.id}>
                  <Table.Td><Text size="sm" fw={600}>{e.name}</Text></Table.Td>
                  <Table.Td><Badge variant="light" color="indigo" size="sm">{label(e.muscleGroup)}</Badge></Table.Td>
                  <Table.Td><Text size="sm">{e.equipment ?? '—'}</Text></Table.Td>
                  <Table.Td><Text size="sm" c="dimmed" lineClamp={2}>{e.description ?? '—'}</Text></Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
        <Pager page={query.data} onChange={setPage} />
      </QueryState>
      <Modal opened={creating} onClose={() => setCreating(false)} title="New exercise" centered>
        {creating && <ExerciseForm groups={groups} onDone={() => setCreating(false)} />}
      </Modal>
    </>
  );
}

function ExerciseForm({ groups, onDone }: { groups: string[]; onDone: () => void }) {
  const [groupOptions, setGroupOptions] = useState(groups);
  const form = useForm({
    initialValues: { name: '', muscleGroup: '' as string, equipment: '', description: '' },
    validate: {
      name: (v) => (!v.trim() ? 'Required' : v.length > 100 ? 'Max 100 characters' : null),
      muscleGroup: (v) => (/^[A-Za-z_]{3,30}$/.test(v) ? null : '3-30 letters or underscores, e.g. LEGS'),
      equipment: (v) => (v.length > 60 ? 'Max 60 characters' : null),
      description: (v) => (v.length > 1000 ? 'Max 1000 characters' : null),
    },
  });
  const create = useApiMutation<typeof form.values, Exercise>({
    request: (v) => ({
      path: '/api/v1/workouts/exercises',
      body: {
        name: v.name.trim(),
        muscleGroup: v.muscleGroup.trim().toUpperCase(),
        equipment: v.equipment.trim() || null,
        description: v.description.trim() || null,
      },
    }),
    invalidate: ['/api/v1/workouts/exercises'],
    successMessage: (e) => `${e.name} added to the catalogue`,
    silentError: true,
    onSuccess: onDone,
  });
  return (
    <form onSubmit={form.onSubmit((v) => create.mutate(v))}>
      <Stack>
        <TextInput label="Name" placeholder="Romanian Deadlift" withAsterisk {...form.getInputProps('name')} />
        <Select
          label="Muscle group"
          description="Pick one or type a new group (letters and underscores)"
          withAsterisk
          searchable
          data={opts(groupOptions)}
          nothingFoundMessage="Press Enter to add as a new group"
          onKeyDown={(e) => {
            const input = e.currentTarget.value.trim().toUpperCase().replace(/\s+/g, '_');
            if (e.key === 'Enter' && input && !groupOptions.includes(input)) {
              e.preventDefault();
              setGroupOptions((g) => [...g, input].sort());
              form.setFieldValue('muscleGroup', input);
            }
          }}
          {...form.getInputProps('muscleGroup')}
        />
        <TextInput label="Equipment" placeholder="Barbell, dumbbells, none…" {...form.getInputProps('equipment')} />
        <Textarea label="Coaching notes / description" autosize minRows={2} {...form.getInputProps('description')} />
        {create.error && <Alert color="red" style={{ whiteSpace: 'pre-line' }}>{errorMessage(create.error)}</Alert>}
        <Group justify="flex-end">
          <Button variant="default" onClick={onDone}>Cancel</Button>
          <Button type="submit" loading={create.isPending}>Add exercise</Button>
        </Group>
      </Stack>
    </form>
  );
}

// ------------------------------------------------------------------ templates

function TemplatesTab() {
  const { user } = useAuth();
  const isManager = hasAnyRole(user, MANAGERS);
  // creating a template requires a trainer profile (POST /templates is TRAINER-only)
  const canCreate = !!user?.roles.includes('TRAINER') && user.trainerId != null;
  const templates = useApiQuery<WorkoutPlan[]>('/api/v1/workouts/templates');
  const trainers = useApiQuery<Page<Trainer>>('/api/v1/trainers', { size: 100 });
  const trainerNames = Object.fromEntries((trainers.data?.content ?? []).map((t) => [t.id, t.fullName]));
  const [goal, setGoal] = useState<string | null>(null);
  const [difficulty, setDifficulty] = useState<string | null>(null);
  const [editing, setEditing] = useState<WorkoutPlan | 'new' | null>(null);
  const [viewing, setViewing] = useState<WorkoutPlan | null>(null);
  const fullScreen = useMediaQuery('(max-width: 48em)');

  const canEdit = (p: WorkoutPlan) => isManager || (user?.trainerId != null && user.trainerId === p.trainerId);
  const rows = (templates.data ?? []).filter((t) => (!goal || t.goal === goal) && (!difficulty || t.difficulty === difficulty));

  return (
    <>
      <Group mb="md" justify="space-between" align="flex-end" wrap="wrap">
        <Group align="flex-end" wrap="wrap">
          <Select label="Goal" placeholder="Any goal" clearable data={opts(GOALS)} value={goal} onChange={setGoal} w={190} />
          <Select label="Difficulty" placeholder="Any level" clearable data={opts(DIFFICULTIES)} value={difficulty}
            onChange={setDifficulty} w={170} />
        </Group>
        {canCreate ? (
          <Button leftSection={<IconPlus size={16} />} onClick={() => setEditing('new')}>New template</Button>
        ) : (
          <Tooltip label="Templates are authored by trainers (a trainer profile is required)">
            <Button leftSection={<IconPlus size={16} />} disabled>New template</Button>
          </Tooltip>
        )}
      </Group>

      <QueryState isLoading={templates.isLoading} error={templates.error} empty={rows.length === 0} emptyText="No workout templates yet.">
        <SimpleGrid cols={{ base: 1, sm: 2, xl: 3 }}>
          {rows.map((t) => {
            const days = new Set(t.exercises.map((e) => e.dayNumber)).size;
            return (
              <Card key={t.id} withBorder padding="lg" style={{ display: 'flex', flexDirection: 'column' }}>
                <Group justify="space-between" align="flex-start" wrap="nowrap">
                  <Title order={5}>{t.name}</Title>
                  <StatusBadge value={t.status} />
                </Group>
                <Group gap={6} mt="xs">
                  <Badge variant="light" color="blue">{label(t.goal)}</Badge>
                  <Badge variant="light" color={DIFFICULTY_COLOR[t.difficulty]}>{label(t.difficulty)}</Badge>
                  <Badge variant="outline" color="gray">{t.durationWeeks} weeks</Badge>
                </Group>
                {t.description && <Text size="sm" mt="sm" lineClamp={2}>{t.description}</Text>}
                <Text size="sm" c="dimmed" mt="sm" style={{ flex: 1 }}>
                  {t.exercises.length} exercises · {days} day{days === 1 ? '' : 's'} / week
                  <br />
                  By {t.trainerId === user?.trainerId ? 'you' : trainerNames[t.trainerId] ?? `trainer #${t.trainerId}`}
                </Text>
                <Group gap="xs" mt="md">
                  <Button size="xs" variant="light" leftSection={<IconEye size={14} />} onClick={() => setViewing(t)}>View</Button>
                  {canEdit(t) && (
                    <Button size="xs" variant="subtle" leftSection={<IconEdit size={14} />} onClick={() => setEditing(t)}>Edit</Button>
                  )}
                </Group>
              </Card>
            );
          })}
        </SimpleGrid>
      </QueryState>

      <Drawer opened={viewing !== null} onClose={() => setViewing(null)} position="right" size="lg" title={viewing?.name}>
        {viewing && <TemplateView plan={viewing} />}
      </Drawer>

      <Modal
        opened={editing !== null}
        onClose={() => setEditing(null)}
        title={editing === 'new' ? 'New workout template' : `Edit ${editing?.name ?? ''}`}
        size={1100}
        fullScreen={fullScreen}
      >
        {editing !== null && (
          <TemplateForm key={editing === 'new' ? 'new' : editing.id} plan={editing === 'new' ? null : editing}
            onDone={() => setEditing(null)} />
        )}
      </Modal>
    </>
  );
}

function TemplateView({ plan }: { plan: WorkoutPlan }) {
  const byDay = useMemo(() => {
    const map = new Map<number, PlanExercise[]>();
    [...plan.exercises].sort((a, b) => a.dayNumber - b.dayNumber || a.position - b.position)
      .forEach((e) => map.set(e.dayNumber, [...(map.get(e.dayNumber) ?? []), e]));
    return [...map.entries()];
  }, [plan.exercises]);
  return (
    <Stack>
      <Group gap={6}>
        <Badge variant="light" color="blue">{label(plan.goal)}</Badge>
        <Badge variant="light" color={DIFFICULTY_COLOR[plan.difficulty]}>{label(plan.difficulty)}</Badge>
        <Badge variant="outline" color="gray">{plan.durationWeeks} weeks</Badge>
      </Group>
      {plan.description && <Text size="sm">{plan.description}</Text>}
      {plan.trainerNotes && <Alert color="gray" variant="light" title="Trainer notes">{plan.trainerNotes}</Alert>}
      {byDay.map(([day, items]) => (
        <div key={day}>
          <Text fw={600} mb={4}>Day {day}</Text>
          <Table withTableBorder verticalSpacing={4}>
            <Table.Tbody>
              {items.map((e) => (
                <Table.Tr key={`${e.dayNumber}-${e.position}`}>
                  <Table.Td>
                    <Text size="sm" fw={500}>{e.exerciseName}</Text>
                    <Text size="xs" c="dimmed">{label(e.muscleGroup)}{e.notes ? ` · ${e.notes}` : ''}</Text>
                  </Table.Td>
                  <Table.Td ta="right">
                    <Text size="sm">
                      {e.sets} × {e.reps != null ? `${e.reps} reps` : `${e.durationSeconds}s`}
                      {e.weightKg != null ? ` @ ${e.weightKg} kg` : ''}
                    </Text>
                    {e.restSeconds != null && <Text size="xs" c="dimmed">rest {e.restSeconds}s</Text>}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </div>
      ))}
    </Stack>
  );
}

// ------------------------------------------------------------------ exercise-list editor

interface ExerciseRow {
  key: number;
  exerciseId: string | null;
  dayNumber: number;
  sets: number | string;
  mode: 'reps' | 'time';
  reps: number | string;
  durationSeconds: number | string;
  weightKg: number | string;
  restSeconds: number | string;
  notes: string;
}

let rowSeq = 0;
const newRow = (dayNumber: number): ExerciseRow => ({
  key: ++rowSeq, exerciseId: null, dayNumber, sets: 3, mode: 'reps', reps: 10, durationSeconds: 60, weightKg: '', restSeconds: 60, notes: '',
});
const fromPlanExercise = (e: PlanExercise): ExerciseRow => ({
  key: ++rowSeq,
  exerciseId: String(e.exerciseId),
  dayNumber: e.dayNumber,
  sets: e.sets,
  mode: e.reps != null ? 'reps' : 'time',
  reps: e.reps ?? 10,
  durationSeconds: e.durationSeconds ?? 60,
  weightKg: e.weightKg ?? '',
  restSeconds: e.restSeconds ?? '',
  notes: e.notes ?? '',
});

const intIn = (v: number | string, min: number, max: number) =>
  v !== '' && Number.isInteger(Number(v)) && Number(v) >= min && Number(v) <= max;

/** Per-row validation mirroring PlanExerciseRequest. */
function validateRows(rows: ExerciseRow[]): Record<number, string> {
  const errors: Record<number, string> = {};
  rows.forEach((r) => {
    const e: string[] = [];
    if (!r.exerciseId) e.push('pick an exercise');
    if (!intIn(r.sets, 1, 20)) e.push('sets 1-20');
    if (r.mode === 'reps' && !intIn(r.reps, 1, 100)) e.push('reps 1-100');
    if (r.mode === 'time' && !intIn(r.durationSeconds, 1, 7200)) e.push('duration 1-7200 s');
    if (r.weightKg !== '' && (Number(r.weightKg) < 0 || Number(r.weightKg) > 500)) e.push('weight 0-500 kg');
    if (r.restSeconds !== '' && !intIn(r.restSeconds, 0, 600)) e.push('rest 0-600 s');
    if (r.notes.length > 300) e.push('notes max 300 chars');
    if (e.length) errors[r.key] = e.join(', ');
  });
  return errors;
}

/**
 * Editable list of exercises grouped by training day. Row order inside a day becomes the exercise
 * position on the server.
 */
function ExerciseListEditor({ rows, onChange, exercises, errors }: {
  rows: ExerciseRow[];
  onChange: (rows: ExerciseRow[]) => void;
  exercises: Exercise[];
  errors: Record<number, string>;
}) {
  const exerciseOptions = useMemo(() => {
    const groups = new Map<string, { value: string; label: string }[]>();
    exercises.forEach((e) => groups.set(e.muscleGroup, [...(groups.get(e.muscleGroup) ?? []), { value: String(e.id), label: e.name }]));
    return [...groups.entries()].sort().map(([group, items]) => ({ group: label(group), items }));
  }, [exercises]);
  const days = [...new Set(rows.map((r) => r.dayNumber))].sort((a, b) => a - b);
  const nextDay = [1, 2, 3, 4, 5, 6, 7].find((d) => !days.includes(d));
  const update = (key: number, patch: Partial<ExerciseRow>) => onChange(rows.map((r) => (r.key === key ? { ...r, ...patch } : r)));
  const move = (key: number, dir: -1 | 1) => {
    const row = rows.find((r) => r.key === key);
    if (!row) return;
    const sameDay = rows.filter((r) => r.dayNumber === row.dayNumber);
    const idx = sameDay.findIndex((r) => r.key === key);
    const swap = sameDay[idx + dir];
    if (!swap) return;
    const i = rows.indexOf(row);
    const j = rows.indexOf(swap);
    const next = [...rows];
    [next[i], next[j]] = [next[j], next[i]];
    onChange(next);
  };
  const full = rows.length >= 60;

  return (
    <Stack gap="sm">
      {days.length === 0 && <Text size="sm" c="dimmed">No exercises yet. Add a training day to start.</Text>}
      {days.map((day) => {
        const dayRows = rows.filter((r) => r.dayNumber === day);
        return (
          <Paper key={day} withBorder p="sm" radius="md">
            <Group justify="space-between" mb="xs">
              <Text fw={700}>Day {day}</Text>
              <Group gap={4}>
                <Tooltip label="Duplicate this day">
                  <ActionIcon variant="subtle" disabled={full || nextDay === undefined} aria-label="Duplicate day"
                    onClick={() => nextDay && onChange([...rows, ...dayRows.map((r) => ({ ...r, key: ++rowSeq, dayNumber: nextDay }))])}>
                    <IconCopy size={16} />
                  </ActionIcon>
                </Tooltip>
                <Button size="compact-xs" variant="light" leftSection={<IconPlus size={12} />} disabled={full}
                  onClick={() => onChange([...rows, newRow(day)])}>
                  Exercise
                </Button>
              </Group>
            </Group>
            <Stack gap="xs">
              {dayRows.map((r, idx) => (
                <div key={r.key}>
                  <Group gap="xs" align="flex-end" wrap="wrap">
                    <Select size="xs" label={idx === 0 ? 'Exercise' : undefined} placeholder="Pick exercise" searchable
                      data={exerciseOptions} value={r.exerciseId} onChange={(v) => update(r.key, { exerciseId: v })}
                      w={{ base: '100%', sm: 220 }} error={!r.exerciseId && !!errors[r.key]} />
                    <Select size="xs" label={idx === 0 ? 'Day' : undefined} w={72} allowDeselect={false}
                      data={[1, 2, 3, 4, 5, 6, 7].map((d) => ({ value: String(d), label: String(d) }))}
                      value={String(r.dayNumber)} onChange={(v) => v && update(r.key, { dayNumber: Number(v) })} />
                    <NumberInput size="xs" label={idx === 0 ? 'Sets' : undefined} w={64} min={1} max={20} allowDecimal={false}
                      value={r.sets} onChange={(v) => update(r.key, { sets: v })} />
                    <SegmentedControl size="xs" value={r.mode} onChange={(v) => update(r.key, { mode: v as 'reps' | 'time' })}
                      data={[{ value: 'reps', label: 'Reps' }, { value: 'time', label: 'Time' }]} />
                    {r.mode === 'reps' ? (
                      <NumberInput size="xs" label={idx === 0 ? 'Reps' : undefined} w={70} min={1} max={100} allowDecimal={false}
                        value={r.reps} onChange={(v) => update(r.key, { reps: v })} />
                    ) : (
                      <NumberInput size="xs" label={idx === 0 ? 'Seconds' : undefined} w={84} min={1} max={7200} allowDecimal={false}
                        value={r.durationSeconds} onChange={(v) => update(r.key, { durationSeconds: v })} />
                    )}
                    <NumberInput size="xs" label={idx === 0 ? 'Kg' : undefined} placeholder="—" w={70} min={0} max={500} decimalScale={2}
                      value={r.weightKg} onChange={(v) => update(r.key, { weightKg: v })} />
                    <NumberInput size="xs" label={idx === 0 ? 'Rest s' : undefined} placeholder="—" w={70} min={0} max={600}
                      allowDecimal={false} value={r.restSeconds} onChange={(v) => update(r.key, { restSeconds: v })} />
                    <TextInput size="xs" label={idx === 0 ? 'Notes' : undefined} placeholder="Cue, tempo…" maxLength={300}
                      style={{ flex: 1, minWidth: 120 }} value={r.notes} onChange={(e) => update(r.key, { notes: e.currentTarget.value })} />
                    <Group gap={0} wrap="nowrap">
                      <ActionIcon size="sm" variant="subtle" color="gray" aria-label="Move up" disabled={idx === 0}
                        onClick={() => move(r.key, -1)}><IconArrowUp size={14} /></ActionIcon>
                      <ActionIcon size="sm" variant="subtle" color="gray" aria-label="Move down" disabled={idx === dayRows.length - 1}
                        onClick={() => move(r.key, 1)}><IconArrowDown size={14} /></ActionIcon>
                      <ActionIcon size="sm" variant="subtle" color="red" aria-label="Remove exercise"
                        onClick={() => onChange(rows.filter((x) => x.key !== r.key))}><IconTrash size={14} /></ActionIcon>
                    </Group>
                  </Group>
                  {errors[r.key] && <Text size="xs" c="red" mt={2}>Fix: {errors[r.key]}</Text>}
                </div>
              ))}
            </Stack>
          </Paper>
        );
      })}
      <Group justify="space-between">
        <Button variant="default" size="xs" leftSection={<IconPlus size={14} />} disabled={full || nextDay === undefined}
          onClick={() => nextDay && onChange([...rows, newRow(nextDay)])}>
          Add training day
        </Button>
        <Text size="xs" c={full ? 'red' : 'dimmed'}>{rows.length} / 60 exercises</Text>
      </Group>
    </Stack>
  );
}

// ------------------------------------------------------------------ template form

interface TemplateValues {
  name: string;
  description: string;
  goal: WorkoutGoal;
  difficulty: Difficulty;
  durationWeeks: number | string;
  trainerNotes: string;
}

function TemplateForm({ plan, onDone }: { plan: WorkoutPlan | null; onDone: () => void }) {
  const { exercises } = useCatalogue();
  const [rows, setRows] = useState<ExerciseRow[]>(() => (plan ? plan.exercises.map(fromPlanExercise) : [newRow(1)]));
  const [showRowErrors, setShowRowErrors] = useState(false);
  const form = useForm<TemplateValues>({
    initialValues: {
      name: plan?.name ?? '',
      description: plan?.description ?? '',
      goal: plan?.goal ?? 'GENERAL_FITNESS',
      difficulty: plan?.difficulty ?? 'BEGINNER',
      durationWeeks: plan?.durationWeeks ?? 8,
      trainerNotes: plan?.trainerNotes ?? '',
    },
    validate: {
      name: (v) => (!v.trim() ? 'Required' : v.length > 120 ? 'Max 120 characters' : null),
      description: (v) => (v.length > 1000 ? 'Max 1000 characters' : null),
      durationWeeks: (v) => (intIn(v, 1, 52) ? null : '1-52 weeks'),
      trainerNotes: (v) => (v.length > 2000 ? 'Max 2000 characters' : null),
    },
  });
  const rowErrors = validateRows(rows);
  const save = useApiMutation<TemplateValues, WorkoutPlan>({
    request: (v) => ({
      path: plan ? `/api/v1/workouts/plans/${plan.id}` : '/api/v1/workouts/templates',
      method: plan ? 'PUT' : 'POST',
      body: {
        name: v.name.trim(),
        description: v.description.trim() || null,
        goal: v.goal,
        difficulty: v.difficulty,
        durationWeeks: Number(v.durationWeeks),
        trainerNotes: v.trainerNotes.trim() || null,
        // stable sort keeps the in-day order chosen in the editor
        exercises: [...rows].sort((a, b) => a.dayNumber - b.dayNumber).map((r) => ({
          exerciseId: Number(r.exerciseId),
          dayNumber: r.dayNumber,
          sets: Number(r.sets),
          reps: r.mode === 'reps' ? Number(r.reps) : null,
          durationSeconds: r.mode === 'time' ? Number(r.durationSeconds) : null,
          weightKg: r.weightKg === '' ? null : Number(r.weightKg),
          restSeconds: r.restSeconds === '' ? null : Number(r.restSeconds),
          notes: r.notes.trim() || null,
        })),
      },
    }),
    invalidate: ['/api/v1/workouts'],
    successMessage: (p) => (plan ? `${p.name} saved` : `Template ${p.name} created`),
    silentError: true,
    onSuccess: onDone,
  });

  const submit = form.onSubmit((v) => {
    setShowRowErrors(true);
    if (Object.keys(rowErrors).length === 0) save.mutate(v);
  });

  return (
    <form onSubmit={submit}>
      <Stack>
        <TextInput label="Name" withAsterisk {...form.getInputProps('name')} />
        <SimpleGrid cols={{ base: 1, sm: 3 }}>
          <NumberInput label="Duration (weeks)" min={1} max={52} allowDecimal={false} withAsterisk {...form.getInputProps('durationWeeks')} />
          <Select label="Difficulty" data={opts(DIFFICULTIES)} allowDeselect={false} {...form.getInputProps('difficulty')} />
          <Select label="Goal" data={opts(GOALS)} allowDeselect={false} {...form.getInputProps('goal')} />
        </SimpleGrid>
        <Textarea label="Description" autosize minRows={2} {...form.getInputProps('description')} />
        <Textarea label="Trainer notes" autosize minRows={2}
          {...form.getInputProps('trainerNotes')} />
        <Divider label="Exercises" labelPosition="left" />
        <ExerciseListEditor rows={rows} onChange={setRows} exercises={exercises} errors={showRowErrors ? rowErrors : {}} />
        {save.error && <Alert color="red" style={{ whiteSpace: 'pre-line' }}>{errorMessage(save.error)}</Alert>}
        <Group justify="flex-end">
          <Button variant="default" onClick={onDone}>Cancel</Button>
          <Button type="submit" loading={save.isPending}>{plan ? 'Save template' : 'Create template'}</Button>
        </Group>
      </Stack>
    </form>
  );
}
