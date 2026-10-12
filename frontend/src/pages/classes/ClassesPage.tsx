import {
  ActionIcon, Alert, Badge, Box, Button, Chip, Divider, Group, Modal, NumberInput, Paper, Progress, Select, SimpleGrid, Stack,
  Switch, Table, Text, TextInput, Textarea, Tooltip,
} from '@mantine/core';
import { DateTimePicker } from '@mantine/dates';
import { useForm } from '@mantine/form';
import {
  IconCalendarPlus, IconCategory, IconChevronLeft, IconChevronRight, IconClock, IconDoor, IconPencil, IconTrash, IconUser,
} from '@tabler/icons-react';
import dayjs, { type Dayjs } from 'dayjs';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  BOOKING_COLORS, CATEGORY_COLORS, CLASS_CATEGORIES, CLASS_INTENSITIES, type BookingStatus, type ClassCategory, type ClassIntensity,
  type ClassSession, type ClassType, type ClassTypeRequest, type SeriesRequest, type SeriesResponse, type SessionRequest,
} from '@/api/classes';
import type { Branch, Page, Trainer } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { hasAnyRole, MANAGERS } from '@/auth/roles';
import { BranchSelect } from '@/components/BranchSelect';
import { PageHeader } from '@/components/PageHeader';
import { QueryState } from '@/components/QueryState';
import { StatusBadge } from '@/components/StatusBadge';
import { label, time, WEEKDAYS } from '@/lib/format';
import { errorMessage } from '@/lib/notify';
import { useApiMutation, useApiQuery } from '@/lib/useApi';

// ---------------------------------------------------------------------------------------------
// Shared helpers (also used by the session page and the member classes page)
// ---------------------------------------------------------------------------------------------

export const CLASSES_API = '/api/v1/classes';

export function BookingBadge({ status, size = 'sm' }: { status: BookingStatus | null | undefined; size?: 'xs' | 'sm' | 'md' }) {
  if (!status) return null;
  return (
    <Badge variant="light" radius="sm" size={size} color={BOOKING_COLORS[status]}>
      {label(status)}
    </Badge>
  );
}

export function CategoryBadge({ category, size = 'xs' }: { category: ClassCategory; size?: 'xs' | 'sm' }) {
  return (
    <Badge variant="dot" size={size} color={CATEGORY_COLORS[category]}>
      {label(category)}
    </Badge>
  );
}

/** "Mon 13 Oct · 18:00–18:45" */
export const classWhen = (s: { startsAt: string; endsAt: string }) =>
  `${dayjs(s.startsAt).format('ddd D MMM')} · ${time(s.startsAt)}–${time(s.endsAt)}`;

/** Monday of the week containing {@code d}. */
export const weekStart = (d: Dayjs) => d.subtract((d.day() + 6) % 7, 'day').startOf('day');

/** Seat usage bar with "booked / capacity" and waitlist count. */
export function SeatBar({ session }: { session: ClassSession }) {
  const ratio = session.capacity ? (session.booked / session.capacity) * 100 : 0;
  const full = session.spotsLeft === 0;
  return (
    <Stack gap={2}>
      <Group justify="space-between" gap={4} wrap="nowrap">
        <Text size="xs" fw={600}>
          {session.booked}/{session.capacity} booked
        </Text>
        {session.waitlisted > 0 ? (
          <Text size="xs" c="orange" fw={600}>+{session.waitlisted} waitlist</Text>
        ) : (
          <Text size="xs" c="dimmed">{full ? 'Full' : `${session.spotsLeft} left`}</Text>
        )}
      </Group>
      <Progress value={ratio} size="sm" color={full ? 'orange' : ratio >= 75 ? 'yellow' : 'teal'} />
    </Stack>
  );
}

function useTrainers(branchId: number | null) {
  return useApiQuery<Page<Trainer>>(branchId ? '/api/v1/trainers' : null, { branchId: branchId ?? undefined, size: 100 });
}

// ---------------------------------------------------------------------------------------------
// Class types
// ---------------------------------------------------------------------------------------------

interface TypeFormValues {
  name: string;
  description: string;
  category: ClassCategory;
  intensity: ClassIntensity;
  defaultDurationMin: number | string;
  defaultCapacity: number | string;
  active: boolean;
}

function ClassTypeForm({ type, onDone }: { type: ClassType | null; onDone: () => void }) {
  const form = useForm<TypeFormValues>({
    initialValues: type
      ? { name: type.name, description: type.description ?? '', category: type.category, intensity: type.intensity,
          defaultDurationMin: type.defaultDurationMin, defaultCapacity: type.defaultCapacity, active: type.active }
      : { name: '', description: '', category: 'YOGA', intensity: 'MEDIUM', defaultDurationMin: 45, defaultCapacity: 15, active: true },
    validate: {
      name: (v) => (!v.trim() ? 'Required' : v.length > 80 ? 'Max 80 characters' : null),
      description: (v) => (v.length > 1000 ? 'Max 1000 characters' : null),
      defaultDurationMin: (v) => (Number(v) >= 15 && Number(v) <= 180 ? null : '15-180 minutes'),
      defaultCapacity: (v) => (Number(v) >= 1 && Number(v) <= 200 ? null : '1-200'),
    },
  });
  const save = useApiMutation<TypeFormValues, ClassType>({
    request: (v) => {
      const body: ClassTypeRequest = {
        name: v.name.trim(), description: v.description.trim() || null, category: v.category, intensity: v.intensity,
        defaultDurationMin: Number(v.defaultDurationMin), defaultCapacity: Number(v.defaultCapacity), active: v.active,
      };
      return { path: type ? `${CLASSES_API}/types/${type.id}` : `${CLASSES_API}/types`, method: type ? 'PUT' : 'POST', body };
    },
    invalidate: [CLASSES_API],
    successMessage: (t) => `${t.name} saved`,
    silentError: true,
    onSuccess: onDone,
  });
  return (
    <form onSubmit={form.onSubmit((v) => save.mutate(v))}>
      <Stack gap="sm">
        <TextInput label="Name" withAsterisk {...form.getInputProps('name')} />
        <SimpleGrid cols={2}>
          <Select label="Category" data={CLASS_CATEGORIES.map((c) => ({ value: c, label: label(c) }))} allowDeselect={false}
            {...form.getInputProps('category')} />
          <Select label="Intensity" data={CLASS_INTENSITIES.map((c) => ({ value: c, label: label(c) }))} allowDeselect={false}
            {...form.getInputProps('intensity')} />
          <NumberInput label="Default duration (min)" min={15} max={180} allowDecimal={false} {...form.getInputProps('defaultDurationMin')} />
          <NumberInput label="Default capacity" min={1} max={200} allowDecimal={false} {...form.getInputProps('defaultCapacity')} />
        </SimpleGrid>
        <Textarea label="Description" autosize minRows={2} {...form.getInputProps('description')} />
        {type && <Switch label="Active (can be scheduled)" {...form.getInputProps('active', { type: 'checkbox' })} />}
        {save.error && <Alert color="red" variant="light">{errorMessage(save.error)}</Alert>}
        <Group justify="flex-end">
          <Button variant="default" onClick={onDone}>Back</Button>
          <Button type="submit" loading={save.isPending}>{type ? 'Save' : 'Create class type'}</Button>
        </Group>
      </Stack>
    </form>
  );
}

function ClassTypesModal({ opened, onClose }: { opened: boolean; onClose: () => void }) {
  const types = useApiQuery<ClassType[]>(opened ? `${CLASSES_API}/types` : null, { includeInactive: true });
  const [editing, setEditing] = useState<ClassType | 'new' | null>(null);
  const remove = useApiMutation<ClassType, void>({
    request: (t) => ({ path: `${CLASSES_API}/types/${t.id}`, method: 'DELETE' }),
    invalidate: [CLASSES_API],
    successMessage: 'Class type deleted',
  });
  const close = () => {
    setEditing(null);
    onClose();
  };
  return (
    <Modal opened={opened} onClose={close} size="xl" title={editing ? (editing === 'new' ? 'New class type' : `Edit ${editing.name}`) : 'Class types'}>
      {editing ? (
        <ClassTypeForm key={editing === 'new' ? 'new' : editing.id} type={editing === 'new' ? null : editing} onDone={() => setEditing(null)} />
      ) : (
        <Stack>
          <Group justify="space-between">
            <Text size="sm" c="dimmed">The catalogue is shared by all branches. Types with classes can be deactivated, not deleted.</Text>
            <Button size="xs" leftSection={<IconCategory size={14} />} onClick={() => setEditing('new')}>New type</Button>
          </Group>
          <QueryState isLoading={types.isLoading} error={types.error} empty={types.data?.length === 0} emptyText="No class types yet.">
            <Table.ScrollContainer minWidth={560}>
              <Table verticalSpacing="xs" highlightOnHover>
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>Name</Table.Th>
                    <Table.Th>Intensity</Table.Th>
                    <Table.Th ta="right">Duration</Table.Th>
                    <Table.Th ta="right">Capacity</Table.Th>
                    <Table.Th>Status</Table.Th>
                    <Table.Th />
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {(types.data ?? []).map((t) => (
                    <Table.Tr key={t.id}>
                      <Table.Td>
                        <Text size="sm" fw={600}>{t.name}</Text>
                        <CategoryBadge category={t.category} />
                      </Table.Td>
                      <Table.Td><StatusBadge value={t.intensity} size="sm" /></Table.Td>
                      <Table.Td ta="right">{t.defaultDurationMin} min</Table.Td>
                      <Table.Td ta="right">{t.defaultCapacity}</Table.Td>
                      <Table.Td><StatusBadge value={t.active ? 'ACTIVE' : 'INACTIVE'} size="sm" /></Table.Td>
                      <Table.Td>
                        <Group gap={4} justify="flex-end" wrap="nowrap">
                          <ActionIcon variant="subtle" aria-label={`Edit ${t.name}`} onClick={() => setEditing(t)}>
                            <IconPencil size={16} />
                          </ActionIcon>
                          <Tooltip label="Delete (only if never scheduled)">
                            <ActionIcon variant="subtle" color="red" aria-label={`Delete ${t.name}`} loading={remove.isPending}
                              onClick={() => remove.mutate(t)}>
                              <IconTrash size={16} />
                            </ActionIcon>
                          </Tooltip>
                        </Group>
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </Table.ScrollContainer>
          </QueryState>
        </Stack>
      )}
    </Modal>
  );
}

// ---------------------------------------------------------------------------------------------
// New class (single or weekly series)
// ---------------------------------------------------------------------------------------------

interface NewClassValues {
  classTypeId: string | null;
  branchId: number | null;
  instructorId: string | null;
  room: string;
  startsAt: Date | null;
  durationMinutes: number | string;
  capacity: number | string;
  waitlistCapacity: number | string;
  bookingOpensHours: number | string;
  cancelCutoffMinutes: number | string;
  recurring: boolean;
  daysOfWeek: string[];
  weeks: number | string;
}

const optNum = (v: number | string): number | null => (v === '' || v == null ? null : Number(v));

function NewClassModal({ opened, onClose, defaultBranchId }: { opened: boolean; onClose: () => void; defaultBranchId: number | null }) {
  const types = useApiQuery<ClassType[]>(opened ? `${CLASSES_API}/types` : null);
  const [result, setResult] = useState<SeriesResponse | null>(null);
  const form = useForm<NewClassValues>({
    initialValues: {
      classTypeId: null, branchId: defaultBranchId, instructorId: null, room: '', startsAt: null, durationMinutes: '', capacity: '',
      waitlistCapacity: 10, bookingOpensHours: 168, cancelCutoffMinutes: 120, recurring: false, daysOfWeek: [], weeks: 4,
    },
    validate: {
      classTypeId: (v) => (v ? null : 'Required'),
      branchId: (v) => (v ? null : 'Required'),
      instructorId: (v) => (v ? null : 'Required'),
      startsAt: (v) => (!v ? 'Required' : dayjs(v).isBefore(dayjs()) ? 'Must be in the future' : null),
      daysOfWeek: (v, vals) => (vals.recurring && v.length === 0 ? 'Pick at least one day' : null),
      weeks: (v, vals) => (!vals.recurring || (Number(v) >= 1 && Number(v) <= 26) ? null : '1-26 weeks'),
    },
  });
  const v = form.values;
  const trainers = useTrainers(v.branchId);
  const type = types.data?.find((t) => String(t.id) === v.classTypeId);

  const close = () => {
    form.reset();
    setResult(null);
    create.reset();
    onClose();
  };

  const create = useApiMutation<NewClassValues, ClassSession | SeriesResponse>({
    request: (vals) => {
      const session: SessionRequest = {
        classTypeId: Number(vals.classTypeId), branchId: Number(vals.branchId), instructorId: Number(vals.instructorId),
        room: vals.room.trim() || null, startsAt: dayjs(vals.startsAt).second(0).millisecond(0).toISOString(),
        durationMinutes: optNum(vals.durationMinutes), capacity: optNum(vals.capacity), waitlistCapacity: optNum(vals.waitlistCapacity),
        bookingOpensHours: optNum(vals.bookingOpensHours), cancelCutoffMinutes: optNum(vals.cancelCutoffMinutes),
      };
      if (vals.recurring) {
        const body: SeriesRequest = { session, daysOfWeek: vals.daysOfWeek.map(Number), weeks: Number(vals.weeks) };
        return { path: `${CLASSES_API}/sessions/series`, body };
      }
      return { path: `${CLASSES_API}/sessions`, body: session };
    },
    invalidate: [CLASSES_API],
    silentError: true,
    successMessage: (r) => ('seriesId' in r && 'created' in r ? `${r.created.length} classes scheduled` : 'Class scheduled'),
    onSuccess: (r) => {
      if ('created' in r) setResult(r);
      else close();
    },
  });

  return (
    <Modal opened={opened} onClose={close} size="lg" title="Schedule a class">
      {result ? (
        <Stack>
          <Alert color="teal" variant="light" title={`${result.created.length} classes scheduled`}>
            {result.created.length > 0 && (
              <Text size="sm">
                {classWhen(result.created[0])} … {classWhen(result.created[result.created.length - 1])}
              </Text>
            )}
          </Alert>
          {result.skipped.length > 0 && (
            <Alert color="yellow" variant="light" title={`${result.skipped.length} slots skipped`}>
              <Stack gap={2}>
                {result.skipped.map((s) => (
                  <Text key={s.startsAt} size="sm">{dayjs(s.startsAt).format('ddd D MMM HH:mm')} — {s.reason}</Text>
                ))}
              </Stack>
            </Alert>
          )}
          <Group justify="flex-end"><Button onClick={close}>Done</Button></Group>
        </Stack>
      ) : (
        <form onSubmit={form.onSubmit((vals) => create.mutate(vals))}>
          <Stack gap="sm">
            <SimpleGrid cols={{ base: 1, sm: 2 }}>
              <Select label="Class type" withAsterisk searchable data={(types.data ?? []).map((t) => ({ value: String(t.id), label: t.name }))}
                {...form.getInputProps('classTypeId')} />
              <BranchSelect label="Branch" withAsterisk value={v.branchId}
                onChange={(id) => { form.setFieldValue('branchId', id); form.setFieldValue('instructorId', null); }}
                error={form.errors.branchId} />
              <Select label="Instructor" withAsterisk searchable nothingFoundMessage="No trainers at this branch"
                data={(trainers.data?.content ?? []).filter((t) => t.status === 'ACTIVE')
                  .map((t) => ({ value: String(t.id), label: t.fullName.replace(/\s*\(Coach\)$/, '') }))}
                {...form.getInputProps('instructorId')} />
              <TextInput label="Room" placeholder="Studio A" maxLength={60} {...form.getInputProps('room')} />
              <DateTimePicker label={v.recurring ? 'First class' : 'Starts at'} withAsterisk valueFormat="ddd DD MMM YYYY HH:mm"
                minDate={new Date()} {...form.getInputProps('startsAt')} />
              <NumberInput label="Duration (min)" placeholder={type ? String(type.defaultDurationMin) : 'Type default'}
                min={15} max={180} allowDecimal={false} {...form.getInputProps('durationMinutes')} />
            </SimpleGrid>
            <Divider label="Capacity & policy" labelPosition="left" />
            <SimpleGrid cols={{ base: 2, sm: 4 }}>
              <NumberInput label="Capacity" placeholder={type ? String(type.defaultCapacity) : 'Type default'} min={1} max={200}
                allowDecimal={false} {...form.getInputProps('capacity')} />
              <NumberInput label="Waitlist" min={0} max={100} allowDecimal={false} {...form.getInputProps('waitlistCapacity')} />
              <NumberInput label="Booking opens (h before)" min={1} max={720} allowDecimal={false} {...form.getInputProps('bookingOpensHours')} />
              <NumberInput label="Free cancel until (min before)" min={0} max={1440} allowDecimal={false}
                {...form.getInputProps('cancelCutoffMinutes')} />
            </SimpleGrid>
            <Switch label="Repeat weekly" {...form.getInputProps('recurring', { type: 'checkbox' })} />
            {v.recurring && (
              <Paper withBorder p="sm" radius="md">
                <Stack gap="xs">
                  <Chip.Group multiple value={v.daysOfWeek} onChange={(days) => form.setFieldValue('daysOfWeek', days)}>
                    <Group gap={6}>
                      {WEEKDAYS.map((d, i) => (
                        <Chip key={d} value={String(i + 1)} size="xs">{d}</Chip>
                      ))}
                    </Group>
                  </Chip.Group>
                  {form.errors.daysOfWeek && <Text size="xs" c="red">{form.errors.daysOfWeek}</Text>}
                  <NumberInput label="For how many weeks" min={1} max={26} allowDecimal={false} w={180} {...form.getInputProps('weeks')} />
                  <Text size="xs" c="dimmed">
                    The time of the first class is repeated on the chosen days, starting with its week. Slots in the past or
                    clashing with the instructor&apos;s other classes (or the room) are skipped and listed afterwards.
                  </Text>
                </Stack>
              </Paper>
            )}
            {create.error && <Alert color="red" variant="light">{errorMessage(create.error)}</Alert>}
            <Group justify="flex-end">
              <Button variant="default" onClick={close}>Cancel</Button>
              <Button type="submit" loading={create.isPending}>{v.recurring ? 'Create series' : 'Schedule class'}</Button>
            </Group>
          </Stack>
        </form>
      )}
    </Modal>
  );
}

// ---------------------------------------------------------------------------------------------
// Timetable
// ---------------------------------------------------------------------------------------------

function SessionCard({ session, mine, onOpen }: { session: ClassSession; mine: boolean; onOpen: () => void }) {
  const cancelled = session.status === 'CANCELLED';
  return (
    <Paper
      withBorder
      p="xs"
      radius="md"
      onClick={onOpen}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => { if (e.key === 'Enter') onOpen(); }}
      aria-label={`${session.classTypeName} ${classWhen(session)}`}
      style={{
        cursor: 'pointer',
        opacity: cancelled ? 0.6 : 1,
        borderLeft: `4px solid var(--mantine-color-${CATEGORY_COLORS[session.category]}-6)`,
        outline: mine ? '2px solid var(--mantine-color-indigo-4)' : undefined,
      }}
    >
      <Stack gap={4}>
        <Group justify="space-between" gap={4}>
          <Text size="xs" fw={700} style={{ whiteSpace: 'nowrap' }}>
            <IconClock size={12} style={{ verticalAlign: -1 }} /> {time(session.startsAt)}–{time(session.endsAt)}
          </Text>
          {session.status !== 'SCHEDULED' && <StatusBadge value={session.status} size="xs" />}
        </Group>
        <Text size="sm" fw={600} lineClamp={1} td={cancelled ? 'line-through' : undefined}>{session.classTypeName}</Text>
        <Text size="xs" c="dimmed" lineClamp={1}>
          <IconUser size={11} style={{ verticalAlign: -1 }} /> {session.instructorName.replace(/\s*\(Coach\)$/, '')}
          {mine && <Badge size="xs" ml={4} variant="light" color="indigo">You</Badge>}
        </Text>
        {session.room && (
          <Text size="xs" c="dimmed" lineClamp={1}><IconDoor size={11} style={{ verticalAlign: -1 }} /> {session.room}</Text>
        )}
        {!cancelled && <SeatBar session={session} />}
      </Stack>
    </Paper>
  );
}

export default function ClassesPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const canManage = hasAnyRole(user, MANAGERS);
  const branches = useApiQuery<Page<Branch>>('/api/v1/branches', { size: 100 });
  const [branchChoice, setBranchChoice] = useState<number | null>(null);
  const branchId = branchChoice ?? user?.branchIds[0] ?? branches.data?.content[0]?.id ?? null;
  const [week, setWeek] = useState(() => weekStart(dayjs()));
  const [typeId, setTypeId] = useState<string | null>(null);
  const [instructorId, setInstructorId] = useState<string | null>(null);
  const [onlyMine, setOnlyMine] = useState(false);
  const [showCancelled, setShowCancelled] = useState(true);
  const [typesOpen, setTypesOpen] = useState(false);
  const [newOpen, setNewOpen] = useState(false);

  const types = useApiQuery<ClassType[]>(`${CLASSES_API}/types`);
  const trainers = useTrainers(branchId);
  const sessions = useApiQuery<Page<ClassSession>>(branchId ? `${CLASSES_API}/sessions` : null, {
    branchId: branchId ?? undefined,
    from: week.format('YYYY-MM-DD'),
    to: week.add(6, 'day').format('YYYY-MM-DD'),
    classTypeId: typeId ?? undefined,
    instructorId: onlyMine ? undefined : instructorId ?? undefined,
    mine: onlyMine || undefined,
    size: 100,
  });
  const days = Array.from({ length: 7 }, (_, i) => week.add(i, 'day'));
  const list = (sessions.data?.content ?? []).filter((s) => showCancelled || s.status !== 'CANCELLED');
  const myTrainerId = user?.trainerId ?? null;
  const totals = list.filter((s) => s.status !== 'CANCELLED').reduce(
    (acc, s) => ({ classes: acc.classes + 1, booked: acc.booked + s.booked, capacity: acc.capacity + s.capacity, waitlisted: acc.waitlisted + s.waitlisted }),
    { classes: 0, booked: 0, capacity: 0, waitlisted: 0 },
  );

  return (
    <>
      <PageHeader
        title="Group classes"
        subtitle={`Week of ${week.format('D MMM')} – ${week.add(6, 'day').format('D MMM YYYY')} · ${totals.classes} classes · ${totals.booked}/${totals.capacity} seats booked${totals.waitlisted ? ` · ${totals.waitlisted} waitlisted` : ''}`}
        actions={canManage && (
          <>
            <Button variant="default" leftSection={<IconCategory size={16} />} onClick={() => setTypesOpen(true)}>Class types</Button>
            <Button leftSection={<IconCalendarPlus size={16} />} onClick={() => setNewOpen(true)}>New class</Button>
          </>
        )}
      />

      <Paper withBorder p="sm" mb="md">
        <Group gap="sm" align="flex-end" wrap="wrap">
          <BranchSelect label="Branch" value={branchId} onChange={(id) => { setBranchChoice(id); setInstructorId(null); }} w={240} />
          <Group gap={4} align="flex-end">
            <ActionIcon variant="default" size="lg" aria-label="Previous week" onClick={() => setWeek((w) => w.subtract(7, 'day'))}>
              <IconChevronLeft size={16} />
            </ActionIcon>
            <Button variant="default" onClick={() => setWeek(weekStart(dayjs()))}>This week</Button>
            <ActionIcon variant="default" size="lg" aria-label="Next week" onClick={() => setWeek((w) => w.add(7, 'day'))}>
              <IconChevronRight size={16} />
            </ActionIcon>
          </Group>
          <Select label="Class type" placeholder="All types" clearable w={200} value={typeId} onChange={setTypeId}
            data={(types.data ?? []).map((t) => ({ value: String(t.id), label: t.name }))} />
          {!onlyMine && (
            <Select label="Instructor" placeholder="All instructors" clearable w={200} value={instructorId} onChange={setInstructorId}
              data={(trainers.data?.content ?? []).map((t) => ({ value: String(t.id), label: t.fullName.replace(/\s*\(Coach\)$/, '') }))} />
          )}
          <Stack gap={6} pb={4}>
            {myTrainerId != null && (
              <Switch size="xs" label="Only my classes" checked={onlyMine} onChange={(e) => setOnlyMine(e.currentTarget.checked)} />
            )}
            <Switch size="xs" label="Show cancelled" checked={showCancelled} onChange={(e) => setShowCancelled(e.currentTarget.checked)} />
          </Stack>
        </Group>
      </Paper>

      <QueryState isLoading={sessions.isLoading || (!branchId && branches.isLoading)} error={sessions.error}
        empty={!branchId} emptyText="Select a branch to see its timetable.">
        <Box style={{ overflowX: 'auto' }}>
          <SimpleGrid cols={{ base: 1, sm: 2, md: 4, lg: 7 }} spacing="xs" miw={0}>
            {days.map((day) => {
              const items = list.filter((s) => dayjs(s.startsAt).isSame(day, 'day'));
              const isToday = day.isSame(dayjs(), 'day');
              return (
                <Stack key={day.format('YYYY-MM-DD')} gap={6}>
                  <Paper p={6} radius="sm" bg={isToday ? 'var(--mantine-color-indigo-light)' : 'var(--mantine-color-default-hover)'}>
                    <Text size="sm" fw={700} ta="center">{day.format('ddd D MMM')}</Text>
                    <Text size="xs" c="dimmed" ta="center">{items.length ? `${items.length} classes` : 'No classes'}</Text>
                  </Paper>
                  {items.map((s) => (
                    <SessionCard key={s.id} session={s} mine={myTrainerId != null && s.instructorId === myTrainerId}
                      onOpen={() => navigate(`/classes/${s.id}`)} />
                  ))}
                </Stack>
              );
            })}
          </SimpleGrid>
        </Box>
      </QueryState>

      <ClassTypesModal opened={typesOpen} onClose={() => setTypesOpen(false)} />
      {newOpen && <NewClassModal opened onClose={() => setNewOpen(false)} defaultBranchId={branchId} />}
    </>
  );
}
