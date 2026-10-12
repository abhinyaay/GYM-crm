import {
  Alert, Anchor, Badge, Button, Group, Menu, Modal, Paper, SimpleGrid, Stack, Table, Tabs, Text, Textarea, Timeline, Title,
} from '@mantine/core';
import { LineChart } from '@mantine/charts';
import {
  IconCalendarPlus, IconChevronDown, IconDoorEnter, IconEdit, IconPlayerPlay, IconReceipt, IconSnowflake, IconSwitchHorizontal,
  IconTrash, IconX,
} from '@tabler/icons-react';
import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import type {
  AuditEntry, Invoice, Member, MemberDashboard, Page, Payment, Progress, Subscription, Visit,
} from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { FRONT_DESK, hasAnyRole, MANAGERS } from '@/auth/roles';
import { InvoiceSummary } from '@/components/billing/InvoiceSummary';
import { MemberAccessPanel } from '@/components/access/MemberAccessPanel';
import { PreferenceMatrix } from '@/components/notifications/PreferenceMatrix';
import { AutopayControl } from '@/components/billing/AutopayControl';
import { PayInvoiceModal } from '@/components/billing/PayInvoiceModal';
import { CreditNoteButtons, TaxDocumentButton } from '@/components/billing/TaxDocumentButton';
import { PaymentMethodsPanel } from '@/components/billing/PaymentMethodsPanel';
import { SubscribeModal, type PlanAction } from '@/components/billing/SubscribeModal';
import { CancelModal, FreezeModal } from '@/components/billing/SubscriptionActions';
import { MemberFormModal } from '@/components/members/MemberFormModal';
import { PageHeader } from '@/components/PageHeader';
import { Pager } from '@/components/Pager';
import { QueryState } from '@/components/QueryState';
import { StatCard } from '@/components/StatCard';
import { StatusBadge } from '@/components/StatusBadge';
import { date, dateTime, label, money, time } from '@/lib/format';
import { errorMessage } from '@/lib/notify';
import { useApiMutation, useApiQuery } from '@/lib/useApi';

export default function MemberDetailPage() {
  const id = Number(useParams().id);
  const navigate = useNavigate();
  const { user } = useAuth();
  const desk = hasAnyRole(user, FRONT_DESK);
  const manager = hasAnyRole(user, MANAGERS);
  const member = useApiQuery<Member>(`/api/v1/members/${id}`);
  const [editOpen, setEditOpen] = useState(false);
  const [statusOpen, setStatusOpen] = useState(false);
  const [tab, setTab] = useState<string | null>('memberships');

  const m = member.data;
  const checkIn = useApiMutation<void, Visit>({
    request: () => ({ path: '/api/v1/attendance/check-in', body: { memberId: id, branchId: m?.homeBranchId, method: 'FRONT_DESK' } }),
    invalidate: ['/api/v1/attendance', '/api/v1/members'],
    successMessage: (v) => `Checked in at ${time(v.checkInTime)}`,
  });
  const remove = useApiMutation({
    request: () => ({ path: `/api/v1/members/${id}`, method: 'DELETE' }),
    invalidate: ['/api/v1/members'],
    successMessage: 'Member deleted',
    onSuccess: () => navigate('/members'),
  });

  return (
    <QueryState isLoading={member.isLoading} error={member.error}>
      {m && (
        <>
          <PageHeader
            title={<Group gap="sm">{m.firstName} {m.lastName}<StatusBadge value={m.membership.state} size="lg" />{m.status !== 'ACTIVE' && <StatusBadge value={m.status} size="lg" />}</Group>}
            subtitle={`${m.memberCode} · ${m.homeBranchName} · member since ${date(m.registeredAt)}`}
            actions={desk && (
              <>
                <Button leftSection={<IconDoorEnter size={16} />} onClick={() => checkIn.mutate()} loading={checkIn.isPending}>Check in</Button>
                <Button variant="default" leftSection={<IconEdit size={16} />} onClick={() => setEditOpen(true)}>Edit</Button>
                {manager && (
                  <Menu position="bottom-end">
                    <Menu.Target><Button variant="default" rightSection={<IconChevronDown size={14} />}>More</Button></Menu.Target>
                    <Menu.Dropdown>
                      <Menu.Item onClick={() => setStatusOpen(true)}>Change account status</Menu.Item>
                      <Menu.Item color="red" leftSection={<IconTrash size={14} />}
                        onClick={() => window.confirm(`Delete ${m.firstName} ${m.lastName}? Only possible without a current membership.`) && remove.mutate()}>
                        Delete member
                      </Menu.Item>
                    </Menu.Dropdown>
                  </Menu>
                )}
              </>
            )}
          />
          <SimpleGrid cols={{ base: 1, sm: 2, md: 4 }} mb="md">
            <StatCard label="Current plan" value={m.membership.planName ?? 'None'} hint={m.membership.endDate ? `until ${date(m.membership.endDate)}` : undefined} />
            <StatCard label="Days remaining" value={m.membership.daysRemaining ?? '—'} />
            <Paper withBorder p="md">
              <Text size="xs" c="dimmed" tt="uppercase" fw={600}>Contact</Text>
              <Text size="sm" mt={4}>{m.email}</Text>
              <Text size="sm">{m.phone}</Text>
            </Paper>
            <Paper withBorder p="md">
              <Text size="xs" c="dimmed" tt="uppercase" fw={600}>Emergency contact</Text>
              {m.emergencyContacts.length === 0 ? <Text size="sm" c="dimmed" mt={4}>None on file</Text> : m.emergencyContacts.map((c) => (
                <Text size="sm" key={c.id ?? c.phone} mt={4}>{c.name} ({c.relationship.toLowerCase()}) · {c.phone}{c.primary && ' ★'}</Text>
              ))}
            </Paper>
          </SimpleGrid>
          {m.notes && <Alert color="gray" mb="md" title="Staff notes">{m.notes}</Alert>}
          {checkIn.error && <Alert color="red" mb="md" title="Entry refused">{errorMessage(checkIn.error)}</Alert>}

          <Tabs value={tab} onChange={setTab} keepMounted={false}>
            <Tabs.List mb="md">
              <Tabs.Tab value="memberships">Memberships</Tabs.Tab>
              <Tabs.Tab value="billing">Billing</Tabs.Tab>
              <Tabs.Tab value="wallet">Cards, autopay &amp; credit</Tabs.Tab>
              <Tabs.Tab value="access">Gate access</Tabs.Tab>
              <Tabs.Tab value="attendance">Attendance</Tabs.Tab>
              <Tabs.Tab value="training">Training</Tabs.Tab>
              <Tabs.Tab value="timeline">Timeline</Tabs.Tab>
              <Tabs.Tab value="notifications">Notifications</Tabs.Tab>
            </Tabs.List>
            <Tabs.Panel value="memberships"><MembershipsTab member={m} /></Tabs.Panel>
            <Tabs.Panel value="billing"><BillingTab memberId={id} /></Tabs.Panel>
            <Tabs.Panel value="wallet"><PaymentMethodsPanel memberId={id} staff /></Tabs.Panel>
            <Tabs.Panel value="access"><MemberAccessPanel memberId={id} /></Tabs.Panel>
            <Tabs.Panel value="attendance"><AttendanceTab memberId={id} /></Tabs.Panel>
            <Tabs.Panel value="training"><TrainingTab memberId={id} /></Tabs.Panel>
            <Tabs.Panel value="timeline"><TimelineTab memberId={id} /></Tabs.Panel>
            <Tabs.Panel value="notifications"><PreferenceMatrix memberId={id} staff /></Tabs.Panel>
          </Tabs>

          <MemberFormModal opened={editOpen} onClose={() => setEditOpen(false)} member={m} />
          <StatusModal member={m} opened={statusOpen} onClose={() => setStatusOpen(false)} />
        </>
      )}
    </QueryState>
  );
}

function StatusModal({ member, opened, onClose }: { member: Member; opened: boolean; onClose: () => void }) {
  const [reason, setReason] = useState('');
  const change = useApiMutation<string>({
    request: (status) => ({ path: `/api/v1/members/${member.id}/status`, method: 'PATCH', body: { status, reason } }),
    invalidate: ['/api/v1/members'],
    successMessage: 'Status updated',
    onSuccess: onClose,
  });
  return (
    <Modal opened={opened} onClose={onClose} title="Account status" centered>
      <Stack>
        <Text size="sm">Suspended or inactive members cannot check in or buy memberships.</Text>
        <Textarea label="Reason" value={reason} onChange={(e) => setReason(e.currentTarget.value)} />
        <Group>
          {(['ACTIVE', 'SUSPENDED', 'INACTIVE'] as const).filter((s) => s !== member.status).map((s) => (
            <Button key={s} variant={s === 'ACTIVE' ? 'filled' : 'light'} color={s === 'ACTIVE' ? 'teal' : 'orange'}
              loading={change.isPending} onClick={() => change.mutate(s)}>{label(s)}</Button>
          ))}
        </Group>
      </Stack>
    </Modal>
  );
}

const OPEN_STATUSES = ['PENDING_PAYMENT', 'SCHEDULED', 'ACTIVE', 'FROZEN', 'GRACE'];

function MembershipsTab({ member }: { member: Member }) {
  const { user } = useAuth();
  const manager = hasAnyRole(user, MANAGERS);
  const [page, setPage] = useState(0);
  const subs = useApiQuery<Page<Subscription>>(`/api/v1/subscriptions/members/${member.id}`, { page, size: 10 });
  const [planAction, setPlanAction] = useState<PlanAction | null>(null);
  const [payInvoice, setPayInvoice] = useState<number | null>(null);
  const [freeze, setFreeze] = useState<Subscription | null>(null);
  const [cancel, setCancel] = useState<Subscription | null>(null);
  const resume = useApiMutation<number, Subscription>({
    request: (sid) => ({ path: `/api/v1/subscriptions/${sid}/resume` }),
    invalidate: ['/api/v1/subscriptions', '/api/v1/members'],
    successMessage: (s) => `Resumed - membership ends ${date(s.endDate)}`,
  });
  const rows = subs.data?.content ?? [];
  const hasOpen = rows.some((s) => OPEN_STATUSES.includes(s.status));
  const latest = rows[0];

  return (
    <Paper withBorder p="md">
      <Group justify="space-between" mb="sm">
        <Title order={4}>Membership history</Title>
        {!hasOpen && member.status === 'ACTIVE' && (
          <Button leftSection={<IconCalendarPlus size={16} />}
            onClick={() => setPlanAction(latest?.status === 'EXPIRED'
              ? { kind: 'renew', subscriptionId: latest.id, currentPlanId: latest.planId }
              : { kind: 'subscribe', memberId: member.id })}>
            {latest?.status === 'EXPIRED' ? 'Renew membership' : 'Sell membership'}
          </Button>
        )}
      </Group>
      <QueryState isLoading={subs.isLoading} error={subs.error} empty={rows.length === 0} emptyText="No memberships yet.">
        <Table.ScrollContainer minWidth={760}>
          <Table verticalSpacing="sm">
            <Table.Thead>
              <Table.Tr><Table.Th>Plan</Table.Th><Table.Th>Period</Table.Th><Table.Th>Status</Table.Th><Table.Th>Price</Table.Th><Table.Th>Freeze</Table.Th><Table.Th /></Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((s) => (
                <Table.Tr key={s.id}>
                  <Table.Td>
                    <Text size="sm" fw={600}>{s.planName}</Text>
                    <Text size="xs" c="dimmed">{label(s.changeType)}{s.previousSubscriptionId ? ` of #${s.previousSubscriptionId}` : ''} · #{s.id}</Text>
                  </Table.Td>
                  <Table.Td><Text size="sm">{date(s.startDate)} → {date(s.endDate)}</Text>
                    {s.frozenUntil && <Text size="xs" c="cyan">frozen until {date(s.frozenUntil)}</Text>}
                    {s.cancellationReason && <Text size="xs" c="dimmed">“{s.cancellationReason}”</Text>}
                  </Table.Td>
                  <Table.Td>
                    <StatusBadge value={s.status} />
                    {['PENDING_PAYMENT', 'SCHEDULED', 'ACTIVE', 'FROZEN'].includes(s.status) && (
                      <div style={{ marginTop: 6 }}><AutopayControl subscription={s} compact /></div>
                    )}
                  </Table.Td>
                  <Table.Td>{money(s.price)}</Table.Td>
                  <Table.Td><Text size="sm">{s.freezeDaysUsed} d · {s.freezeCount}×</Text></Table.Td>
                  <Table.Td>
                    <Group gap={6} justify="flex-end" wrap="nowrap">
                      {s.status === 'PENDING_PAYMENT' && s.invoiceId && (
                        <Button size="xs" leftSection={<IconReceipt size={14} />} onClick={() => setPayInvoice(s.invoiceId)}>Collect payment</Button>
                      )}
                      {s.status === 'FROZEN' && (
                        <Button size="xs" variant="light" leftSection={<IconPlayerPlay size={14} />} loading={resume.isPending}
                          onClick={() => resume.mutate(s.id)}>Resume</Button>
                      )}
                      {['ACTIVE', 'FROZEN', 'GRACE', 'SCHEDULED'].includes(s.status) && (
                        <Menu position="bottom-end">
                          <Menu.Target><Button size="xs" variant="default" rightSection={<IconChevronDown size={12} />}>Actions</Button></Menu.Target>
                          <Menu.Dropdown>
                            <Menu.Item leftSection={<IconCalendarPlus size={14} />}
                              onClick={() => setPlanAction({ kind: 'renew', subscriptionId: s.id, currentPlanId: s.planId })}>Renew</Menu.Item>
                            {s.status === 'ACTIVE' && (
                              <>
                                <Menu.Item leftSection={<IconSwitchHorizontal size={14} />}
                                  onClick={() => setPlanAction({ kind: 'change', subscriptionId: s.id, currentPlanId: s.planId })}>Upgrade / downgrade</Menu.Item>
                                <Menu.Item leftSection={<IconSnowflake size={14} />} onClick={() => setFreeze(s)}>Freeze</Menu.Item>
                              </>
                            )}
                            <Menu.Divider />
                            <Menu.Item color="red" leftSection={<IconX size={14} />} onClick={() => setCancel(s)}>Cancel…</Menu.Item>
                          </Menu.Dropdown>
                        </Menu>
                      )}
                      {s.status === 'PENDING_PAYMENT' && (
                        <Button size="xs" variant="subtle" color="red" onClick={() => setCancel(s)}>Cancel</Button>
                      )}
                    </Group>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
        <Pager page={subs.data} onChange={setPage} />
      </QueryState>
      <SubscribeModal action={planAction} opened={!!planAction} onClose={() => setPlanAction(null)}
        onCreated={(s) => { setPlanAction(null); setPayInvoice(s.invoiceId); }} />
      <PayInvoiceModal invoiceId={payInvoice} opened={!!payInvoice} onClose={() => setPayInvoice(null)} />
      <FreezeModal subscription={freeze} opened={!!freeze} onClose={() => setFreeze(null)} />
      <CancelModal subscription={cancel} opened={!!cancel} onClose={() => setCancel(null)} allowSkipRefund={manager} />
    </Paper>
  );
}

function BillingTab({ memberId }: { memberId: number }) {
  const invoices = useApiQuery<Page<Invoice>>('/api/v1/payments/invoices', { memberId, size: 20 });
  const payments = useApiQuery<Page<Payment>>('/api/v1/payments', { memberId, size: 20 });
  const [view, setView] = useState<Invoice | null>(null);
  const [pay, setPay] = useState<number | null>(null);
  return (
    <SimpleGrid cols={{ base: 1, lg: 2 }}>
      <Paper withBorder p="md">
        <Title order={4} mb="sm">Invoices</Title>
        <QueryState isLoading={invoices.isLoading} error={invoices.error} empty={invoices.data?.content.length === 0}>
          <Table verticalSpacing="xs" highlightOnHover>
            <Table.Thead><Table.Tr><Table.Th>Invoice</Table.Th><Table.Th>Total</Table.Th><Table.Th>Status</Table.Th><Table.Th /></Table.Tr></Table.Thead>
            <Table.Tbody>
              {invoices.data?.content.map((i) => (
                <Table.Tr key={i.id}>
                  <Table.Td><Anchor size="sm" onClick={() => setView(i)}>{i.invoiceNumber}</Anchor><Text size="xs" c="dimmed">{date(i.issuedAt)}</Text></Table.Td>
                  <Table.Td>{money(i.totalAmount)}</Table.Td>
                  <Table.Td><Group gap={4}><StatusBadge value={i.status} />{i.overdue && <Badge color="red" size="xs">overdue</Badge>}</Group></Table.Td>
                  <Table.Td>
                    <Group gap={4} wrap="nowrap" justify="flex-end">
                      <TaxDocumentButton compact doc={{ kind: 'invoice', invoiceId: i.id, number: i.invoiceNumber }} />
                      {i.status === 'ISSUED' && <Button size="xs" onClick={() => setPay(i.id)}>Pay</Button>}
                    </Group>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </QueryState>
      </Paper>
      <Paper withBorder p="md">
        <Title order={4} mb="sm">Payments</Title>
        <QueryState isLoading={payments.isLoading} error={payments.error} empty={payments.data?.content.length === 0}>
          <Table verticalSpacing="xs">
            <Table.Thead><Table.Tr><Table.Th>Date</Table.Th><Table.Th>Amount</Table.Th><Table.Th>Method</Table.Th><Table.Th>Status</Table.Th></Table.Tr></Table.Thead>
            <Table.Tbody>
              {payments.data?.content.map((p) => (
                <Table.Tr key={p.id}>
                  <Table.Td><Text size="sm">{dateTime(p.processedAt)}</Text><Text size="xs" c="dimmed">{p.invoiceNumber}</Text></Table.Td>
                  <Table.Td>
                    {money(p.amount)}
                    {p.refundedAmount > 0 && (
                      <Group gap={2} wrap="nowrap">
                        <Text size="xs" c="grape">−{money(p.refundedAmount)} refunded</Text>
                        <CreditNoteButtons paymentId={p.id} />
                      </Group>
                    )}
                  </Table.Td>
                  <Table.Td>
                    {label(p.method)}{p.autopay && <Badge ml={4} size="xs" variant="light" color="teal">autopay</Badge>}
                    {p.instrument && <Text size="xs" c="dimmed">{p.instrument}</Text>}
                  </Table.Td>
                  <Table.Td><StatusBadge value={p.status} />{p.failureReason && <Text size="xs" c="red">{p.failureReason}</Text>}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </QueryState>
      </Paper>
      <Modal opened={!!view} onClose={() => setView(null)} title="Invoice" centered>
        {view && (
          <Stack>
            <InvoiceSummary invoice={view} />
            <TaxDocumentButton doc={{ kind: 'invoice', invoiceId: view.id, number: view.invoiceNumber }}
              label="Download GST tax invoice (PDF)" />
          </Stack>
        )}
      </Modal>
      <PayInvoiceModal invoiceId={pay} opened={!!pay} onClose={() => setPay(null)} />
    </SimpleGrid>
  );
}

function AttendanceTab({ memberId }: { memberId: number }) {
  const [page, setPage] = useState(0);
  const visits = useApiQuery<Page<Visit>>(`/api/v1/attendance/members/${memberId}`, { page, size: 15 });
  return (
    <Paper withBorder p="md">
      <Title order={4} mb="sm">Visits (last 90 days)</Title>
      <QueryState isLoading={visits.isLoading} error={visits.error} empty={visits.data?.content.length === 0} emptyText="No visits in the last 90 days.">
        <Table verticalSpacing="xs">
          <Table.Thead><Table.Tr><Table.Th>Date</Table.Th><Table.Th>In</Table.Th><Table.Th>Out</Table.Th><Table.Th>Duration</Table.Th><Table.Th>Method</Table.Th></Table.Tr></Table.Thead>
          <Table.Tbody>
            {visits.data?.content.map((v) => (
              <Table.Tr key={v.id}>
                <Table.Td>{date(v.checkInTime)}</Table.Td>
                <Table.Td>{time(v.checkInTime)}</Table.Td>
                <Table.Td>{v.checkOutTime ? time(v.checkOutTime) : <Badge variant="dot">inside</Badge>}</Table.Td>
                <Table.Td>{v.durationMinutes != null ? `${v.durationMinutes} min${v.autoCheckedOut ? ' (auto)' : ''}` : '—'}</Table.Td>
                <Table.Td>{label(v.method)}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
        <Pager page={visits.data} onChange={setPage} />
      </QueryState>
    </Paper>
  );
}

function TrainingTab({ memberId }: { memberId: number }) {
  const { user } = useAuth();
  const canCoach = hasAnyRole(user, [...MANAGERS, 'TRAINER']);
  const overview = useApiQuery<MemberDashboard>('/api/v1/dashboard/member', { memberId });
  const progress = useApiQuery<Page<Progress>>(`/api/v1/workouts/members/${memberId}/progress`, { size: 50 });
  const d = overview.data;
  const chart = [...(progress.data?.content ?? [])].reverse().filter((p) => p.weightKg != null)
    .map((p) => ({ date: date(p.recordedAt), weight: p.weightKg }));
  return (
    <QueryState isLoading={overview.isLoading} error={overview.error}>
      {d && (
        <SimpleGrid cols={{ base: 1, lg: 2 }}>
          <Paper withBorder p="md">
            <Title order={4} mb="sm">Trainer & plan</Title>
            {d.trainer ? (
              <Text size="sm" mb="sm">Coached by <b>{d.trainer.trainerName}</b> since {date(d.trainer.assignedAt)}{d.trainer.goal && ` · goal: ${d.trainer.goal}`}</Text>
            ) : <Text size="sm" c="dimmed" mb="sm">No personal trainer assigned.</Text>}
            {d.workoutPlan ? (
              <>
                <Text size="sm" fw={600}>{d.workoutPlan.name}</Text>
                <Text size="xs" c="dimmed" mb="xs">{label(d.workoutPlan.goal)} · {label(d.workoutPlan.difficulty)} · {date(d.workoutPlan.startDate)} → {date(d.workoutPlan.endDate)}</Text>
                {d.workoutPlan.trainerNotes && <Text size="sm" fs="italic" mb="xs">“{d.workoutPlan.trainerNotes}”</Text>}
                <Text size="sm">{d.workoutPlan.exercises.length} exercises across {new Set(d.workoutPlan.exercises.map((e) => e.dayNumber)).size} days</Text>
              </>
            ) : <Text size="sm" c="dimmed">No active workout plan.</Text>}
            <Title order={5} mt="md" mb="xs">Upcoming sessions</Title>
            {d.upcomingSessions.length === 0 ? <Text size="sm" c="dimmed">None booked.</Text> : d.upcomingSessions.map((s) => (
              <Text size="sm" key={s.id}>{dateTime(s.startsAt)} · <StatusBadge value={s.status} size="xs" /></Text>
            ))}
            {d.trainer && canCoach && (
              <Anchor component={Link} to={`/trainer/clients/${memberId}`} size="sm" mt="md" display="block">Open coaching view</Anchor>
            )}
          </Paper>
          <Paper withBorder p="md">
            <Title order={4} mb="sm">Body weight</Title>
            {progress.error ? <Text size="sm" c="dimmed">{errorMessage(progress.error)}</Text>
              : chart.length < 2 ? <Text size="sm" c="dimmed">Not enough measurements yet.</Text>
                : <LineChart h={220} data={chart} dataKey="date" series={[{ name: 'weight', color: 'teal.6', label: 'kg' }]}
                    curveType="monotone" yAxisProps={{ domain: ['dataMin - 2', 'dataMax + 2'] }} />}
          </Paper>
        </SimpleGrid>
      )}
    </QueryState>
  );
}

function TimelineTab({ memberId }: { memberId: number }) {
  const [page, setPage] = useState(0);
  const history = useApiQuery<Page<AuditEntry>>(`/api/v1/members/${memberId}/history`, { page, size: 25 });
  return (
    <Paper withBorder p="md">
      <Title order={4} mb="md">Audit timeline</Title>
      <QueryState isLoading={history.isLoading} error={history.error} empty={history.data?.content.length === 0}>
        <Timeline bulletSize={14} lineWidth={2}>
          {history.data?.content.map((e) => (
            <Timeline.Item key={e.id} title={`${label(e.entityType)} · ${label(e.action)}`}>
              <Text size="xs" c="dimmed">{dateTime(e.occurredAt)} · by {e.actorEmail ?? 'system'}</Text>
              {e.details && Object.keys(e.details).length > 0 && (
                <Text size="xs" mt={2}>
                  {Object.entries(e.details).map(([k, v]) => `${k}: ${String(v)}`).join(' · ')}
                </Text>
              )}
            </Timeline.Item>
          ))}
        </Timeline>
        <Pager page={history.data} onChange={setPage} />
      </QueryState>
    </Paper>
  );
}
