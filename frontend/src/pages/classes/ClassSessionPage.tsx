import {
  ActionIcon, Alert, Anchor, Button, Group, Modal, NumberInput, Paper, Select, SimpleGrid, Stack, Table, Text, TextInput, Textarea,
  Tooltip,
} from '@mantine/core';
import { DateInput } from '@mantine/dates';
import { useForm, type UseFormReturnType } from '@mantine/form';
import {
  IconArrowLeft, IconCalendarOff, IconCheck, IconHourglass, IconPencil, IconUserCheck, IconUserPlus, IconUsers, IconUserX, IconX,
} from '@tabler/icons-react';
import dayjs from 'dayjs';
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import type {
  BookingStatus, ClassBooking, ClassCredits, ClassSession, ClassSessionDetail, RosterEntry, SeriesCancelResponse,
  SessionUpdateRequest,
} from '@/api/classes';
import type { Page, Trainer } from '@/api/types';
import { MemberSearch } from '@/components/MemberSearch';
import { PageHeader } from '@/components/PageHeader';
import { QueryState } from '@/components/QueryState';
import { StatCard } from '@/components/StatCard';
import { StatusBadge } from '@/components/StatusBadge';
import { dateTime, label, time } from '@/lib/format';
import { errorMessage } from '@/lib/notify';
import { useApiMutation, useApiQuery } from '@/lib/useApi';
import { BookingBadge, CategoryBadge, CLASSES_API, classWhen } from './ClassesPage';

const STATUS_ORDER: Record<BookingStatus, number> = {
  BOOKED: 0, ATTENDED: 0, NO_SHOW: 0, WAITLISTED: 1, LATE_CANCELLED: 2, CANCELLED: 3,
};

// ---------------------------------------------------------------------------------------------
// Edit / cancel
// ---------------------------------------------------------------------------------------------

interface EditValues {
  instructorId: string | null;
  room: string;
  startsAt: Date | null;
  durationMinutes: number | string;
  capacity: number | string;
  waitlistCapacity: number | string;
  bookingOpensHours: number | string;
  cancelCutoffMinutes: number | string;
}

function EditSessionModal({ session, onClose }: { session: ClassSession; onClose: () => void }) {
  const trainers = useApiQuery<Page<Trainer>>('/api/v1/trainers', { branchId: session.branchId, size: 100 });
  const form = useForm<EditValues>({
    initialValues: {
      instructorId: String(session.instructorId), room: session.room ?? '', startsAt: new Date(session.startsAt),
      durationMinutes: session.durationMinutes, capacity: session.capacity, waitlistCapacity: session.waitlistCapacity,
      bookingOpensHours: session.bookingOpensHours, cancelCutoffMinutes: session.cancelCutoffMinutes,
    },
    validate: {
      instructorId: (v) => (v ? null : 'Required'),
      startsAt: (v) => (!v ? 'Required' : dayjs(v).isBefore(dayjs()) ? 'Must be in the future' : null),
      durationMinutes: (v) => (Number(v) >= 15 && Number(v) <= 180 ? null : '15-180'),
      capacity: (v) => (Number(v) < session.booked ? `At least ${session.booked} (already booked)` : Number(v) > 200 ? 'Max 200' : null),
      waitlistCapacity: (v) => (Number(v) >= 0 && Number(v) <= 100 ? null : '0-100'),
      bookingOpensHours: (v) => (Number(v) >= 1 && Number(v) <= 720 ? null : '1-720'),
      cancelCutoffMinutes: (v) => (Number(v) >= 0 && Number(v) <= 1440 ? null : '0-1440'),
    },
  });
  const save = useApiMutation<EditValues, ClassSession>({
    request: (v) => {
      const body: SessionUpdateRequest = {
        instructorId: Number(v.instructorId), room: v.room.trim() || null,
        startsAt: dayjs(v.startsAt).second(0).millisecond(0).toISOString(), durationMinutes: Number(v.durationMinutes),
        capacity: Number(v.capacity), waitlistCapacity: Number(v.waitlistCapacity), bookingOpensHours: Number(v.bookingOpensHours),
        cancelCutoffMinutes: Number(v.cancelCutoffMinutes),
      };
      return { path: `${CLASSES_API}/sessions/${session.id}`, method: 'PUT', body };
    },
    invalidate: [CLASSES_API],
    successMessage: 'Class updated',
    silentError: true,
    onSuccess: onClose,
  });
  return (
    <Modal opened onClose={onClose} title={`Edit ${session.classTypeName}`} size="lg">
      <form onSubmit={form.onSubmit((v) => save.mutate(v))}>
        <Stack gap="sm">
          <SimpleGrid cols={{ base: 1, sm: 2 }}>
            <Select label="Instructor" withAsterisk searchable
              data={(trainers.data?.content ?? []).filter((t) => t.status === 'ACTIVE')
                .map((t) => ({ value: String(t.id), label: t.fullName.replace(/\s*\(Coach\)$/, '') }))}
              {...form.getInputProps('instructorId')} />
            <TextInput label="Room" maxLength={60} {...form.getInputProps('room')} />
            <DateTimePickerField form={form} />
            <NumberInput label="Duration (min)" min={15} max={180} allowDecimal={false} {...form.getInputProps('durationMinutes')} />
            <NumberInput label="Capacity" min={1} max={200} allowDecimal={false} {...form.getInputProps('capacity')} />
            <NumberInput label="Waitlist" min={0} max={100} allowDecimal={false} {...form.getInputProps('waitlistCapacity')} />
            <NumberInput label="Booking opens (h before)" min={1} max={720} allowDecimal={false} {...form.getInputProps('bookingOpensHours')} />
            <NumberInput label="Free cancel until (min before)" min={0} max={1440} allowDecimal={false}
              {...form.getInputProps('cancelCutoffMinutes')} />
          </SimpleGrid>
          <Text size="xs" c="dimmed">
            Booked and waitlisted members are notified when the time changes. Raising the capacity moves waitlisted members into the new
            seats in order.
          </Text>
          {save.error && <Alert color="red" variant="light">{errorMessage(save.error)}</Alert>}
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose}>Cancel</Button>
            <Button type="submit" loading={save.isPending}>Save changes</Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}

/** Date + time inputs for the class start. */
function DateTimePickerField({ form }: { form: UseFormReturnType<EditValues> }) {
  const value = form.values.startsAt;
  const hhmm = value ? dayjs(value).format('HH:mm') : '';
  const setPart = (date: Date | null, t: string) => {
    if (!date) {
      form.setFieldValue('startsAt', null);
      return;
    }
    const [h, m] = (t || '00:00').split(':').map(Number);
    form.setFieldValue('startsAt', dayjs(date).hour(h).minute(m).second(0).millisecond(0).toDate());
  };
  return (
    <Group grow gap="xs" align="flex-start">
      <DateInput label="Date" withAsterisk value={value} minDate={new Date()} valueFormat="ddd DD MMM YYYY"
        onChange={(d) => setPart(d, hhmm)} error={form.errors.startsAt} />
      <TextInput label="Time" type="time" value={hhmm} onChange={(e) => setPart(value, e.currentTarget.value)} />
    </Group>
  );
}

function CancelSessionModal({ session, onClose }: { session: ClassSession; onClose: () => void }) {
  const [reason, setReason] = useState('');
  const [wholeSeries, setWholeSeries] = useState(false);
  const cancel = useApiMutation<void, ClassSession | SeriesCancelResponse>({
    request: () => wholeSeries && session.seriesId
      ? { path: `${CLASSES_API}/series/${session.seriesId}/cancel`, body: { from: dayjs(session.startsAt).format('YYYY-MM-DD'), reason: reason.trim() } }
      : { path: `${CLASSES_API}/sessions/${session.id}/cancel`, body: { reason: reason.trim() } },
    invalidate: [CLASSES_API],
    successMessage: (r) => ('cancelledSessions' in r ? `${r.cancelledSessions} classes cancelled` : 'Class cancelled'),
    silentError: true,
    onSuccess: onClose,
  });
  const booked = session.booked + session.waitlisted;
  return (
    <Modal opened onClose={onClose} title="Cancel class">
      <Stack gap="sm">
        <Alert color="orange" variant="light">
          {booked > 0
            ? `${booked} booked or waitlisted member${booked === 1 ? '' : 's'} will be notified. Their bookings are released and no class credit is used.`
            : 'Nobody has booked this class yet.'}
        </Alert>
        <Textarea label="Reason (shown to members)" withAsterisk maxLength={300} autosize minRows={2} value={reason}
          onChange={(e) => setReason(e.currentTarget.value)} />
        {session.seriesId && (
          <Select label="Scope" allowDeselect={false} value={wholeSeries ? 'series' : 'one'}
            onChange={(v) => setWholeSeries(v === 'series')}
            data={[
              { value: 'one', label: 'Only this class' },
              { value: 'series', label: `This and all later classes of the series (from ${dayjs(session.startsAt).format('D MMM')})` },
            ]} />
        )}
        {cancel.error && <Alert color="red" variant="light">{errorMessage(cancel.error)}</Alert>}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>Keep class</Button>
          <Button color="red" disabled={!reason.trim()} loading={cancel.isPending} onClick={() => cancel.mutate()}>Cancel class</Button>
        </Group>
      </Stack>
    </Modal>
  );
}

// ---------------------------------------------------------------------------------------------
// Book a member (front desk)
// ---------------------------------------------------------------------------------------------

function BookMemberPanel({ session }: { session: ClassSession }) {
  const [memberId, setMemberId] = useState<number | null>(null);
  const credits = useApiQuery<ClassCredits>(memberId ? `${CLASSES_API}/members/${memberId}/credits` : null,
    { month: dayjs(session.startsAt).format('YYYY-MM') });
  const book = useApiMutation<number, ClassBooking>({
    request: (id) => ({ path: `${CLASSES_API}/sessions/${session.id}/bookings`, body: { memberId: id } }),
    invalidate: [CLASSES_API],
    successMessage: (b) => (b.status === 'WAITLISTED' ? `${b.memberName} waitlisted (#${b.waitlistPosition})` : `${b.memberName} booked`),
    silentError: true,
    onSuccess: () => setMemberId(null),
  });
  const c = credits.data;
  const full = session.spotsLeft === 0;
  const waitlistFull = full && session.waitlisted >= session.waitlistCapacity;
  return (
    <Paper withBorder p="md">
      <Text fw={600} mb="xs">Book a member</Text>
      <Group align="flex-end" gap="sm" wrap="wrap">
        <MemberSearch value={memberId} onChange={(id) => { setMemberId(id); book.reset(); }} w={320} label="Member" />
        <Button leftSection={<IconUserPlus size={16} />} disabled={!memberId || waitlistFull} loading={book.isPending}
          onClick={() => memberId && book.mutate(memberId)}>
          {full ? 'Add to waitlist' : 'Book seat'}
        </Button>
      </Group>
      {c && (
        <Text size="xs" c="dimmed" mt={6}>
          {c.planName ?? 'No membership'} ·{' '}
          {!c.includesClasses ? 'classes not included' : c.unlimited ? 'unlimited classes' : `${c.remaining} of ${c.creditsPerMonth} credits left in ${dayjs(c.month).format('MMMM')}`}
          {c.bookingBlockedUntil && ` · blocked until ${dateTime(c.bookingBlockedUntil)} (no-shows)`}
        </Text>
      )}
      {waitlistFull && <Text size="xs" c="orange" mt={6}>The class and its waitlist are full.</Text>}
      {book.error && <Alert color="red" variant="light" mt="sm">{errorMessage(book.error)}</Alert>}
    </Paper>
  );
}

// ---------------------------------------------------------------------------------------------
// Roster
// ---------------------------------------------------------------------------------------------

function RosterTable({ detail }: { detail: ClassSessionDetail }) {
  const { session } = detail;
  const started = dayjs(session.startsAt).isBefore(dayjs());
  const lateNow = dayjs().isAfter(dayjs(session.cancelCutoffAt));
  const [cancelling, setCancelling] = useState<RosterEntry | null>(null);
  const attendance = useApiMutation<{ bookingId: number; status: 'ATTENDED' | 'NO_SHOW' }, ClassBooking>({
    request: ({ bookingId, status }) => ({ path: `${CLASSES_API}/bookings/${bookingId}/attendance`, method: 'PATCH', body: { status } }),
    invalidate: [CLASSES_API],
    successMessage: (b) => `${b.memberName}: ${label(b.status).toLowerCase()}`,
  });
  const cancel = useApiMutation<number, ClassBooking>({
    request: (id) => ({ path: `${CLASSES_API}/bookings/${id}/cancel` }),
    invalidate: [CLASSES_API],
    successMessage: (b) => `${b.memberName}: ${label(b.status).toLowerCase()}`,
    onSuccess: () => setCancelling(null),
  });
  const roster = [...(detail.roster ?? [])].sort((a, b) => STATUS_ORDER[a.status] - STATUS_ORDER[b.status]
    || (a.waitlistPosition ?? 0) - (b.waitlistPosition ?? 0) || a.bookedAt.localeCompare(b.bookedAt));
  const canAttend = detail.canMarkAttendance && started && session.status !== 'CANCELLED';
  const canCancel = detail.canBookForMembers && !started && session.status === 'SCHEDULED';

  return (
    <Paper withBorder p="md">
      <Group justify="space-between" mb="xs">
        <Text fw={600}>Roster</Text>
        {detail.canMarkAttendance && !started && session.status === 'SCHEDULED' && (
          <Text size="xs" c="dimmed">Attendance can be recorded once the class starts.</Text>
        )}
        {session.status === 'SCHEDULED' && started && (
          <Text size="xs" c="dimmed">Unmarked seats are settled automatically 30 min after the class (gym check-in = attended).</Text>
        )}
      </Group>
      <QueryState isLoading={false} error={null} empty={roster.length === 0} emptyText="No bookings yet.">
        <Table.ScrollContainer minWidth={720}>
          <Table verticalSpacing="xs" highlightOnHover>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Member</Table.Th>
                <Table.Th>Status</Table.Th>
                <Table.Th>Booked</Table.Th>
                <Table.Th>Updated</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {roster.map((r) => (
                <Table.Tr key={r.bookingId} style={{ opacity: r.status === 'CANCELLED' ? 0.6 : 1 }}>
                  <Table.Td>
                    <Anchor component={Link} to={`/members/${r.memberId}`} size="sm" fw={600}>{r.memberName}</Anchor>
                    <Text size="xs" c="dimmed" ff="monospace">{r.memberCode}</Text>
                  </Table.Td>
                  <Table.Td>
                    <Group gap={6}>
                      <BookingBadge status={r.status} />
                      {r.waitlistPosition != null && <Text size="xs" c="dimmed">#{r.waitlistPosition}</Text>}
                      {r.promotedAt && <Tooltip label={`Promoted from waitlist ${dateTime(r.promotedAt)}`}><IconHourglass size={14} /></Tooltip>}
                    </Group>
                  </Table.Td>
                  <Table.Td><Text size="xs">{dateTime(r.bookedAt)}</Text></Table.Td>
                  <Table.Td>
                    <Text size="xs" c="dimmed">
                      {r.attendedAt ? `Attended ${time(r.attendedAt)}` : r.cancelledAt ? `Cancelled ${dateTime(r.cancelledAt)}` : '—'}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Group gap={4} justify="flex-end" wrap="nowrap">
                      {canAttend && ['BOOKED', 'ATTENDED', 'NO_SHOW'].includes(r.status) && (
                        <>
                          <Tooltip label="Attended">
                            <ActionIcon variant={r.status === 'ATTENDED' ? 'filled' : 'light'} color="teal" aria-label={`Mark ${r.memberName} attended`}
                              disabled={r.status === 'ATTENDED'} loading={attendance.isPending}
                              onClick={() => attendance.mutate({ bookingId: r.bookingId, status: 'ATTENDED' })}>
                              <IconCheck size={16} />
                            </ActionIcon>
                          </Tooltip>
                          <Tooltip label="No-show">
                            <ActionIcon variant={r.status === 'NO_SHOW' ? 'filled' : 'light'} color="red" aria-label={`Mark ${r.memberName} no-show`}
                              disabled={r.status === 'NO_SHOW'} loading={attendance.isPending}
                              onClick={() => attendance.mutate({ bookingId: r.bookingId, status: 'NO_SHOW' })}>
                              <IconX size={16} />
                            </ActionIcon>
                          </Tooltip>
                        </>
                      )}
                      {canCancel && (r.status === 'BOOKED' || r.status === 'WAITLISTED') && (
                        <Button size="compact-xs" variant="subtle" color="red" onClick={() => setCancelling(r)}>Cancel</Button>
                      )}
                    </Group>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      </QueryState>

      <Modal opened={cancelling != null} onClose={() => setCancelling(null)} title="Cancel booking">
        {cancelling && (
          <Stack gap="sm">
            <Text size="sm">Cancel {cancelling.memberName}&apos;s {cancelling.status === 'WAITLISTED' ? 'waitlist place' : 'seat'}?</Text>
            {cancelling.status === 'BOOKED' && lateNow && (
              <Alert color="orange" variant="light">
                The free cancellation window closed at {dateTime(session.cancelCutoffAt)}: this is a late cancellation and still
                uses one of the member&apos;s class credits.
              </Alert>
            )}
            {cancelling.status === 'BOOKED' && session.waitlisted > 0 && (
              <Text size="xs" c="dimmed">The first member on the waitlist will get the seat and be notified.</Text>
            )}
            <Group justify="flex-end">
              <Button variant="default" onClick={() => setCancelling(null)}>Keep</Button>
              <Button color="red" loading={cancel.isPending} onClick={() => cancel.mutate(cancelling.bookingId)}>Cancel booking</Button>
            </Group>
          </Stack>
        )}
      </Modal>
    </Paper>
  );
}

// ---------------------------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------------------------

export default function ClassSessionPage() {
  const { id } = useParams();
  const q = useApiQuery<ClassSessionDetail>(`${CLASSES_API}/sessions/${id}`);
  const [editing, setEditing] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const detail = q.data;
  const s = detail?.session;
  const started = s ? dayjs(s.startsAt).isBefore(dayjs()) : false;
  const editable = !!detail?.canManage && s?.status === 'SCHEDULED' && !started;

  return (
    <QueryState isLoading={q.isLoading} error={q.error}>
      {detail && s && (
        <>
          <Anchor component={Link} to="/classes" size="sm">
            <IconArrowLeft size={14} style={{ verticalAlign: -2 }} /> Timetable
          </Anchor>
          <PageHeader
            title={
              <Group gap="sm">
                {s.classTypeName}
                <StatusBadge value={s.status} />
              </Group>
            }
            subtitle={`${classWhen(s)} · ${s.branchName}${s.room ? ` · ${s.room}` : ''} · with ${s.instructorName.replace(/\s*\(Coach\)$/, '')}`}
            actions={editable && (
              <>
                <Button variant="default" leftSection={<IconPencil size={16} />} onClick={() => setEditing(true)}>Edit</Button>
                <Button color="red" variant="light" leftSection={<IconCalendarOff size={16} />} onClick={() => setCancelling(true)}>
                  Cancel class
                </Button>
              </>
            )}
          />
          <Stack gap="md">
            {s.status === 'CANCELLED' && (
              <Alert color="red" variant="light" title="This class was cancelled">{s.cancellationReason ?? 'No reason given.'}</Alert>
            )}
            <Group gap="xs">
              <CategoryBadge category={s.category} size="sm" />
              <StatusBadge value={s.intensity} />
              <Text size="xs" c="dimmed">
                Booking opens {dateTime(s.bookingOpensAt)} · free cancellation until {dateTime(s.cancelCutoffAt)}
                {s.seriesId && ' · part of a weekly series'}
              </Text>
            </Group>
            <SimpleGrid cols={{ base: 2, md: 4 }}>
              <StatCard label="Booked" value={`${s.booked} / ${s.capacity}`} icon={<IconUsers size={20} />} color="indigo"
                hint={s.spotsLeft > 0 ? `${s.spotsLeft} spots left` : 'Full'} />
              <StatCard label="Waitlist" value={`${s.waitlisted} / ${s.waitlistCapacity}`} icon={<IconHourglass size={20} />} color="orange" />
              <StatCard label="Attended" value={s.attended} icon={<IconUserCheck size={20} />} />
              <StatCard label="No-shows" value={s.noShows} icon={<IconUserX size={20} />} color="red" />
            </SimpleGrid>
            {detail.canBookForMembers && s.status === 'SCHEDULED' && !started && <BookMemberPanel session={s} />}
            <RosterTable detail={detail} />
          </Stack>
          {editing && <EditSessionModal session={s} onClose={() => setEditing(false)} />}
          {cancelling && <CancelSessionModal session={s} onClose={() => setCancelling(false)} />}
        </>
      )}
    </QueryState>
  );
}
