import {
  ActionIcon, Alert, Badge, Button, Card, Group, Menu, Modal, Paper, SimpleGrid, Stack, Table, Text, Title, Tooltip, VisuallyHidden,
} from '@mantine/core';
import {
  IconArrowsExchange, IconCreditCard, IconDots, IconPlayerPause, IconPlayerPlay, IconRefresh, IconShoppingCart, IconX,
} from '@tabler/icons-react';
import { useState, type ReactNode } from 'react';
import type { Member, Page, Plan, Subscription, SubscriptionStatus } from '@/api/types';
import { SubscribeModal, type PlanAction } from '@/components/billing/SubscribeModal';
import { AutopayControl } from '@/components/billing/AutopayControl';
import { PayInvoiceModal } from '@/components/billing/PayInvoiceModal';
import { CancelModal, FreezeModal } from '@/components/billing/SubscriptionActions';
import { PageHeader } from '@/components/PageHeader';
import { Pager } from '@/components/Pager';
import { QueryState } from '@/components/QueryState';
import { StatusBadge } from '@/components/StatusBadge';
import { date, label, money } from '@/lib/format';
import { errorMessage } from '@/lib/notify';
import { useApiMutation, useApiQuery } from '@/lib/useApi';

const RENEWABLE: SubscriptionStatus[] = ['SCHEDULED', 'ACTIVE', 'FROZEN', 'GRACE', 'EXPIRED'];
const CANCELLABLE: SubscriptionStatus[] = ['PENDING_PAYMENT', 'SCHEDULED', 'ACTIVE', 'FROZEN', 'GRACE'];
const OPEN: SubscriptionStatus[] = ['PENDING_PAYMENT', 'SCHEDULED', 'ACTIVE', 'FROZEN', 'GRACE'];

interface Actions {
  pay: boolean;
  renew: boolean;
  change: boolean;
  freeze: boolean;
  resume: boolean;
  cancel: boolean;
  /** why renew / change are unavailable, if relevant */
  blockedReason?: string;
}

/** Mirrors the backend's preconditions so we only offer actions that can succeed. */
function actionsFor(s: Subscription, all: Subscription[], plans: Plan[]): Actions {
  const hasPending = all.some((x) => x.status === 'PENDING_PAYMENT');
  const hasOpenSuccessor = all.some((x) => x.previousSubscriptionId === s.id && OPEN.includes(x.status));
  const plan = plans.find((p) => p.id === s.planId);
  const freezeOk = !plan || (plan.freezePolicy.allowed && s.freezeCount < plan.freezePolicy.maxCount
    && s.freezeDaysUsed < plan.freezePolicy.maxDays);
  const blockedReason = hasPending
    ? 'Complete or cancel the pending payment first'
    : hasOpenSuccessor ? 'A renewal or plan change is already queued' : undefined;
  return {
    pay: s.status === 'PENDING_PAYMENT' && s.invoiceId != null,
    renew: RENEWABLE.includes(s.status) && !hasPending && !hasOpenSuccessor,
    change: s.status === 'ACTIVE' && !hasPending && !hasOpenSuccessor,
    freeze: s.status === 'ACTIVE' && freezeOk,
    resume: s.status === 'FROZEN',
    cancel: CANCELLABLE.includes(s.status),
    blockedReason,
  };
}

function PlanCatalogue({ plans, onBuy }: { plans: Plan[]; onBuy?: () => void }) {
  return (
    <SimpleGrid cols={{ base: 1, sm: 2, md: 3 }}>
      {plans.map((p) => (
        <Card key={p.id} withBorder radius="md" p="lg">
          <Group justify="space-between" mb={4} wrap="nowrap">
            <Text fw={600}>{p.name}</Text>
            <Badge variant="light" size="sm">{label(p.billingPeriod)}</Badge>
          </Group>
          <Text fz={24} fw={700}>{money(p.price)}</Text>
          <Text size="xs" c="dimmed">
            {p.durationMonths ? `${p.durationMonths} month${p.durationMonths > 1 ? 's' : ''}` : `${p.durationDays} days`}
            {p.joiningFee > 0 ? ` · + ${money(p.joiningFee)} joining fee` : ''} · excl. GST
          </Text>
          {p.description && <Text size="sm" mt="xs">{p.description}</Text>}
          <Stack gap={2} mt="sm">
            {p.benefits.map((b) => <Text size="sm" key={b}>• {b}</Text>)}
          </Stack>
          <Stack gap={2} mt="sm">
            <Text size="xs" c="dimmed">
              {p.access.allBranchAccess ? 'All branches' : 'Home branch only'}
              {p.access.maxVisitsPerMonth ? ` · ${p.access.maxVisitsPerMonth} visits/month` : ' · unlimited visits'}
              {p.access.maxEntriesPerDay ? ` · ${p.access.maxEntriesPerDay}× entry per day` : ''}
            </Text>
            {p.access.accessStartTime && (
              <Text size="xs" c="orange">Entry {p.access.accessStartTime.slice(0, 5)}–{p.access.accessEndTime?.slice(0, 5)}</Text>
            )}
            <Text size="xs" c="dimmed">
              {p.freezePolicy.allowed
                ? `Freeze ${p.freezePolicy.minDays}–${p.freezePolicy.maxDays} days, up to ${p.freezePolicy.maxCount}×`
                : 'Freezing not available'}
              {' · '}
              {label(p.cancellation.policy)}
              {p.cancellation.coolingOffDays > 0 ? ` (${p.cancellation.coolingOffDays}-day cooling off)` : ''}
            </Text>
          </Stack>
          {onBuy && (
            <Button mt="md" variant="light" fullWidth onClick={onBuy}>Choose plan</Button>
          )}
        </Card>
      ))}
    </SimpleGrid>
  );
}

export default function MemberMembershipPage() {
  const me = useApiQuery<Member>('/api/v1/members/me');
  const memberId = me.data?.id ?? null;
  const [page, setPage] = useState(0);
  const history = useApiQuery<Page<Subscription>>(memberId ? `/api/v1/subscriptions/members/${memberId}` : null, { page, size: 20 });
  const plans = useApiQuery<Plan[]>('/api/v1/plans');
  const currentId = me.data?.membership.subscriptionId ?? null;
  const current = useApiQuery<Subscription>(currentId ? `/api/v1/subscriptions/${currentId}` : null);

  const [planAction, setPlanAction] = useState<PlanAction | null>(null);
  const [subscribeOpen, setSubscribeOpen] = useState(false);
  const [payInvoiceId, setPayInvoiceId] = useState<number | null>(null);
  const [freezeSub, setFreezeSub] = useState<Subscription | null>(null);
  const [cancelSub, setCancelSub] = useState<Subscription | null>(null);
  const [resumeSub, setResumeSub] = useState<Subscription | null>(null);

  const resume = useApiMutation<number, Subscription>({
    request: (id) => ({ path: `/api/v1/subscriptions/${id}/resume` }),
    invalidate: ['/api/v1/subscriptions', '/api/v1/members', '/api/v1/dashboard'],
    successMessage: (s) => `Membership resumed — now ends ${date(s.endDate)}`,
    silentError: true,
    onSuccess: () => setResumeSub(null),
  });

  const subs = history.data?.content ?? [];
  // the current subscription might not be on the visible history page
  const all = current.data && !subs.some((s) => s.id === current.data.id) ? [current.data, ...subs] : subs;
  const planList = (plans.data ?? []).filter((p) => p.price > 0 || p.joiningFee > 0);   // free trials are staff-granted
  const membership = me.data?.membership;
  const pending = subs.filter((s) => s.status === 'PENDING_PAYMENT');
  const hasPending = pending.length > 0;
  const canBuy = membership?.state === 'NONE' && !hasPending;

  const openPlanAction = (a: PlanAction) => {
    setPlanAction(a);
    setSubscribeOpen(true);
  };
  const buy = () => memberId && openPlanAction({ kind: 'subscribe', memberId });

  const renderActions = (s: Subscription, compact: boolean) => {
    const a = actionsFor(s, all, planList);
    const items = [
      a.pay && { key: 'pay', label: 'Pay now', icon: <IconCreditCard size={16} />, color: undefined,
        onClick: () => setPayInvoiceId(s.invoiceId) },
      a.renew && { key: 'renew', label: 'Renew', icon: <IconRefresh size={16} />, color: undefined,
        onClick: () => openPlanAction({ kind: 'renew', subscriptionId: s.id, currentPlanId: s.planId }) },
      a.change && { key: 'change', label: 'Change plan', icon: <IconArrowsExchange size={16} />, color: undefined,
        onClick: () => openPlanAction({ kind: 'change', subscriptionId: s.id, currentPlanId: s.planId }) },
      a.freeze && { key: 'freeze', label: 'Freeze', icon: <IconPlayerPause size={16} />, color: undefined,
        onClick: () => setFreezeSub(s) },
      a.resume && { key: 'resume', label: 'Resume', icon: <IconPlayerPlay size={16} />, color: undefined,
        onClick: () => setResumeSub(s) },
      a.cancel && { key: 'cancel', label: s.status === 'PENDING_PAYMENT' ? 'Cancel order' : 'Cancel membership',
        icon: <IconX size={16} />, color: 'red', onClick: () => setCancelSub(s) },
    ].filter(Boolean) as { key: string; label: string; icon: ReactNode; color?: string; onClick: () => void }[];

    if (items.length === 0) return compact ? <Text size="xs" c="dimmed">—</Text> : null;
    if (compact) {
      return (
        <Menu position="bottom-end" withinPortal>
          <Menu.Target>
            <ActionIcon variant="subtle" aria-label={`Actions for subscription ${s.id}`}>
              <IconDots size={18} />
            </ActionIcon>
          </Menu.Target>
          <Menu.Dropdown>
            {items.map((i) => (
              <Menu.Item key={i.key} leftSection={i.icon} color={i.color} onClick={i.onClick}>{i.label}</Menu.Item>
            ))}
          </Menu.Dropdown>
        </Menu>
      );
    }
    return (
      <Stack gap="xs">
        <Group gap="xs">
          {items.map((i) => (
            <Button key={i.key} size="xs" variant={i.key === 'pay' ? 'filled' : i.color ? 'subtle' : 'light'}
              color={i.color} leftSection={i.icon} onClick={i.onClick}>
              {i.label}
            </Button>
          ))}
        </Group>
        {a.blockedReason && RENEWABLE.includes(s.status) && (
          <Text size="xs" c="dimmed">Renew / change plan unavailable: {a.blockedReason.toLowerCase()}.</Text>
        )}
      </Stack>
    );
  };

  return (
    <>
      <PageHeader
        title="My membership"
        subtitle="Your plan, renewals and billing history"
        actions={canBuy && <Button leftSection={<IconShoppingCart size={16} />} onClick={buy}>Buy a membership</Button>}
      />
      <QueryState isLoading={me.isLoading} error={me.error}>
        {me.data && membership && (
          <Stack gap="xl">
            {pending.map((s) => (
              <Alert key={s.id} color="yellow" title="Payment pending" icon={<IconCreditCard size={18} />}>
                <Group justify="space-between" wrap="wrap" gap="xs">
                  <Text size="sm">
                    {s.planName} ({label(s.changeType)}) starting {date(s.startDate)} is waiting for payment of {money(s.price)} + GST.
                    Unpaid orders are cancelled automatically after a few days.
                  </Text>
                  <Group gap="xs">
                    {s.invoiceId && <Button size="xs" onClick={() => setPayInvoiceId(s.invoiceId)}>Pay now</Button>}
                    <Button size="xs" variant="subtle" color="red" onClick={() => setCancelSub(s)}>Cancel order</Button>
                  </Group>
                </Group>
              </Alert>
            ))}

            <Paper withBorder radius="md" p="lg">
              <Group justify="space-between" align="flex-start" wrap="wrap">
                <div>
                  <Text size="xs" c="dimmed" tt="uppercase" fw={600}>Current membership</Text>
                  <Title order={3} mt={4}>{membership.planName ?? 'No active membership'}</Title>
                  {membership.endDate && (
                    <Text size="sm" c="dimmed" mt={4}>
                      {date(membership.startDate)} → {date(membership.endDate)}
                      {membership.daysRemaining != null ? ` · ${membership.daysRemaining} days remaining` : ''}
                    </Text>
                  )}
                  {current.data?.status === 'FROZEN' && (
                    <Text size="sm" c="cyan" mt={4}>
                      Frozen {date(current.data.frozenFrom)} – {date(current.data.frozenUntil)}
                    </Text>
                  )}
                  {current.data && current.data.freezeCount > 0 && current.data.status !== 'FROZEN' && (
                    <Text size="xs" c="dimmed" mt={4}>
                      Freezes used: {current.data.freezeCount} ({current.data.freezeDaysUsed} days)
                    </Text>
                  )}
                </div>
                <StatusBadge value={membership.state} size="lg" />
              </Group>
              {current.data && (
                <div style={{ marginTop: 16 }}>
                  <AutopayControl subscription={current.data} />
                </div>
              )}
              <div style={{ marginTop: 16 }}>
                {current.data ? (
                  renderActions(current.data, false)
                ) : membership.state === 'NONE' ? (
                  <Group>
                    <Text size="sm" c="dimmed">Pick a plan below to get started.</Text>
                    {canBuy && <Button size="xs" onClick={buy}>Buy a membership</Button>}
                  </Group>
                ) : null}
              </div>
            </Paper>

            <div>
              <Title order={4} mb="sm">Subscription history</Title>
              <Paper withBorder radius="md">
                <QueryState isLoading={history.isLoading} error={history.error} empty={subs.length === 0}
                  emptyText="No subscriptions yet.">
                  <Table.ScrollContainer minWidth={760}>
                    <Table verticalSpacing="sm" highlightOnHover>
                      <Table.Thead>
                        <Table.Tr>
                          <Table.Th>Plan</Table.Th>
                          <Table.Th>Type</Table.Th>
                          <Table.Th>Period</Table.Th>
                          <Table.Th ta="right">Price</Table.Th>
                          <Table.Th>Status</Table.Th>
                          <Table.Th w={60}><VisuallyHidden>Actions</VisuallyHidden></Table.Th>
                        </Table.Tr>
                      </Table.Thead>
                      <Table.Tbody>
                        {subs.map((s) => (
                          <Table.Tr key={s.id}>
                            <Table.Td>
                              <Text size="sm" fw={500}>{s.planName}</Text>
                              <Text size="xs" c="dimmed">#{s.id} · {s.planCode}</Text>
                            </Table.Td>
                            <Table.Td>{label(s.changeType)}</Table.Td>
                            <Table.Td>
                              <Text size="sm">{date(s.startDate)} → {date(s.endDate)}</Text>
                              {s.cancellationReason && (
                                <Tooltip label={s.cancellationReason} multiline maw={300}>
                                  <Text size="xs" c="dimmed" truncate="end" maw={260}>Cancelled: {s.cancellationReason}</Text>
                                </Tooltip>
                              )}
                            </Table.Td>
                            <Table.Td ta="right">{money(s.price)}</Table.Td>
                            <Table.Td><StatusBadge value={s.status} /></Table.Td>
                            <Table.Td>{renderActions(s, true)}</Table.Td>
                          </Table.Tr>
                        ))}
                      </Table.Tbody>
                    </Table>
                  </Table.ScrollContainer>
                </QueryState>
              </Paper>
              <Pager page={history.data} onChange={setPage} />
            </div>

            <div>
              <Title order={4} mb={4}>Membership plans</Title>
              <Text size="sm" c="dimmed" mb="sm">Prices exclude 18% GST.</Text>
              <QueryState isLoading={plans.isLoading} error={plans.error} empty={planList.length === 0}
                emptyText="No plans are on sale right now.">
                <PlanCatalogue plans={planList} onBuy={canBuy ? buy : undefined} />
              </QueryState>
            </div>
          </Stack>
        )}
      </QueryState>

      <SubscribeModal
        action={planAction}
        opened={subscribeOpen}
        onClose={() => setSubscribeOpen(false)}
        onCreated={(s) => {
          setSubscribeOpen(false);
          if (s.invoiceId) setPayInvoiceId(s.invoiceId);
        }}
      />
      <PayInvoiceModal invoiceId={payInvoiceId} opened={payInvoiceId != null} onClose={() => setPayInvoiceId(null)} />
      <FreezeModal subscription={freezeSub} opened={freezeSub != null} onClose={() => setFreezeSub(null)} />
      <CancelModal subscription={cancelSub} opened={cancelSub != null} onClose={() => setCancelSub(null)} allowSkipRefund={false} />
      <Modal opened={resumeSub != null} onClose={() => { setResumeSub(null); resume.reset(); }} title="Resume membership" centered>
        <Stack>
          <Text size="sm">
            End the freeze today? Unused freeze days are returned to your allowance and your end date moves
            earlier accordingly.
          </Text>
          {resume.error && <Alert color="red">{errorMessage(resume.error)}</Alert>}
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setResumeSub(null)}>Keep frozen</Button>
            <Button loading={resume.isPending} onClick={() => resumeSub && resume.mutate(resumeSub.id)}>Resume now</Button>
          </Group>
        </Stack>
      </Modal>
    </>
  );
}
