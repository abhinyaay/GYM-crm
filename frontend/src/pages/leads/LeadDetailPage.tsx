import {
  ActionIcon, Alert, Anchor, Badge, Button, Divider, Grid, Group, Modal, Paper, Radio, SegmentedControl, Select,
  SimpleGrid, Stack, Text, Textarea, ThemeIcon, Timeline, Title, Tooltip,
} from '@mantine/core';
import { DateInput, DateTimePicker } from '@mantine/dates';
import {
  IconAlarm, IconArrowBackUp, IconArrowLeft, IconBrandWhatsapp, IconCheck, IconFlag, IconGift, IconLink, IconMail,
  IconMessage, IconNote, IconPencil, IconPhone, IconRoute, IconUserCheck, IconWalk, IconX,
} from '@tabler/icons-react';
import dayjs from 'dayjs';
import { useEffect, useState, type ReactNode } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import {
  LOGGABLE_ACTIVITIES, type LeadActivity, type LeadActivityType, type LeadDetail, type TrialResult,
} from '@/api/leads';
import type { Plan } from '@/api/types';
import { PayInvoiceModal } from '@/components/billing/PayInvoiceModal';
import { MemberSearch } from '@/components/MemberSearch';
import { PageHeader } from '@/components/PageHeader';
import { QueryState } from '@/components/QueryState';
import { date, dateTime, fromNow, label, money } from '@/lib/format';
import { errorMessage } from '@/lib/notify';
import { useApiMutation, useApiQuery } from '@/lib/useApi';
import { LeadFormModal, LeadStatusBadge, LostModal, moveLabel, useLeadOwners, useLeadStatusChange } from './components';

const ACTIVITY_ICON: Record<LeadActivityType, ReactNode> = {
  NOTE: <IconNote size={14} />, CALL: <IconPhone size={14} />, EMAIL: <IconMail size={14} />,
  WHATSAPP: <IconBrandWhatsapp size={14} />, SMS: <IconMessage size={14} />, VISIT: <IconWalk size={14} />,
  TOUR: <IconRoute size={14} />, STATUS_CHANGE: <IconFlag size={14} />, FOLLOW_UP: <IconAlarm size={14} />,
};
const ACTIVITY_COLOR: Partial<Record<LeadActivityType, string>> = {
  CALL: 'blue', WHATSAPP: 'green', EMAIL: 'indigo', SMS: 'cyan', VISIT: 'grape', TOUR: 'violet', STATUS_CHANGE: 'orange',
  FOLLOW_UP: 'yellow',
};

export default function LeadDetailPage() {
  const id = Number(useParams().id);
  const [params, setParams] = useSearchParams();
  const q = useApiQuery<LeadDetail>(`/api/v1/leads/${id}`);
  const d = q.data;
  const [editing, setEditing] = useState(false);
  const [losing, setLosing] = useState(false);
  const [trialOpen, setTrialOpen] = useState(false);
  const [converting, setConverting] = useState(false);
  const [payInvoiceId, setPayInvoiceId] = useState<number | null>(null);
  const change = useLeadStatusChange();

  useEffect(() => {
    if (params.get('trial') && d?.canStartTrial) {
      setTrialOpen(true);
      setParams({}, { replace: true });
    }
  }, [params, d?.canStartTrial, setParams]);

  return (
    <QueryState isLoading={q.isLoading} error={q.error}>
      {d && (
        <>
          <Anchor component={Link} to="/leads" size="sm" c="dimmed">
            <IconArrowLeft size={14} style={{ verticalAlign: -2 }} /> Leads
          </Anchor>
          <PageHeader
            title={<Group gap="sm">{d.lead.fullName}<LeadStatusBadge status={d.lead.status} size="lg" /></Group>}
            subtitle={`${label(d.lead.source)} lead · ${d.lead.branchName} · created ${fromNow(d.lead.createdAt)}`}
            actions={
              <>
                <Button variant="default" leftSection={<IconPencil size={16} />} onClick={() => setEditing(true)}>Edit</Button>
                {d.allowedStatusChanges.filter((s) => s !== 'LOST').map((s) => (
                  <Button key={s} variant="light" loading={change.isPending && change.variables?.status === s}
                    leftSection={s === 'NEW' ? <IconArrowBackUp size={16} /> : undefined}
                    onClick={() => change.mutate({ id, status: s })}>
                    {moveLabel(s)}
                  </Button>
                ))}
                {d.canStartTrial && (
                  <Button color="orange" leftSection={<IconGift size={16} />} onClick={() => setTrialOpen(true)}>Start trial</Button>
                )}
                {d.canConvert && (
                  <Button variant="light" color="teal" leftSection={<IconUserCheck size={16} />} onClick={() => setConverting(true)}>
                    Mark converted
                  </Button>
                )}
                {d.allowedStatusChanges.includes('LOST') && (
                  <Button variant="light" color="red" leftSection={<IconX size={16} />} onClick={() => setLosing(true)}>Mark lost</Button>
                )}
              </>
            }
          />
          <Grid gutter="md">
            <Grid.Col span={{ base: 12, md: 5 }}>
              <Stack>
                <Profile detail={d} />
                <FollowUps detail={d} />
              </Stack>
            </Grid.Col>
            <Grid.Col span={{ base: 12, md: 7 }}>
              <Stack>
                <LogActivity leadId={id} />
                <ActivityTimeline activities={d.activities} />
              </Stack>
            </Grid.Col>
          </Grid>

          <LeadFormModal opened={editing} onClose={() => setEditing(false)} lead={d.lead} />
          <LostModal lead={losing ? d.lead : null} onClose={() => setLosing(false)} />
          <StartTrialModal detail={d} opened={trialOpen} onClose={() => setTrialOpen(false)}
            onStarted={(r) => { if (r.paymentDue && r.invoiceId) setPayInvoiceId(r.invoiceId); }} />
          <ConvertModal leadId={id} branchId={d.lead.branchId} opened={converting} onClose={() => setConverting(false)} />
          <PayInvoiceModal invoiceId={payInvoiceId} opened={payInvoiceId != null} onClose={() => setPayInvoiceId(null)}
            onPaid={() => q.refetch()} />
        </>
      )}
    </QueryState>
  );
}

function Field({ k, children }: { k: string; children: ReactNode }) {
  return (
    <div>
      <Text size="xs" c="dimmed" tt="uppercase" fw={600}>{k}</Text>
      <Text size="sm" component="div">{children}</Text>
    </div>
  );
}

function Profile({ detail }: { detail: LeadDetail }) {
  const l = detail.lead;
  const owners = useLeadOwners(l.branchId);
  const assign = useApiMutation<number | null>({
    request: (ownerUserId) => ({ path: `/api/v1/leads/${l.id}/owner`, method: 'PATCH', body: { ownerUserId } }),
    invalidate: ['/api/v1/leads'],
    successMessage: 'Owner updated',
  });
  const ownerOptions = (owners.data ?? []).map((o) => ({ value: String(o.userId), label: o.fullName }));
  if (l.ownerUserId != null && !ownerOptions.some((o) => o.value === String(l.ownerUserId))) {
    ownerOptions.push({ value: String(l.ownerUserId), label: l.ownerName ?? `User ${l.ownerUserId}` });
  }
  const open = ['NEW', 'CONTACTED', 'TOUR_SCHEDULED', 'TRIAL_ACTIVE'].includes(l.status);
  return (
    <Paper withBorder p="md">
      <Title order={5} mb="sm">Prospect</Title>
      <SimpleGrid cols={2} spacing="sm">
        <Field k="Phone"><Anchor href={`tel:${l.phone}`}>{l.phone}</Anchor></Field>
        <Field k="Email">{l.email ? <Anchor href={`mailto:${l.email}`}>{l.email}</Anchor> : <Text span c="orange" size="sm">Missing</Text>}</Field>
        <Field k="Interest">{label(l.interest)}</Field>
        <Field k="Source">{label(l.source)}</Field>
        <Field k="Created">{dateTime(l.createdAt)}</Field>
        <Field k="Next follow-up">
          <Text span size="sm" c={l.followUpOverdue ? 'red' : undefined}>{l.nextFollowUpAt ? dateTime(l.nextFollowUpAt) : '—'}</Text>
        </Field>
      </SimpleGrid>
      <Select mt="md" label="Owner" placeholder="Unassigned" clearable disabled={!open} data={ownerOptions}
        value={l.ownerUserId == null ? null : String(l.ownerUserId)}
        onChange={(v) => assign.mutate(v ? Number(v) : null)} />
      {l.status === 'TRIAL_ACTIVE' && (
        <Alert mt="md" color="orange" icon={<IconGift size={18} />} title="Trial running">
          Ends {date(l.trialEndsOn)} ({fromNow(l.trialEndsOn ? dayjs(l.trialEndsOn).endOf('day').toISOString() : null)}).
          Buying any membership converts the lead automatically.
        </Alert>
      )}
      {l.status === 'LOST' && l.lostReason && (
        <Alert mt="md" color="gray" title="Lost">Reason: {label(l.lostReason)}</Alert>
      )}
      {l.status === 'CONVERTED' && <Alert mt="md" color="teal" title="Converted">{dateTime(l.convertedAt)}</Alert>}
      {detail.member && (
        <Group mt="md" gap="xs">
          <IconLink size={16} />
          <Text size="sm">Member:</Text>
          <Anchor component={Link} to={`/members/${detail.member.id}`} size="sm" fw={600}>
            {detail.member.fullName} · {detail.member.memberCode}
          </Anchor>
        </Group>
      )}
      {l.notes && (
        <>
          <Divider my="sm" />
          <Text size="sm" style={{ whiteSpace: 'pre-line' }}>{l.notes}</Text>
        </>
      )}
    </Paper>
  );
}

function LogActivity({ leadId }: { leadId: number }) {
  const [type, setType] = useState<LeadActivityType>('CALL');
  const [body, setBody] = useState('');
  const log = useApiMutation<void, LeadActivity>({
    request: () => ({ path: `/api/v1/leads/${leadId}/activities`, body: { type, body: body.trim() } }),
    invalidate: ['/api/v1/leads'],
    successMessage: (a) => `${label(a.type)} logged`,
    onSuccess: () => setBody(''),
  });
  return (
    <Paper withBorder p="md">
      <Title order={5} mb="sm">Log activity</Title>
      <SegmentedControl fullWidth size="xs" value={type} onChange={(v) => setType(v as LeadActivityType)}
        data={LOGGABLE_ACTIVITIES.map((t) => ({ value: t, label: t === 'WHATSAPP' ? 'WhatsApp' : label(t) }))} />
      <Textarea mt="sm" autosize minRows={2} maxLength={2000} value={body} onChange={(e) => setBody(e.currentTarget.value)}
        placeholder={type === 'CALL' ? 'What did they say?' : type === 'NOTE' ? 'Add a note…' : 'Summary'} />
      <Group justify="flex-end" mt="sm">
        <Button size="xs" disabled={!body.trim()} loading={log.isPending} onClick={() => log.mutate()}>Save</Button>
      </Group>
    </Paper>
  );
}

function FollowUps({ detail }: { detail: LeadDetail }) {
  const l = detail.lead;
  const openTasks = detail.activities.filter((a) => a.type === 'FOLLOW_UP' && !a.completedAt)
    .sort((a, b) => (a.dueAt ?? '').localeCompare(b.dueAt ?? ''));
  const [dueAt, setDueAt] = useState<Date | null>(null);
  const [body, setBody] = useState('');
  const open = ['NEW', 'CONTACTED', 'TOUR_SCHEDULED', 'TRIAL_ACTIVE'].includes(l.status);
  const schedule = useApiMutation<void, LeadActivity>({
    request: () => ({ path: `/api/v1/leads/${l.id}/follow-ups`, body: { dueAt: dueAt?.toISOString(), body: body.trim() } }),
    invalidate: ['/api/v1/leads'],
    successMessage: 'Follow-up scheduled',
    onSuccess: () => { setDueAt(null); setBody(''); },
  });
  const complete = useApiMutation<number, LeadActivity>({
    request: (activityId) => ({ path: `/api/v1/leads/${l.id}/follow-ups/${activityId}/complete`, body: {} }),
    invalidate: ['/api/v1/leads'],
    successMessage: 'Follow-up done',
  });
  const quick = (days: number) => setDueAt(dayjs().add(days, 'day').hour(10).minute(0).second(0).toDate());
  return (
    <Paper withBorder p="md">
      <Group justify="space-between" mb="sm">
        <Title order={5}>Follow-ups</Title>
        {openTasks.length > 0 && <Badge variant="light" color="yellow">{openTasks.length} open</Badge>}
      </Group>
      <Stack gap={6}>
        {openTasks.length === 0 && <Text size="sm" c="dimmed">No open follow-ups.</Text>}
        {openTasks.map((a) => (
          <Group key={a.id} justify="space-between" wrap="nowrap" gap="xs">
            <div style={{ minWidth: 0 }}>
              <Text size="sm" lineClamp={2}>{a.body}</Text>
              <Text size="xs" c={a.overdue ? 'red' : 'dimmed'}>{a.overdue ? 'Overdue · ' : 'Due '}{dateTime(a.dueAt)}</Text>
            </div>
            <Tooltip label="Mark done">
              <ActionIcon variant="light" color="teal" aria-label="Mark follow-up done"
                loading={complete.isPending && complete.variables === a.id} onClick={() => complete.mutate(a.id)}>
                <IconCheck size={16} />
              </ActionIcon>
            </Tooltip>
          </Group>
        ))}
      </Stack>
      {open && (
        <>
          <Divider my="sm" label="Schedule a follow-up" labelPosition="left" />
          <Group gap={6} mb={6}>
            <Button size="compact-xs" variant="default" onClick={() => quick(0)}>Today</Button>
            <Button size="compact-xs" variant="default" onClick={() => quick(1)}>Tomorrow</Button>
            <Button size="compact-xs" variant="default" onClick={() => quick(3)}>In 3 days</Button>
            <Button size="compact-xs" variant="default" onClick={() => quick(7)}>Next week</Button>
          </Group>
          <DateTimePicker placeholder="Due date & time" value={dueAt} onChange={setDueAt} minDate={new Date()}
            valueFormat="DD MMM YYYY, HH:mm" clearable />
          <Textarea mt="xs" autosize minRows={1} maxLength={2000} placeholder="What to do (e.g. call about evening batch)"
            value={body} onChange={(e) => setBody(e.currentTarget.value)} />
          <Group justify="flex-end" mt="xs">
            <Button size="xs" disabled={!dueAt || !body.trim()} loading={schedule.isPending} onClick={() => schedule.mutate()}>
              Schedule
            </Button>
          </Group>
        </>
      )}
    </Paper>
  );
}

function ActivityTimeline({ activities }: { activities: LeadActivity[] }) {
  return (
    <Paper withBorder p="md">
      <Title order={5} mb="md">Timeline</Title>
      {activities.length === 0 ? <Text size="sm" c="dimmed">No activity yet.</Text> : (
        <Timeline bulletSize={24} lineWidth={2}>
          {activities.map((a) => (
            <Timeline.Item key={a.id} color={ACTIVITY_COLOR[a.type] ?? 'gray'}
              bullet={<ThemeIcon size={22} radius="xl" color={ACTIVITY_COLOR[a.type] ?? 'gray'}>{ACTIVITY_ICON[a.type]}</ThemeIcon>}
              title={
                <Group gap={6}>
                  <Text size="sm" fw={600}>{a.type === 'WHATSAPP' ? 'WhatsApp' : label(a.type)}</Text>
                  {a.type === 'FOLLOW_UP' && (a.completedAt
                    ? <Badge size="xs" color="teal" variant="light">Done</Badge>
                    : <Badge size="xs" color={a.overdue ? 'red' : 'yellow'} variant="light">{a.overdue ? 'Overdue' : 'Open'}</Badge>)}
                </Group>
              }>
              <Text size="sm" style={{ whiteSpace: 'pre-line' }}>{a.body}</Text>
              <Text size="xs" c="dimmed" mt={2}>
                {dateTime(a.createdAt)} · {a.createdByName ?? 'System'}
                {a.dueAt ? ` · due ${dateTime(a.dueAt)}` : ''}
              </Text>
            </Timeline.Item>
          ))}
        </Timeline>
      )}
    </Paper>
  );
}

function StartTrialModal({ detail, opened, onClose, onStarted }: {
  detail: LeadDetail;
  opened: boolean;
  onClose: () => void;
  onStarted: (r: TrialResult) => void;
}) {
  const plans = useApiQuery<Plan[]>(opened ? '/api/v1/plans' : null);
  const [planId, setPlanId] = useState<string | null>(null);
  const [startDate, setStartDate] = useState<Date | null>(null);
  const sorted = [...(plans.data ?? [])].sort((a, b) => a.price - b.price);
  useEffect(() => {
    if (opened) {
      setStartDate(null);
      const free = (plans.data ?? []).find((p) => p.price === 0);
      setPlanId(free ? String(free.id) : null);
    }
  }, [opened, plans.data]);
  const missing = [!detail.lead.email && 'email', !detail.lead.lastName && 'last name'].filter(Boolean);
  const start = useApiMutation<void, TrialResult>({
    request: () => ({
      path: `/api/v1/leads/${detail.lead.id}/trial`,
      body: { planId: Number(planId), startDate: startDate ? dayjs(startDate).format('YYYY-MM-DD') : undefined },
    }),
    invalidate: ['/api/v1/leads', '/api/v1/members', '/api/v1/subscriptions'],
    successMessage: (r) => (r.paymentDue ? 'Trial created — collect payment to activate it'
      : `Trial active until ${date(r.endDate)}${r.existingMemberLinked ? ' (linked to existing member)' : ''}`),
    silentError: true,
    onSuccess: (r) => { onClose(); onStarted(r); },
  });
  const selected = sorted.find((p) => String(p.id) === planId);
  return (
    <Modal opened={opened} onClose={onClose} title={`Start a trial for ${detail.lead.fullName}`} size="lg" centered>
      <Stack>
        <Text size="sm" c="dimmed">
          Registers {detail.lead.firstName} as a member (or links the existing member with the same email) and subscribes
          them to the chosen plan. Free plans activate immediately; paid ones wait for payment.
        </Text>
        {missing.length > 0 && (
          <Alert color="orange">Add the prospect&apos;s {missing.join(' and ')} (Edit) before starting a trial.</Alert>
        )}
        <QueryState isLoading={plans.isLoading} error={plans.error} empty={sorted.length === 0} emptyText="No plans on sale.">
          <Radio.Group value={planId} onChange={setPlanId} label="Plan">
            <Stack gap={6} mt={6}>
              {sorted.map((p) => (
                <Paper key={p.id} withBorder p="xs" radius="md"
                  style={p.price === 0 ? { borderColor: 'var(--mantine-color-orange-5)' } : undefined}>
                  <Radio value={String(p.id)} label={
                    <Group gap={6}>
                      <Text size="sm" fw={600}>{p.name}</Text>
                      {p.price === 0 && <Badge size="xs" color="orange">Free trial</Badge>}
                      <Text size="xs" c="dimmed">
                        {p.billingPeriod === 'CUSTOM' ? `${p.durationDays} days` : label(p.billingPeriod)} · {p.price === 0 ? 'Free' : money(p.price)}
                      </Text>
                    </Group>
                  } />
                </Paper>
              ))}
            </Stack>
          </Radio.Group>
        </QueryState>
        <DateInput label="Start date" placeholder="Today" value={startDate} onChange={setStartDate} clearable
          minDate={new Date()} maxDate={dayjs().add(90, 'day').toDate()} valueFormat="DD MMM YYYY" />
        {selected && selected.price > 0 && (
          <Alert color="blue">This plan costs {money(selected.price)} (+ tax). You can collect payment right after.</Alert>
        )}
        {start.error && <Alert color="red" style={{ whiteSpace: 'pre-line' }}>{errorMessage(start.error)}</Alert>}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>Cancel</Button>
          <Button color="orange" disabled={!planId || missing.length > 0} loading={start.isPending} onClick={() => start.mutate()}>
            Start trial
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}

function ConvertModal({ leadId, branchId, opened, onClose }: {
  leadId: number;
  branchId: number;
  opened: boolean;
  onClose: () => void;
}) {
  const [memberId, setMemberId] = useState<number | null>(null);
  useEffect(() => { if (opened) setMemberId(null); }, [opened]);
  const convert = useApiMutation<void, LeadDetail>({
    request: () => ({ path: `/api/v1/leads/${leadId}/convert`, body: { memberId } }),
    invalidate: ['/api/v1/leads'],
    successMessage: 'Lead converted',
    silentError: true,
    onSuccess: onClose,
  });
  return (
    <Modal opened={opened} onClose={onClose} title="Link to a member" centered>
      <Stack>
        <Text size="sm" c="dimmed">
          Use this when the prospect already joined (e.g. bought a membership at the desk). Purchases by a member linked
          through a trial convert the lead automatically.
        </Text>
        <MemberSearch label="Member" placeholder="Search name, phone or code" value={memberId}
          onChange={(v) => setMemberId(v)} branchId={branchId} />
        {convert.error && <Alert color="red">{errorMessage(convert.error)}</Alert>}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>Cancel</Button>
          <Button color="teal" disabled={!memberId} loading={convert.isPending} onClick={() => convert.mutate()}>Mark converted</Button>
        </Group>
      </Stack>
    </Modal>
  );
}
