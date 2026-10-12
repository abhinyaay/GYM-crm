import {
  Alert, Badge, Button, Card, Group, Modal, Paper, Select, SimpleGrid, Stack, Tabs, Text, Tooltip,
} from '@mantine/core';
import { IconAlertTriangle, IconCalendarEvent, IconClock, IconDoor, IconTicket, IconUser } from '@tabler/icons-react';
import dayjs from 'dayjs';
import { useState } from 'react';
import type { ClassBooking, ClassCredits, ClassSession, ClassType } from '@/api/classes';
import type { Page } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { Pager } from '@/components/Pager';
import { QueryState } from '@/components/QueryState';
import { StatCard } from '@/components/StatCard';
import { StatusBadge } from '@/components/StatusBadge';
import { dateTime, time } from '@/lib/format';
import { errorMessage } from '@/lib/notify';
import { useApiMutation, useApiQuery } from '@/lib/useApi';
import { BookingBadge, CategoryBadge, CLASSES_API, classWhen, SeatBar } from '../classes/ClassesPage';

const coach = (name: string) => name.replace(/\s*\(Coach\)$/, '');

/** What the member is about to cancel: a booking id plus the class times needed for the cutoff warning. */
interface CancelTarget {
  bookingId: number;
  status: 'BOOKED' | 'WAITLISTED';
  classTypeName: string;
  startsAt: string;
  endsAt: string;
  cancelCutoffAt: string;
}

function CancelBookingModal({ target, onClose }: { target: CancelTarget | null; onClose: () => void }) {
  const cancel = useApiMutation<number, ClassBooking>({
    request: (id) => ({ path: `${CLASSES_API}/bookings/${id}/cancel` }),
    invalidate: [CLASSES_API],
    successMessage: (b) => (b.status === 'LATE_CANCELLED' ? 'Booking cancelled (late: the credit is used)' : 'Booking cancelled'),
    silentError: true,
    onSuccess: onClose,
  });
  const late = target != null && target.status === 'BOOKED' && dayjs().isAfter(dayjs(target.cancelCutoffAt));
  return (
    <Modal opened={target != null} onClose={() => { cancel.reset(); onClose(); }} title="Cancel booking">
      {target && (
        <Stack gap="sm">
          <Text size="sm">
            {target.status === 'WAITLISTED' ? 'Leave the waitlist for' : 'Cancel your place in'} <b>{target.classTypeName}</b>,{' '}
            {classWhen(target)}?
          </Text>
          {late ? (
            <Alert color="orange" variant="light" icon={<IconAlertTriangle size={18} />} title="Late cancellation">
              Free cancellation ended at {dateTime(target.cancelCutoffAt)}. Your spot will be released, but this class still counts
              as one of this month&apos;s class credits.
            </Alert>
          ) : target.status === 'BOOKED' ? (
            <Text size="xs" c="dimmed">You are cancelling before {dateTime(target.cancelCutoffAt)}, so the class credit is returned.</Text>
          ) : null}
          {cancel.error && <Alert color="red" variant="light">{errorMessage(cancel.error)}</Alert>}
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose}>Keep booking</Button>
            <Button color="red" loading={cancel.isPending} onClick={() => cancel.mutate(target.bookingId)}>
              {late ? 'Cancel anyway' : 'Cancel booking'}
            </Button>
          </Group>
        </Stack>
      )}
    </Modal>
  );
}

function ClassCard({ session, blocked, onCancel }: { session: ClassSession; blocked: boolean; onCancel: (t: CancelTarget) => void }) {
  const book = useApiMutation<void, ClassBooking>({
    request: () => ({ path: `${CLASSES_API}/sessions/${session.id}/bookings`, body: {} }),
    invalidate: [CLASSES_API],
    successMessage: (b) => (b.status === 'WAITLISTED'
      ? `You're #${b.waitlistPosition} on the waitlist for ${b.classTypeName}`
      : `Booked: ${b.classTypeName}, ${classWhen(b)}`),
  });
  const cancelled = session.status === 'CANCELLED';
  const mine = session.myBookingStatus === 'BOOKED' || session.myBookingStatus === 'WAITLISTED' ? session.myBookingStatus : null;
  const notOpen = dayjs().isBefore(dayjs(session.bookingOpensAt));
  const full = session.spotsLeft === 0;
  const waitlistFull = full && session.waitlisted >= session.waitlistCapacity;

  let action;
  if (cancelled) {
    action = <Text size="xs" c="red">Cancelled{session.cancellationReason ? `: ${session.cancellationReason}` : ''}</Text>;
  } else if (mine && session.myBookingId != null) {
    const bookingId = session.myBookingId;
    action = (
      <Button size="xs" variant="light" color="red" fullWidth
        onClick={() => onCancel({ bookingId, status: mine, classTypeName: session.classTypeName, startsAt: session.startsAt,
          endsAt: session.endsAt, cancelCutoffAt: session.cancelCutoffAt })}>
        {mine === 'WAITLISTED' ? 'Leave waitlist' : 'Cancel booking'}
      </Button>
    );
  } else if (notOpen) {
    action = (
      <Button size="xs" variant="default" fullWidth disabled>
        Opens {dayjs(session.bookingOpensAt).format('ddd D MMM HH:mm')}
      </Button>
    );
  } else {
    action = (
      <Tooltip label="Booking is blocked after repeated no-shows" disabled={!blocked}>
        <Button size="xs" fullWidth color={full ? 'orange' : 'teal'} variant={full ? 'light' : 'filled'}
          disabled={waitlistFull || blocked} loading={book.isPending} onClick={() => book.mutate()}>
          {waitlistFull ? 'Full' : full ? 'Join waitlist' : 'Book'}
        </Button>
      </Tooltip>
    );
  }

  return (
    <Card withBorder radius="md" padding="sm" style={{ opacity: cancelled ? 0.6 : 1 }}>
      <Stack gap={6}>
        <Group justify="space-between" wrap="nowrap" gap={4}>
          <Text fw={700} size="sm"><IconClock size={13} style={{ verticalAlign: -2 }} /> {time(session.startsAt)}–{time(session.endsAt)}</Text>
          {mine ? <BookingBadge status={mine} size="xs" /> : <CategoryBadge category={session.category} />}
        </Group>
        <Text fw={600} lineClamp={1}>{session.classTypeName}</Text>
        <Text size="xs" c="dimmed" lineClamp={1}>
          <IconUser size={11} style={{ verticalAlign: -1 }} /> {coach(session.instructorName)}
          {' · '}
          <IconDoor size={11} style={{ verticalAlign: -1 }} /> {session.branchName}{session.room ? `, ${session.room}` : ''}
        </Text>
        {!cancelled && <SeatBar session={session} />}
        {action}
      </Stack>
    </Card>
  );
}

function Browse({ credits }: { credits: ClassCredits | undefined }) {
  const [typeId, setTypeId] = useState<string | null>(null);
  const [day, setDay] = useState<string | null>(null);
  const [cancelTarget, setCancelTarget] = useState<CancelTarget | null>(null);
  const types = useApiQuery<ClassType[]>(`${CLASSES_API}/types`);
  const from = dayjs().format('YYYY-MM-DD');
  const to = dayjs().add(13, 'day').format('YYYY-MM-DD');
  const sessions = useApiQuery<Page<ClassSession>>(`${CLASSES_API}/sessions`, {
    from: day ?? from, to: day ?? to, classTypeId: typeId ?? undefined, size: 100,
  });
  const days = Array.from({ length: 14 }, (_, i) => dayjs().add(i, 'day'));
  const list = sessions.data?.content ?? [];
  const grouped = days
    .map((d) => ({ day: d, items: list.filter((s) => dayjs(s.startsAt).isSame(d, 'day')) }))
    .filter((g) => g.items.length > 0);
  const blocked = credits?.bookingBlockedUntil != null;

  return (
    <Stack gap="md">
      <Group gap="sm" align="flex-end">
        <Select label="Class" placeholder="All classes" clearable w={220} value={typeId} onChange={setTypeId}
          data={(types.data ?? []).map((t) => ({ value: String(t.id), label: t.name }))} />
        <Select label="Day" placeholder="Next 14 days" clearable w={220} value={day} onChange={setDay}
          data={days.map((d) => ({ value: d.format('YYYY-MM-DD'), label: d.isSame(dayjs(), 'day') ? `Today, ${d.format('D MMM')}` : d.format('ddd D MMM') }))} />
      </Group>
      <QueryState isLoading={sessions.isLoading} error={sessions.error} empty={grouped.length === 0}
        emptyText="No upcoming classes match your filters.">
        <Stack gap="lg">
          {grouped.map((g) => (
            <Stack key={g.day.format('YYYY-MM-DD')} gap="xs">
              <Text fw={700}>{g.day.isSame(dayjs(), 'day') ? 'Today' : g.day.isSame(dayjs().add(1, 'day'), 'day') ? 'Tomorrow' : g.day.format('dddd D MMMM')}</Text>
              <SimpleGrid cols={{ base: 1, xs: 2, md: 3, lg: 4 }} spacing="sm">
                {g.items.map((s) => <ClassCard key={s.id} session={s} blocked={blocked} onCancel={setCancelTarget} />)}
              </SimpleGrid>
            </Stack>
          ))}
        </Stack>
      </QueryState>
      <CancelBookingModal target={cancelTarget} onClose={() => setCancelTarget(null)} />
    </Stack>
  );
}

function MyBookings({ when }: { when: 'UPCOMING' | 'PAST' }) {
  const [page, setPage] = useState(0);
  const [cancelTarget, setCancelTarget] = useState<CancelTarget | null>(null);
  const q = useApiQuery<Page<ClassBooking>>(`${CLASSES_API}/bookings/me`, { when, page, size: 10 });
  const items = q.data?.content ?? [];
  return (
    <QueryState isLoading={q.isLoading} error={q.error} empty={items.length === 0}
      emptyText={when === 'UPCOMING' ? 'No upcoming bookings. Browse the timetable to book a class.' : 'No past classes yet.'}>
      <Stack gap="xs">
        {items.map((b) => {
          const live = (b.status === 'BOOKED' || b.status === 'WAITLISTED') && b.sessionStatus === 'SCHEDULED'
            && dayjs().isBefore(dayjs(b.startsAt));
          return (
            <Paper key={b.id} withBorder p="sm" radius="md">
              <Group justify="space-between" wrap="wrap" gap="xs">
                <Stack gap={2}>
                  <Group gap="xs">
                    <Text fw={600}>{b.classTypeName}</Text>
                    <BookingBadge status={b.status} size="xs" />
                    {b.waitlistPosition != null && <Badge size="xs" variant="outline" color="yellow">#{b.waitlistPosition} on waitlist</Badge>}
                    {b.sessionStatus === 'CANCELLED' && <StatusBadge value="CANCELLED" size="xs" />}
                  </Group>
                  <Text size="sm" c="dimmed">
                    {classWhen(b)} · {coach(b.instructorName)} · {b.branchName}{b.room ? `, ${b.room}` : ''}
                  </Text>
                  {b.promotedAt && <Text size="xs" c="teal">Moved off the waitlist {dateTime(b.promotedAt)}</Text>}
                </Stack>
                {live && (
                  <Button size="xs" variant="light" color="red"
                    onClick={() => setCancelTarget({ bookingId: b.id, status: b.status as 'BOOKED' | 'WAITLISTED',
                      classTypeName: b.classTypeName, startsAt: b.startsAt, endsAt: b.endsAt, cancelCutoffAt: b.cancelCutoffAt })}>
                    {b.status === 'WAITLISTED' ? 'Leave waitlist' : 'Cancel'}
                  </Button>
                )}
              </Group>
            </Paper>
          );
        })}
        <Pager page={q.data} onChange={setPage} />
      </Stack>
      <CancelBookingModal target={cancelTarget} onClose={() => setCancelTarget(null)} />
    </QueryState>
  );
}

export default function MemberClassesPage() {
  const credits = useApiQuery<ClassCredits>(`${CLASSES_API}/credits/me`);
  const c = credits.data;
  const creditsValue = !c ? '—' : !c.includesClasses ? 'Not included' : c.unlimited ? 'Unlimited' : `${c.remaining} left`;
  return (
    <>
      <PageHeader title="Group classes" subtitle="Book yoga, HIIT, spin and more. Classes open for booking up to 7 days ahead." />
      <Stack gap="md">
        <SimpleGrid cols={{ base: 1, sm: 3 }}>
          <StatCard label={`Class credits · ${c ? dayjs(c.month).format('MMMM') : ''}`} value={creditsValue} icon={<IconTicket size={20} />}
            color="indigo" hint={c?.includesClasses && !c.unlimited ? `${c.used} of ${c.creditsPerMonth} used` : c?.planName ?? undefined} />
          <StatCard label="Used this month" value={c ? c.used : '—'} icon={<IconCalendarEvent size={20} />}
            hint="Booked, attended, late-cancelled and no-show classes count" />
          <StatCard label="No-shows (30 days)" value={c ? `${c.recentNoShows} / ${c.noShowThreshold}` : '—'}
            icon={<IconAlertTriangle size={20} />} color={c && c.recentNoShows > 0 ? 'orange' : 'gray'}
            hint={`${c?.noShowThreshold ?? 3} no-shows block booking for 7 days`} />
        </SimpleGrid>
        {c && !c.includesClasses && (
          <Alert color="gray" variant="light" title="Group classes are not part of your plan">
            {c.planName ? `Your ${c.planName} membership does not include classes.` : 'You have no active membership.'} Ask the front desk
            about plans that include classes.
          </Alert>
        )}
        {c?.bookingBlockedUntil && (
          <Alert color="red" variant="light" icon={<IconAlertTriangle size={18} />} title="Booking paused">
            After {c.noShowThreshold} no-shows you can book classes again from {dateTime(c.bookingBlockedUntil)}.
          </Alert>
        )}
        <Tabs defaultValue="browse" keepMounted={false}>
          <Tabs.List mb="md">
            <Tabs.Tab value="browse">Timetable</Tabs.Tab>
            <Tabs.Tab value="upcoming">My bookings</Tabs.Tab>
            <Tabs.Tab value="past">Past classes</Tabs.Tab>
          </Tabs.List>
          <Tabs.Panel value="browse"><Browse credits={c} /></Tabs.Panel>
          <Tabs.Panel value="upcoming"><MyBookings when="UPCOMING" /></Tabs.Panel>
          <Tabs.Panel value="past"><MyBookings when="PAST" /></Tabs.Panel>
        </Tabs>
      </Stack>
    </>
  );
}
