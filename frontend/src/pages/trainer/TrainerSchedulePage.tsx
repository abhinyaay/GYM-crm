import {
  ActionIcon, Alert, Badge, Button, Grid, Group, Modal, NumberInput, Paper, Select, SimpleGrid, Stack, Text, Textarea, Title, Tooltip,
} from '@mantine/core';
import { DateTimePicker } from '@mantine/dates';
import {
  IconCalendarPlus, IconCheck, IconChevronLeft, IconChevronRight, IconPercentage, IconUserOff, IconUsers, IconX,
} from '@tabler/icons-react';
import dayjs, { type Dayjs } from 'dayjs';
import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import type { ClassSession } from '@/api/classes';
import { ApiError } from '@/api/client';
import type {
  AvailabilitySlot, Page, SessionStatus, Trainer, TrainerAssignment, TrainerPerformance, TrainingSession,
} from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { PageHeader } from '@/components/PageHeader';
import { QueryState } from '@/components/QueryState';
import { StatCard } from '@/components/StatCard';
import { StatusBadge } from '@/components/StatusBadge';
import { label, pct, WEEKDAYS } from '@/lib/format';
import { errorMessage } from '@/lib/notify';
import { useApiMutation, useApiQuery } from '@/lib/useApi';

// ---------------------------------------------------------------------------------------------
// Shared session helpers (also used by the trainer home and client pages)
// ---------------------------------------------------------------------------------------------

type Outcome = Exclude<SessionStatus, 'SCHEDULED'>;

const OUTCOME_COPY: Record<Outcome, { title: string; button: string; color: string; help: string }> = {
  COMPLETED: { title: 'Mark session completed', button: 'Mark completed', color: 'teal',
    help: 'Sessions can only be completed once their start time has passed.' },
  NO_SHOW: { title: 'Mark as no-show', button: 'Mark no-show', color: 'red',
    help: 'The member did not turn up. This counts towards their no-show rate.' },
  CANCELLED: { title: 'Cancel session', button: 'Cancel session', color: 'orange',
    help: 'The member will be notified that the session is cancelled.' },
};

export const hhmm = (t: string) => t.slice(0, 5);

/** Session outcome buttons for a SCHEDULED session (complete / no-show / cancel with notes). */
export function SessionActions({ trainerId, session, size = 'xs' }: {
  trainerId: number;
  session: TrainingSession;
  size?: 'xs' | 'sm';
}) {
  const [target, setTarget] = useState<Outcome | null>(null);
  const [notes, setNotes] = useState('');
  const update = useApiMutation<Outcome, TrainingSession>({
    request: (status) => ({
      path: `/api/v1/trainers/${trainerId}/sessions/${session.id}`,
      method: 'PATCH',
      body: { status, notes: notes.trim() || undefined },
    }),
    invalidate: [`/api/v1/trainers/${trainerId}`, '/api/v1/dashboard'],
    successMessage: (s) => `Session ${label(s.status).toLowerCase()}`,
    silentError: true,
    onSuccess: () => close(),
  });
  const close = () => {
    setTarget(null);
    setNotes('');
    update.reset();
  };
  if (session.status !== 'SCHEDULED') return null;
  const started = dayjs(session.startsAt).isBefore(dayjs());
  const copy = target ? OUTCOME_COPY[target] : null;

  return (
    <>
      <Group gap={4} wrap="nowrap">
        <Tooltip label={started ? 'Mark completed' : 'Session has not started yet'}>
          <ActionIcon variant="light" color="teal" size={size === 'xs' ? 'md' : 'lg'}
            aria-label={`Mark session with ${session.memberName} completed`} onClick={() => setTarget('COMPLETED')}>
            <IconCheck size={16} />
          </ActionIcon>
        </Tooltip>
        <Tooltip label="No-show">
          <ActionIcon variant="light" color="red" size={size === 'xs' ? 'md' : 'lg'}
            aria-label={`Mark ${session.memberName} as no-show`} onClick={() => setTarget('NO_SHOW')}>
            <IconUserOff size={16} />
          </ActionIcon>
        </Tooltip>
        <Tooltip label="Cancel session">
          <ActionIcon variant="light" color="orange" size={size === 'xs' ? 'md' : 'lg'}
            aria-label={`Cancel session with ${session.memberName}`} onClick={() => setTarget('CANCELLED')}>
            <IconX size={16} />
          </ActionIcon>
        </Tooltip>
      </Group>
      <Modal opened={target != null} onClose={close} title={copy?.title} centered>
        {copy && target && (
          <Stack>
            <Text size="sm">
              {session.memberName} · {dayjs(session.startsAt).format('ddd DD MMM, HH:mm')}–{dayjs(session.endsAt).format('HH:mm')}
            </Text>
            <Text size="xs" c="dimmed">{copy.help}</Text>
            <Textarea label="Notes (optional)" autosize minRows={2} maxLength={1000} value={notes}
              onChange={(e) => setNotes(e.currentTarget.value)} />
            {update.error && <Alert color="red">{errorMessage(update.error)}</Alert>}
            <Group justify="flex-end">
              <Button variant="default" onClick={close}>Back</Button>
              <Button color={copy.color} loading={update.isPending} onClick={() => update.mutate(target)}>{copy.button}</Button>
            </Group>
          </Stack>
        )}
      </Modal>
    </>
  );
}

/** Weekly availability, grouped by weekday. */
export function AvailabilityList({ slots }: { slots: AvailabilitySlot[] }) {
  if (slots.length === 0) return <Text size="sm" c="dimmed">No availability configured.</Text>;
  return (
    <Stack gap={6}>
      {WEEKDAYS.map((d, i) => {
        const day = slots.filter((s) => s.dayOfWeek === i + 1).sort((a, b) => a.startTime.localeCompare(b.startTime));
        return (
          <Group key={d} gap="xs" wrap="nowrap" align="flex-start">
            <Text size="sm" fw={600} w={36}>{d}</Text>
            {day.length === 0 ? (
              <Text size="sm" c="dimmed">Off</Text>
            ) : (
              <Group gap={4}>
                {day.map((s) => (
                  <Badge key={`${s.startTime}-${s.branchId}`} variant="light" radius="sm">
                    {hhmm(s.startTime)}–{hhmm(s.endTime)}
                  </Badge>
                ))}
              </Group>
            )}
          </Group>
        );
      })}
    </Stack>
  );
}

/** Books a session with a client; shows the API's clash (409) / outside-availability (422) messages. */
export function BookSessionModal({ trainerId, opened, onClose, memberId, clients, availability }: {
  trainerId: number;
  opened: boolean;
  onClose: () => void;
  /** fixed member, or pick from `clients` */
  memberId?: number;
  clients?: TrainerAssignment[];
  availability?: AvailabilitySlot[];
}) {
  const [picked, setPicked] = useState<string | null>(null);
  const [startsAt, setStartsAt] = useState<Date | null>(null);
  const [duration, setDuration] = useState<number | string>(60);
  const [notes, setNotes] = useState('');
  const target = memberId ?? (picked ? Number(picked) : null);
  const book = useApiMutation<void, TrainingSession>({
    request: () => ({
      path: `/api/v1/trainers/${trainerId}/sessions`,
      body: {
        memberId: target,
        startsAt: dayjs(startsAt).toISOString(),
        durationMinutes: Number(duration),
        notes: notes.trim() || undefined,
      },
    }),
    invalidate: [`/api/v1/trainers/${trainerId}`, '/api/v1/dashboard'],
    successMessage: (s) => `Session booked for ${dayjs(s.startsAt).format('ddd DD MMM, HH:mm')}`,
    silentError: true,
    onSuccess: () => close(),
  });
  const close = () => {
    setStartsAt(null);
    setNotes('');
    setPicked(null);
    book.reset();
    onClose();
  };
  const durationNum = Number(duration);
  const inFuture = startsAt != null && dayjs(startsAt).isAfter(dayjs());
  const durationOk = Number.isInteger(durationNum) && durationNum >= 15 && durationNum <= 180;
  const dow = startsAt ? ((dayjs(startsAt).day() + 6) % 7) + 1 : null;
  const daySlots = dow != null && availability ? availability.filter((s) => s.dayOfWeek === dow) : [];

  return (
    <Modal opened={opened} onClose={close} title="Book a training session" centered>
      <Stack>
        {memberId == null && (
          <Select
            label="Client"
            placeholder="Choose a client"
            searchable
            withAsterisk
            value={picked}
            onChange={setPicked}
            data={(clients ?? []).filter((c) => c.status === 'ACTIVE')
              .map((c) => ({ value: String(c.memberId), label: `${c.memberName} · ${c.memberCode}` }))}
            nothingFoundMessage="No active clients"
          />
        )}
        <DateTimePicker
          label="Starts at"
          placeholder="Pick date and time"
          withAsterisk
          value={startsAt}
          onChange={setStartsAt}
          minDate={new Date()}
          valueFormat="ddd DD MMM YYYY, HH:mm"
          error={startsAt && !inFuture ? 'Must be in the future' : undefined}
        />
        {startsAt && availability && (
          <Text size="xs" c={daySlots.length ? 'dimmed' : 'orange'}>
            {daySlots.length
              ? `Your availability on ${WEEKDAYS[(dow ?? 1) - 1]}: ${daySlots.map((s) => `${hhmm(s.startTime)}–${hhmm(s.endTime)}`).join(', ')}`
              : `You have no availability on ${WEEKDAYS[(dow ?? 1) - 1]}`}
          </Text>
        )}
        <NumberInput label="Duration (minutes)" min={15} max={180} step={15} allowDecimal={false} value={duration}
          onChange={setDuration} error={!durationOk ? 'Between 15 and 180 minutes' : undefined} />
        <Textarea label="Notes (optional)" autosize minRows={2} maxLength={1000} value={notes}
          onChange={(e) => setNotes(e.currentTarget.value)} />
        {book.error && (
          <Alert color="red" title="Could not book">
            {book.error instanceof ApiError && book.error.status === 409
              ? `This time clashes with another session for you or the member. (${errorMessage(book.error)})`
              : errorMessage(book.error)}
          </Alert>
        )}
        <Group justify="flex-end">
          <Button variant="default" onClick={close}>Cancel</Button>
          <Button leftSection={<IconCalendarPlus size={16} />} disabled={!target || !inFuture || !durationOk}
            loading={book.isPending} onClick={() => book.mutate()}>
            Book session
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}

// ---------------------------------------------------------------------------------------------

/** Monday of the week containing d. */
function mondayOf(d: Dayjs): Dayjs {
  return d.startOf('day').subtract((d.day() + 6) % 7, 'day');
}

function SessionCard({ trainerId, session }: { trainerId: number; session: TrainingSession }) {
  return (
    <Paper withBorder radius="sm" p="xs">
      <Group justify="space-between" gap={4} wrap="nowrap" align="flex-start">
        <Text size="xs" fw={700}>
          {dayjs(session.startsAt).format('HH:mm')}–{dayjs(session.endsAt).format('HH:mm')}
        </Text>
        <StatusBadge value={session.status} size="xs" />
      </Group>
      <Text size="sm" fw={500} component={Link} to={`/trainer/clients/${session.memberId}`}
        style={{ textDecoration: 'none', color: 'inherit' }} lineClamp={1}>
        {session.memberName}
      </Text>
      {session.notes && <Text size="xs" c="dimmed" lineClamp={2}>{session.notes}</Text>}
      {session.status === 'SCHEDULED' && (
        <Group mt={6}>
          <SessionActions trainerId={trainerId} session={session} />
        </Group>
      )}
    </Paper>
  );
}

export default function TrainerSchedulePage() {
  const { user } = useAuth();
  const trainerId = user?.trainerId ?? null;
  const [weekStart, setWeekStart] = useState(() => mondayOf(dayjs()));
  const [booking, setBooking] = useState(false);
  const from = weekStart.format('YYYY-MM-DD');
  const to = weekStart.add(6, 'day').format('YYYY-MM-DD');

  const schedule = useApiQuery<TrainingSession[]>(trainerId ? `/api/v1/trainers/${trainerId}/schedule` : null, { from, to });
  const trainer = useApiQuery<Trainer>(trainerId ? `/api/v1/trainers/${trainerId}` : null);
  const performance = useApiQuery<TrainerPerformance>(trainerId ? `/api/v1/trainers/${trainerId}/performance` : null);
  const clients = useApiQuery<TrainerAssignment[]>(trainerId ? `/api/v1/trainers/${trainerId}/clients` : null);
  // group classes this trainer teaches in the same week
  const classes = useApiQuery<Page<ClassSession>>(trainerId ? '/api/v1/classes/sessions' : null, { mine: true, from, to, size: 100 });

  const dayClasses = (key: string) =>
    (classes.data?.content ?? []).filter((c) => dayjs(c.startsAt).format('YYYY-MM-DD') === key);

  const byDay = useMemo(() => {
    const map = new Map<string, TrainingSession[]>();
    (schedule.data ?? []).forEach((s) => {
      const key = dayjs(s.startsAt).format('YYYY-MM-DD');
      map.set(key, [...(map.get(key) ?? []), s]);
    });
    map.forEach((list) => list.sort((a, b) => a.startsAt.localeCompare(b.startsAt)));
    return map;
  }, [schedule.data]);

  if (!trainerId) {
    return (
      <>
        <PageHeader title="Schedule" />
        <Alert color="yellow">Your account is not linked to a trainer profile.</Alert>
      </>
    );
  }

  const sessions = schedule.data ?? [];
  const scheduled = sessions.filter((s) => s.status === 'SCHEDULED').length;
  const perf = performance.data;
  const today = dayjs().format('YYYY-MM-DD');
  const slots = trainer.data?.availability ?? [];

  return (
    <>
      <PageHeader
        title="Schedule"
        subtitle={`${weekStart.format('DD MMM')} – ${weekStart.add(6, 'day').format('DD MMM YYYY')} · ${sessions.length} session${sessions.length === 1 ? '' : 's'}, ${scheduled} upcoming`}
        actions={
          <>
            <Group gap={4}>
              <ActionIcon variant="default" size="lg" aria-label="Previous week" onClick={() => setWeekStart((w) => w.subtract(7, 'day'))}>
                <IconChevronLeft size={18} />
              </ActionIcon>
              <Button variant="default" onClick={() => setWeekStart(mondayOf(dayjs()))}>This week</Button>
              <ActionIcon variant="default" size="lg" aria-label="Next week" onClick={() => setWeekStart((w) => w.add(7, 'day'))}>
                <IconChevronRight size={18} />
              </ActionIcon>
            </Group>
            <Button leftSection={<IconCalendarPlus size={16} />} onClick={() => setBooking(true)}>Book session</Button>
          </>
        }
      />
      <Stack gap="lg">
        <QueryState isLoading={schedule.isLoading} error={schedule.error}>
          <SimpleGrid cols={{ base: 1, sm: 2, md: 4, xl: 7 }} spacing="sm">
            {Array.from({ length: 7 }, (_, i) => {
              const day = weekStart.add(i, 'day');
              const key = day.format('YYYY-MM-DD');
              const list = byDay.get(key) ?? [];
              const daySlots = slots.filter((s) => s.dayOfWeek === i + 1);
              const isToday = key === today;
              return (
                <Paper key={key} withBorder radius="md" p="sm"
                  style={isToday ? { borderColor: 'var(--mantine-color-teal-6)', borderWidth: 2 } : undefined}>
                  <Group justify="space-between" mb={4}>
                    <Text fw={700} c={isToday ? 'teal' : undefined}>{WEEKDAYS[i]} {day.format('DD')}</Text>
                    {list.length > 0 && <Badge size="sm" variant="light" color="gray">{list.length}</Badge>}
                  </Group>
                  <Text size="xs" c="dimmed" mb="xs">
                    {daySlots.length ? daySlots.map((s) => `${hhmm(s.startTime)}–${hhmm(s.endTime)}`).join(', ') : 'Not available'}
                  </Text>
                  <Stack gap={6}>
                    {list.length === 0 && dayClasses(key).length === 0 ? (
                      <Text size="xs" c="dimmed" ta="center" py="sm">No sessions</Text>
                    ) : (
                      list.map((s) => <SessionCard key={s.id} trainerId={trainerId} session={s} />)
                    )}
                    {dayClasses(key).map((c) => (
                      <Paper key={`class-${c.id}`} component={Link} to={`/classes/${c.id}`} withBorder radius="sm" p={6}
                        style={{ textDecoration: 'none', color: 'inherit', opacity: c.status === 'CANCELLED' ? 0.55 : 1,
                          borderLeft: '3px solid var(--mantine-color-indigo-5)' }}>
                        <Group justify="space-between" gap={4} wrap="nowrap">
                          <Text size="xs" fw={700}>{dayjs(c.startsAt).format('HH:mm')} · {c.classTypeName}</Text>
                          <Badge size="xs" variant="light" color={c.status === 'CANCELLED' ? 'red' : 'indigo'}>
                            {c.status === 'CANCELLED' ? 'Cancelled' : `${c.booked}/${c.capacity}`}
                          </Badge>
                        </Group>
                        <Text size="xs" c="dimmed">Group class{c.room ? ` · ${c.room}` : ''}{c.waitlisted ? ` · ${c.waitlisted} waitlisted` : ''}</Text>
                      </Paper>
                    ))}
                  </Stack>
                </Paper>
              );
            })}
          </SimpleGrid>
        </QueryState>

        <Grid gutter="md">
          <Grid.Col span={{ base: 12, md: 4 }}>
            <Paper withBorder radius="md" p="md" h="100%">
              <Title order={4} mb="sm">Weekly availability</Title>
              <QueryState isLoading={trainer.isLoading} error={trainer.error}>
                <AvailabilityList slots={slots} />
                <Text size="xs" c="dimmed" mt="sm">Times are in the branch's local time. Ask a manager to change your availability.</Text>
              </QueryState>
            </Paper>
          </Grid.Col>
          <Grid.Col span={{ base: 12, md: 8 }}>
            <Paper withBorder radius="md" p="md" h="100%">
              <Title order={4} mb={4}>Performance</Title>
              <Text size="xs" c="dimmed" mb="sm">
                {perf ? `${dayjs(perf.from).format('DD MMM')} – ${dayjs(perf.to).format('DD MMM YYYY')} (last 30 days)` : 'Last 30 days'}
              </Text>
              <QueryState isLoading={performance.isLoading} error={performance.error}>
                {perf && (
                  <Stack>
                    <SimpleGrid cols={{ base: 1, xs: 3 }}>
                      <StatCard label="Active clients" value={perf.activeClients} icon={<IconUsers size={20} />} />
                      <StatCard label="Completion rate" value={pct(perf.completionRate)} icon={<IconPercentage size={20} />} color="indigo" />
                      <StatCard label="No-show rate" value={pct(perf.noShowRate)} icon={<IconUserOff size={20} />}
                        color={perf.noShowRate > 0.15 ? 'red' : 'orange'} />
                    </SimpleGrid>
                    <Group gap="xs">
                      {(Object.entries(perf.sessionsByStatus) as [SessionStatus, number][]).map(([status, n]) => (
                        <Group key={status} gap={4}>
                          <StatusBadge value={status} />
                          <Text size="sm" fw={600}>{n}</Text>
                        </Group>
                      ))}
                    </Group>
                  </Stack>
                )}
              </QueryState>
            </Paper>
          </Grid.Col>
        </Grid>
      </Stack>

      <BookSessionModal
        trainerId={trainerId}
        opened={booking}
        onClose={() => setBooking(false)}
        clients={clients.data}
        availability={slots}
      />
    </>
  );
}
