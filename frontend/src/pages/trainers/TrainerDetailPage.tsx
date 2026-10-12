import {
  ActionIcon, Alert, Anchor, Avatar, Badge, Button, Card, Center, Divider, Grid, Group, Loader, Modal, NumberInput, Paper,
  Select, SimpleGrid, Stack, Table, Tabs, TagsInput, Text, Textarea, TextInput, Title, Tooltip,
} from '@mantine/core';
import { TimeInput } from '@mantine/dates';
import { useForm } from '@mantine/form';
import {
  IconArrowLeft, IconCalendarEvent, IconCalendarTime, IconCheck, IconClock, IconEdit, IconPlus, IconTrash, IconUserMinus,
  IconUserPlus, IconUsers, IconX,
} from '@tabler/icons-react';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import type {
  AvailabilitySlot, Branch, Page, Trainer, TrainerAssignment, TrainerPerformance, TrainerStatus, TrainingSession,
} from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { hasAnyRole, MANAGERS } from '@/auth/roles';
import { BranchSelect, useBranchNames } from '@/components/BranchSelect';
import { MemberSearch } from '@/components/MemberSearch';
import { PageHeader } from '@/components/PageHeader';
import { QueryState } from '@/components/QueryState';
import { StatCard } from '@/components/StatCard';
import { StatusBadge } from '@/components/StatusBadge';
import { date, label, money, pct, time, WEEKDAYS } from '@/lib/format';
import { errorMessage } from '@/lib/notify';
import { useApiMutation, useApiQuery } from '@/lib/useApi';

const STATUSES: TrainerStatus[] = ['ACTIVE', 'ON_LEAVE', 'INACTIVE'];
const SPECIALIZATION_SUGGESTIONS = [
  'STRENGTH', 'WEIGHT_LOSS', 'HIIT', 'YOGA', 'PILATES', 'CROSSFIT', 'BODYBUILDING', 'REHABILITATION', 'NUTRITION',
  'CARDIO', 'MOBILITY', 'SENIOR_FITNESS',
];
const initials = (name: string) => name.split(/\s+/).filter(Boolean).slice(0, 2).map((p) => p[0]).join('').toUpperCase();

export default function TrainerDetailPage() {
  const { id } = useParams();
  const { user } = useAuth();
  const canManage = hasAnyRole(user, MANAGERS);
  const branchNames = useBranchNames();
  const trainerQuery = useApiQuery<Trainer>(id ? `/api/v1/trainers/${id}` : null);
  const performance = useApiQuery<TrainerPerformance>(id ? `/api/v1/trainers/${id}/performance` : null);
  const [editing, setEditing] = useState(false);
  const [tab, setTab] = useState<string | null>('clients');
  const t = trainerQuery.data;

  if (trainerQuery.isLoading || trainerQuery.error || !t) {
    return (
      <>
        <BackLink />
        <QueryState isLoading={trainerQuery.isLoading} error={trainerQuery.error}>
          <Center py="xl"><Loader size="sm" /></Center>
        </QueryState>
      </>
    );
  }

  const perf = performance.data;
  const sessionsTotal = perf ? Object.values(perf.sessionsByStatus).reduce((a, b) => a + b, 0) : null;

  return (
    <>
      <BackLink />
      <PageHeader
        title={
          <Group gap="sm">
            <Avatar color="teal" radius="xl" size="lg">{initials(t.fullName)}</Avatar>
            <span>{t.fullName}</span>
            <StatusBadge value={t.status} size="lg" />
          </Group>
        }
        subtitle={`${t.email} · ${t.employeeCode} · ${branchNames[t.homeBranchId] ?? `Branch #${t.homeBranchId}`}`}
        actions={canManage && (
          <Button variant="light" leftSection={<IconEdit size={16} />} onClick={() => setEditing(true)}>Edit profile</Button>
        )}
      />

      <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} mb="lg">
        <StatCard label="Active clients" value={`${perf?.activeClients ?? '—'} / ${t.maxActiveClients}`} icon={<IconUsers size={20} />}
          hint="capacity" />
        <StatCard label="Sessions (30 days)" value={sessionsTotal ?? '—'} icon={<IconCalendarEvent size={20} />} color="blue"
          hint={perf ? `${perf.sessionsByStatus.COMPLETED ?? 0} completed · ${perf.sessionsByStatus.CANCELLED ?? 0} cancelled` : undefined} />
        <StatCard label="Completion rate" value={pct(perf?.completionRate)} icon={<IconCheck size={20} />} color="teal"
          hint={perf ? `${date(perf.from)} – ${date(perf.to)}` : undefined} />
        <StatCard label="No-show rate" value={pct(perf?.noShowRate)} icon={<IconX size={20} />} color="red"
          hint={perf ? `${perf.sessionsByStatus.NO_SHOW ?? 0} no-shows` : undefined} />
      </SimpleGrid>

      <Grid gutter="lg">
        <Grid.Col span={{ base: 12, lg: 4 }}>
          <Card withBorder padding="lg">
            <Title order={5} mb="sm">Profile</Title>
            <Stack gap={8}>
              <Row k="Home branch" v={branchNames[t.homeBranchId] ?? `#${t.homeBranchId}`} />
              <Row k="Experience" v={`${t.yearsOfExperience} years`} />
              <Row k="Hourly rate" v={money(t.hourlyRate)} />
              <Row k="Max active clients" v={String(t.maxActiveClients)} />
              <Row k="Certifications" v={t.certifications || '—'} />
              <div>
                <Text size="sm" c="dimmed" mb={4}>Specialisations</Text>
                <Group gap={4}>
                  {t.specializations.length === 0 && <Text size="sm">—</Text>}
                  {t.specializations.map((s) => <Badge key={s} variant="light" color="indigo">{label(s)}</Badge>)}
                </Group>
              </div>
              <Divider />
              <Text size="sm" c={t.bio ? undefined : 'dimmed'} style={{ whiteSpace: 'pre-line' }}>{t.bio || 'No bio yet.'}</Text>
            </Stack>
          </Card>
        </Grid.Col>

        <Grid.Col span={{ base: 12, lg: 8 }}>
          <Paper withBorder p="lg">
            <Tabs value={tab} onChange={setTab} keepMounted={false}>
              <Tabs.List mb="md">
                <Tabs.Tab value="clients" leftSection={<IconUsers size={16} />}>Clients</Tabs.Tab>
                <Tabs.Tab value="schedule" leftSection={<IconCalendarTime size={16} />}>Next 7 days</Tabs.Tab>
                <Tabs.Tab value="availability" leftSection={<IconClock size={16} />}>Weekly availability</Tabs.Tab>
              </Tabs.List>
              <Tabs.Panel value="clients"><ClientsPanel trainer={t} canManage={canManage} /></Tabs.Panel>
              <Tabs.Panel value="schedule"><SchedulePanel trainerId={t.id} /></Tabs.Panel>
              <Tabs.Panel value="availability">
                <AvailabilityEditor key={JSON.stringify(t.availability)} trainer={t} canEdit={canManage} />
              </Tabs.Panel>
            </Tabs>
          </Paper>
        </Grid.Col>
      </Grid>

      <Modal opened={editing} onClose={() => setEditing(false)} title="Edit trainer profile" size="lg">
        {editing && <EditTrainerForm trainer={t} onDone={() => setEditing(false)} />}
      </Modal>
    </>
  );
}

function BackLink() {
  return (
    <Anchor component={Link} to="/trainers" size="sm" mb="xs" display="inline-flex" style={{ alignItems: 'center', gap: 4 }}>
      <IconArrowLeft size={14} /> All trainers
    </Anchor>
  );
}

function Row({ k, v }: { k: string; v: string }) {
  return (
    <Group justify="space-between" wrap="nowrap" align="flex-start">
      <Text size="sm" c="dimmed">{k}</Text>
      <Text size="sm" ta="right">{v}</Text>
    </Group>
  );
}

// ------------------------------------------------------------------ clients

function ClientsPanel({ trainer, canManage }: { trainer: Trainer; canManage: boolean }) {
  const clients = useApiQuery<TrainerAssignment[]>(`/api/v1/trainers/${trainer.id}/clients`);
  const [assigning, setAssigning] = useState(false);
  const [ending, setEnding] = useState<TrainerAssignment | null>(null);
  const end = useApiMutation<number>({
    request: (memberId) => ({ path: `/api/v1/trainers/${trainer.id}/clients/${memberId}`, method: 'DELETE' }),
    invalidate: [`/api/v1/trainers/${trainer.id}`, '/api/v1/members'],
    successMessage: 'Assignment ended',
    onSuccess: () => setEnding(null),
  });
  const count = clients.data?.length ?? 0;
  const full = count >= trainer.maxActiveClients;

  return (
    <>
      <Group justify="space-between" mb="sm">
        <Text size="sm" c="dimmed">{count} of {trainer.maxActiveClients} client slots used</Text>
        {canManage && (
          <Tooltip label={full ? 'Trainer is at capacity' : trainer.status !== 'ACTIVE' ? `Trainer is ${label(trainer.status).toLowerCase()}` : 'Assign a member'}>
            <Button size="xs" leftSection={<IconUserPlus size={14} />} onClick={() => setAssigning(true)}
              disabled={full || trainer.status !== 'ACTIVE'}>
              Assign client
            </Button>
          </Tooltip>
        )}
      </Group>
      <QueryState isLoading={clients.isLoading} error={clients.error} empty={count === 0} emptyText="No active clients.">
        <Table.ScrollContainer minWidth={560}>
          <Table striped verticalSpacing="xs">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Member</Table.Th>
                <Table.Th>Goal</Table.Th>
                <Table.Th>Since</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {clients.data?.map((a) => (
                <Table.Tr key={a.id}>
                  <Table.Td>
                    <Anchor component={Link} to={`/members/${a.memberId}`} size="sm" fw={600}>{a.memberName}</Anchor>
                    <Text size="xs" c="dimmed">{a.memberCode}</Text>
                  </Table.Td>
                  <Table.Td><Text size="sm">{a.goal ?? '—'}</Text></Table.Td>
                  <Table.Td><Text size="sm">{date(a.assignedAt)}</Text></Table.Td>
                  <Table.Td>
                    {canManage && (
                      <Group justify="flex-end">
                        <Tooltip label="End assignment">
                          <ActionIcon variant="subtle" color="red" onClick={() => setEnding(a)} aria-label="End assignment">
                            <IconUserMinus size={16} />
                          </ActionIcon>
                        </Tooltip>
                      </Group>
                    )}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      </QueryState>

      <Modal opened={assigning} onClose={() => setAssigning(false)} title={`Assign client to ${trainer.fullName}`} centered>
        {assigning && <AssignClientForm trainer={trainer} onDone={() => setAssigning(false)} />}
      </Modal>

      <Modal opened={ending !== null} onClose={() => setEnding(null)} title="End assignment" centered>
        {ending && (
          <Stack>
            <Text size="sm">
              End the coaching assignment between <b>{trainer.fullName}</b> and <b>{ending.memberName}</b>? Their workout
              history is kept.
            </Text>
            <Group justify="flex-end">
              <Button variant="default" onClick={() => setEnding(null)}>Cancel</Button>
              <Button color="red" loading={end.isPending} onClick={() => end.mutate(ending.memberId)}>End assignment</Button>
            </Group>
          </Stack>
        )}
      </Modal>
    </>
  );
}

function AssignClientForm({ trainer, onDone }: { trainer: Trainer; onDone: () => void }) {
  const [memberId, setMemberId] = useState<number | null>(null);
  const [goal, setGoal] = useState('');
  const assign = useApiMutation<void, TrainerAssignment>({
    request: () => ({ path: `/api/v1/trainers/${trainer.id}/clients`, body: { memberId, goal: goal.trim() || null } }),
    invalidate: [`/api/v1/trainers/${trainer.id}`, '/api/v1/members'],
    successMessage: (a) => `${a.memberName} assigned to ${a.trainerName}`,
    silentError: true,
    onSuccess: onDone,
  });
  return (
    <Stack>
      <MemberSearch
        label="Member"
        description="Members of the trainer's home branch. Any existing trainer assignment is ended."
        value={memberId}
        onChange={(v) => setMemberId(v)}
        branchId={trainer.homeBranchId}
        withAsterisk
      />
      <Textarea label="Goal" placeholder="e.g. Lose 5 kg before March" autosize minRows={2} maxLength={300}
        value={goal} onChange={(e) => setGoal(e.currentTarget.value)} />
      {assign.error && <Alert color="red">{errorMessage(assign.error)}</Alert>}
      <Group justify="flex-end">
        <Button variant="default" onClick={onDone}>Cancel</Button>
        <Button disabled={!memberId} loading={assign.isPending} onClick={() => assign.mutate()}>Assign</Button>
      </Group>
    </Stack>
  );
}

// ------------------------------------------------------------------ schedule

function SchedulePanel({ trainerId }: { trainerId: number }) {
  const schedule = useApiQuery<TrainingSession[]>(`/api/v1/trainers/${trainerId}/schedule`);
  const branchNames = useBranchNames();
  const groups = useMemo(() => {
    const byDay = new Map<string, TrainingSession[]>();
    [...(schedule.data ?? [])]
      .sort((a, b) => a.startsAt.localeCompare(b.startsAt))
      .forEach((s) => {
        const key = dayjs(s.startsAt).format('YYYY-MM-DD');
        byDay.set(key, [...(byDay.get(key) ?? []), s]);
      });
    return [...byDay.entries()];
  }, [schedule.data]);

  return (
    <QueryState isLoading={schedule.isLoading} error={schedule.error} empty={groups.length === 0} emptyText="No sessions in the next 7 days.">
      <Stack gap="md">
        {groups.map(([day, sessions]) => (
          <div key={day}>
            <Text fw={600} size="sm" mb={4} component="div">
              {dayjs(day).format('dddd, DD MMM')}
              {dayjs(day).isSame(dayjs(), 'day') && <Badge ml="xs" size="xs">Today</Badge>}
            </Text>
            <Stack gap={4}>
              {sessions.map((s) => (
                <Paper key={s.id} withBorder p="xs" radius="sm">
                  <Group justify="space-between" wrap="nowrap">
                    <Group gap="sm" wrap="nowrap">
                      <Text size="sm" ff="monospace" w={96}>{time(s.startsAt)}–{time(s.endsAt)}</Text>
                      <div>
                        <Anchor component={Link} to={`/members/${s.memberId}`} size="sm" fw={600}>{s.memberName}</Anchor>
                        <Text size="xs" c="dimmed">{branchNames[s.branchId] ?? `Branch #${s.branchId}`}{s.notes ? ` · ${s.notes}` : ''}</Text>
                      </div>
                    </Group>
                    <StatusBadge value={s.status} />
                  </Group>
                </Paper>
              ))}
            </Stack>
          </div>
        ))}
      </Stack>
    </QueryState>
  );
}

// ------------------------------------------------------------------ availability

interface SlotDraft {
  key: number;
  branchId: number;
  dayOfWeek: number;
  startTime: string;
  endTime: string;
}

let slotSeq = 0;
const toDraft = (s: AvailabilitySlot): SlotDraft => ({
  key: ++slotSeq, branchId: s.branchId, dayOfWeek: s.dayOfWeek, startTime: s.startTime.slice(0, 5), endTime: s.endTime.slice(0, 5),
});

/** Returns an error per slot key: missing/unordered times or overlap with another slot on the same day. */
function validateSlots(slots: SlotDraft[]): Record<number, string> {
  const errors: Record<number, string> = {};
  slots.forEach((s) => {
    if (!s.startTime || !s.endTime) errors[s.key] = 'Start and end time required';
    else if (s.startTime >= s.endTime) errors[s.key] = 'Start must be before end';
  });
  const valid = slots.filter((s) => !errors[s.key]);
  valid.forEach((a) => {
    valid.forEach((b) => {
      if (a.key !== b.key && a.dayOfWeek === b.dayOfWeek && a.startTime < b.endTime && b.startTime < a.endTime) {
        errors[a.key] = 'Overlaps another slot on this day';
      }
    });
  });
  return errors;
}

function AvailabilityEditor({ trainer, canEdit }: { trainer: Trainer; canEdit: boolean }) {
  const initial = useMemo(() => trainer.availability.map(toDraft), [trainer.availability]);
  const [slots, setSlots] = useState<SlotDraft[]>(initial);
  const branches = useApiQuery<Page<Branch>>('/api/v1/branches', { size: 100 });
  const branchOptions = (branches.data?.content ?? []).map((b) => ({ value: String(b.id), label: b.name }));
  const errors = validateSlots(slots);
  const hasErrors = Object.keys(errors).length > 0;
  const strip = (list: SlotDraft[]) =>
    JSON.stringify(list.map(({ branchId, dayOfWeek, startTime, endTime }) => [branchId, dayOfWeek, startTime, endTime]));
  const dirty = strip(slots) !== strip(initial);

  const save = useApiMutation<void, Trainer>({
    request: () => ({
      path: `/api/v1/trainers/${trainer.id}/availability`,
      method: 'PUT',
      body: {
        slots: slots.map(({ branchId, dayOfWeek, startTime, endTime }) => ({ branchId, dayOfWeek, startTime, endTime })),
      },
    }),
    invalidate: [`/api/v1/trainers/${trainer.id}`],
    successMessage: 'Availability saved',
    silentError: true,
  });

  const update = (key: number, patch: Partial<SlotDraft>) =>
    setSlots((list) => list.map((s) => (s.key === key ? { ...s, ...patch } : s)));
  const add = (day: number) => {
    const sameDay = slots.filter((s) => s.dayOfWeek === day).sort((a, b) => a.endTime.localeCompare(b.endTime));
    const last = sameDay[sameDay.length - 1];
    const start = last && last.endTime < '22:00' ? last.endTime : '06:00';
    const end = dayjs(`2000-01-01T${start}`).add(2, 'hour').format('HH:mm');
    setSlots((list) => [...list, { key: ++slotSeq, branchId: trainer.homeBranchId, dayOfWeek: day, startTime: start, endTime: end < start ? '23:59' : end }]);
  };
  const copyToWeekdays = (day: number) => {
    const source = slots.filter((s) => s.dayOfWeek === day);
    setSlots((list) => [
      ...list.filter((s) => s.dayOfWeek === day || s.dayOfWeek > 5),
      ...[1, 2, 3, 4, 5].filter((d) => d !== day).flatMap((d) => source.map((s) => ({ ...s, key: ++slotSeq, dayOfWeek: d }))),
    ]);
  };

  return (
    <Stack gap="sm">
      <Text size="sm" c="dimmed">
        Sessions can only be booked inside these windows. Slots on the same day must not overlap.
      </Text>
      {WEEKDAYS.map((name, i) => {
        const day = i + 1;
        const daySlots = slots.filter((s) => s.dayOfWeek === day).sort((a, b) => a.startTime.localeCompare(b.startTime));
        return (
          <Paper key={day} withBorder p="sm" radius="sm">
            <Group justify="space-between" mb={daySlots.length ? 'xs' : 0}>
              <Group gap="xs">
                <Text fw={600} size="sm" w={36}>{name}</Text>
                {daySlots.length === 0 && <Text size="sm" c="dimmed">Unavailable</Text>}
              </Group>
              {canEdit && (
                <Group gap={4}>
                  {day <= 5 && daySlots.length > 0 && (
                    <Button size="compact-xs" variant="subtle" onClick={() => copyToWeekdays(day)}>Copy to Mon–Fri</Button>
                  )}
                  <Button size="compact-xs" variant="light" leftSection={<IconPlus size={12} />} onClick={() => add(day)}
                    disabled={slots.length >= 42}>
                    Slot
                  </Button>
                </Group>
              )}
            </Group>
            <Stack gap={6}>
              {daySlots.map((s) => (
                <div key={s.key}>
                  <Group gap="xs" wrap="wrap" align="center">
                    <TimeInput size="xs" w={110} value={s.startTime} disabled={!canEdit} aria-label="Start time"
                      onChange={(e) => update(s.key, { startTime: e.currentTarget.value })} error={!!errors[s.key]} />
                    <Text size="xs" c="dimmed">to</Text>
                    <TimeInput size="xs" w={110} value={s.endTime} disabled={!canEdit} aria-label="End time"
                      onChange={(e) => update(s.key, { endTime: e.currentTarget.value })} error={!!errors[s.key]} />
                    <Select size="xs" w={200} data={branchOptions} value={String(s.branchId)} disabled={!canEdit}
                      allowDeselect={false} aria-label="Branch"
                      onChange={(v) => v && update(s.key, { branchId: Number(v) })} />
                    {canEdit && (
                      <ActionIcon variant="subtle" color="red" aria-label="Remove slot"
                        onClick={() => setSlots((list) => list.filter((x) => x.key !== s.key))}>
                        <IconTrash size={14} />
                      </ActionIcon>
                    )}
                  </Group>
                  {errors[s.key] && <Text size="xs" c="red" mt={2}>{errors[s.key]}</Text>}
                </div>
              ))}
            </Stack>
          </Paper>
        );
      })}
      {save.error && <Alert color="red" style={{ whiteSpace: 'pre-line' }}>{errorMessage(save.error)}</Alert>}
      {canEdit && (
        <Group justify="flex-end">
          <Button variant="default" disabled={!dirty} onClick={() => { setSlots(initial); save.reset(); }}>Reset</Button>
          <Button disabled={!dirty || hasErrors} loading={save.isPending} onClick={() => save.mutate()}>Save availability</Button>
        </Group>
      )}
    </Stack>
  );
}

// ------------------------------------------------------------------ edit

interface EditValues {
  homeBranchId: number | null;
  bio: string;
  certifications: string;
  yearsOfExperience: number | string;
  hourlyRate: number | string;
  maxActiveClients: number | string;
  status: TrainerStatus;
  specializations: string[];
}

function EditTrainerForm({ trainer, onDone }: { trainer: Trainer; onDone: () => void }) {
  const form = useForm<EditValues>({
    initialValues: {
      homeBranchId: trainer.homeBranchId,
      bio: trainer.bio ?? '',
      certifications: trainer.certifications ?? '',
      yearsOfExperience: trainer.yearsOfExperience,
      hourlyRate: trainer.hourlyRate ?? '',
      maxActiveClients: trainer.maxActiveClients,
      status: trainer.status,
      specializations: trainer.specializations,
    },
    validate: {
      homeBranchId: (v) => (v ? null : 'Required'),
      bio: (v) => (v.length > 1000 ? 'Max 1000 characters' : null),
      certifications: (v) => (v.length > 500 ? 'Max 500 characters' : null),
      yearsOfExperience: (v) => (Number.isInteger(Number(v)) && Number(v) >= 0 && Number(v) <= 60 ? null : '0-60'),
      hourlyRate: (v) => (v === '' || Number(v) >= 0 ? null : 'Must be ≥ 0'),
      maxActiveClients: (v) => (v === '' || (Number.isInteger(Number(v)) && Number(v) >= 1 && Number(v) <= 200) ? null : '1-200'),
      specializations: (v) => (v.length > 10 ? 'At most 10' : v.some((s) => s.length > 50) ? 'Max 50 characters each' : null),
    },
  });
  const save = useApiMutation<EditValues, Trainer>({
    request: (v) => ({
      path: `/api/v1/trainers/${trainer.id}`,
      method: 'PUT',
      body: {
        homeBranchId: v.homeBranchId,
        bio: v.bio.trim() || null,
        certifications: v.certifications.trim() || null,
        yearsOfExperience: Number(v.yearsOfExperience) || 0,
        hourlyRate: v.hourlyRate === '' ? null : Number(v.hourlyRate),
        maxActiveClients: v.maxActiveClients === '' ? null : Number(v.maxActiveClients),
        status: v.status,
        specializations: v.specializations.map((s) => s.trim().toUpperCase().replace(/\s+/g, '_')),
      },
    }),
    invalidate: ['/api/v1/trainers'],
    successMessage: 'Trainer profile saved',
    silentError: true,
    onSuccess: onDone,
  });
  return (
    <form onSubmit={form.onSubmit((v) => save.mutate(v))}>
      <Stack>
        <SimpleGrid cols={{ base: 1, sm: 2 }}>
          <BranchSelect label="Home branch" withAsterisk value={form.values.homeBranchId}
            onChange={(v) => form.setFieldValue('homeBranchId', v)} error={form.errors.homeBranchId} />
          <Select label="Status" data={STATUSES.map((s) => ({ value: s, label: label(s) }))} allowDeselect={false}
            {...form.getInputProps('status')} />
          <NumberInput label="Years of experience" min={0} max={60} allowDecimal={false} {...form.getInputProps('yearsOfExperience')} />
          <NumberInput label="Hourly rate (₹)" min={0} decimalScale={2} thousandSeparator="," {...form.getInputProps('hourlyRate')} />
          <NumberInput label="Max active clients" min={1} max={200} allowDecimal={false} {...form.getInputProps('maxActiveClients')} />
        </SimpleGrid>
        <TagsInput label="Specialisations" data={SPECIALIZATION_SUGGESTIONS} maxTags={10} clearable
          {...form.getInputProps('specializations')} />
        <TextInput label="Certifications" {...form.getInputProps('certifications')} />
        <Textarea label="Bio" autosize minRows={3} {...form.getInputProps('bio')} />
        {save.error && <Alert color="red" style={{ whiteSpace: 'pre-line' }}>{errorMessage(save.error)}</Alert>}
        <Group justify="flex-end">
          <Button variant="default" onClick={onDone}>Cancel</Button>
          <Button type="submit" loading={save.isPending}>Save</Button>
        </Group>
      </Stack>
    </form>
  );
}
