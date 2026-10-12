import {
  Alert, Badge, Button, Card, Divider, Group, List, Modal, NumberInput, SegmentedControl, Select, SimpleGrid, Stack,
  Switch, Text, Textarea, TextInput, ThemeIcon, Title, Tooltip,
} from '@mantine/core';
import { TimeInput } from '@mantine/dates';
import { useForm } from '@mantine/form';
import {
  IconArchive, IconCheck, IconClock, IconEdit, IconHistory, IconPlayerPlay, IconPlus, IconSnowflake, IconTag,
} from '@tabler/icons-react';
import { useState } from 'react';
import { PRICING, type PriceChange } from '@/api/pricing';
import type { BillingPeriod, CancellationPolicy, Page, Plan, PlanStatus } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { FRONT_DESK, hasAnyRole, MANAGERS, ORG_WIDE } from '@/auth/roles';
import { PageHeader } from '@/components/PageHeader';
import { Pager } from '@/components/Pager';
import { QueryState } from '@/components/QueryState';
import { StatusBadge } from '@/components/StatusBadge';
import { label, money } from '@/lib/format';
import { errorMessage } from '@/lib/notify';
import { useApiMutation, useApiQuery } from '@/lib/useApi';
import { ChangePriceModal, PriceHistoryDrawer, ScheduledPriceBadge } from './PlanPricing';

const PERIODS: { value: BillingPeriod; label: string }[] = [
  { value: 'MONTHLY', label: 'Monthly' },
  { value: 'QUARTERLY', label: 'Quarterly' },
  { value: 'HALF_YEARLY', label: 'Half-yearly' },
  { value: 'ANNUAL', label: 'Annual' },
  { value: 'CUSTOM', label: 'Custom (days)' },
];

const POLICIES: { value: CancellationPolicy; label: string; hint: string }[] = [
  { value: 'NON_REFUNDABLE', label: 'Non-refundable', hint: 'No refund on cancellation' },
  { value: 'PRORATED', label: 'Prorated', hint: 'Unused days refunded, minus the cancellation fee' },
  { value: 'COOLING_OFF_FULL_REFUND', label: 'Cooling-off full refund', hint: 'Full refund inside the cooling-off window' },
];

function periodSuffix(plan: Plan): string {
  switch (plan.billingPeriod) {
    case 'MONTHLY': return '/ month';
    case 'QUARTERLY': return '/ quarter';
    case 'HALF_YEARLY': return '/ 6 months';
    case 'ANNUAL': return '/ year';
    default: return `/ ${plan.durationDays ?? '?'} days`;
  }
}

const hhmm = (t: string | null) => (t ? t.slice(0, 5) : '');

// ------------------------------------------------------------------ page

export default function PlansPage() {
  const { user } = useAuth();
  const isManager = hasAnyRole(user, MANAGERS);
  const canEdit = hasAnyRole(user, ORG_WIDE);
  const [status, setStatus] = useState<'ALL' | PlanStatus>('ALL');
  const [page, setPage] = useState(0);
  const [editing, setEditing] = useState<Plan | 'new' | null>(null);
  const [statusChange, setStatusChange] = useState<{ plan: Plan; to: 'ACTIVE' | 'ARCHIVED' } | null>(null);
  const [pricing, setPricing] = useState<Plan | null>(null);
  const [history, setHistory] = useState<Plan | null>(null);

  // front desk: the public active catalogue; managers: every plan incl. drafts/archived
  const active = useApiQuery<Plan[]>(isManager ? null : '/api/v1/plans');
  const admin = useApiQuery<Page<Plan>>(isManager ? '/api/v1/plans/admin' : null, {
    status: status === 'ALL' ? undefined : status, page, size: 12,
  });
  const plans = isManager ? admin.data?.content : active.data;
  const scheduled = useApiQuery<PriceChange[]>(hasAnyRole(user, FRONT_DESK) ? PRICING.scheduled : null);
  const pendingByPlan = new Map((scheduled.data ?? []).map((c) => [c.planId, c]));
  const query = isManager ? admin : active;

  const changeStatus = useApiMutation<{ id: number; status: 'ACTIVE' | 'ARCHIVED' }, Plan>({
    request: ({ id, status: s }) => ({ path: `/api/v1/plans/${id}/status`, method: 'PATCH', body: { status: s } }),
    invalidate: ['/api/v1/plans'],
    successMessage: (p) => `${p.name} is now ${label(p.status).toLowerCase()}`,
    onSuccess: () => setStatusChange(null),
  });

  return (
    <>
      <PageHeader
        title="Membership plans"
        subtitle={isManager ? 'Plan catalogue including drafts and archived plans' : 'Plans currently on sale'}
        actions={canEdit && (
          <Button leftSection={<IconPlus size={16} />} onClick={() => setEditing('new')}>New plan</Button>
        )}
      />

      {isManager && (
        <Group mb="md">
          <SegmentedControl
            value={status}
            onChange={(v) => { setStatus(v as 'ALL' | PlanStatus); setPage(0); }}
            data={[
              { value: 'ALL', label: 'All' },
              { value: 'ACTIVE', label: 'Active' },
              { value: 'DRAFT', label: 'Draft' },
              { value: 'ARCHIVED', label: 'Archived' },
            ]}
          />
        </Group>
      )}

      <QueryState isLoading={query.isLoading} error={query.error} empty={plans?.length === 0} emptyText="No plans found.">
        <SimpleGrid cols={{ base: 1, sm: 2, xl: 3 }} spacing="md">
          {plans?.map((p) => (
            <PlanCard
              key={p.id}
              plan={p}
              canEdit={canEdit}
              canPrice={isManager}
              pending={pendingByPlan.get(p.id)}
              onEdit={() => setEditing(p)}
              onStatus={(to) => setStatusChange({ plan: p, to })}
              onChangePrice={() => setPricing(p)}
              onHistory={() => setHistory(p)}
            />
          ))}
        </SimpleGrid>
        {isManager && <Pager page={admin.data} onChange={setPage} />}
      </QueryState>

      <Modal
        opened={editing !== null}
        onClose={() => setEditing(null)}
        title={editing === 'new' ? 'New membership plan' : `Edit ${editing?.name ?? ''}`}
        size="xl"
      >
        {editing !== null && (
          <PlanForm
            key={editing === 'new' ? 'new' : editing.id}
            plan={editing === 'new' ? null : editing}
            onDone={() => setEditing(null)}
          />
        )}
      </Modal>

      <ChangePriceModal plan={pricing} opened={pricing !== null} onClose={() => setPricing(null)} />
      <PriceHistoryDrawer plan={history} opened={history !== null} onClose={() => setHistory(null)} canManage={isManager} />

      <Modal
        opened={statusChange !== null}
        onClose={() => setStatusChange(null)}
        title={statusChange?.to === 'ACTIVE' ? 'Activate plan' : 'Archive plan'}
        centered
      >
        {statusChange && (
          <Stack>
            <Text size="sm">
              {statusChange.to === 'ACTIVE'
                ? <>Put <b>{statusChange.plan.name}</b> on sale? Active plans appear in the public catalogue.</>
                : <>Stop selling <b>{statusChange.plan.name}</b>? Existing subscriptions are unaffected, but an archived plan can never be re-activated.</>}
            </Text>
            {changeStatus.error && <Alert color="red">{errorMessage(changeStatus.error)}</Alert>}
            <Group justify="flex-end">
              <Button variant="default" onClick={() => setStatusChange(null)}>Cancel</Button>
              <Button
                color={statusChange.to === 'ACTIVE' ? 'teal' : 'red'}
                loading={changeStatus.isPending}
                onClick={() => changeStatus.mutate({ id: statusChange.plan.id, status: statusChange.to })}
              >
                {statusChange.to === 'ACTIVE' ? 'Activate' : 'Archive'}
              </Button>
            </Group>
          </Stack>
        )}
      </Modal>
    </>
  );
}

// ------------------------------------------------------------------ card

function PlanCard({ plan, canEdit, canPrice, pending, onEdit, onStatus, onChangePrice, onHistory }: {
  plan: Plan;
  canEdit: boolean;
  canPrice: boolean;
  pending?: PriceChange;
  onEdit: () => void;
  onStatus: (to: 'ACTIVE' | 'ARCHIVED') => void;
  onChangePrice: () => void;
  onHistory: () => void;
}) {
  const { access, freezePolicy, cancellation } = plan;
  return (
    <Card withBorder padding="lg" radius="md" style={{ display: 'flex', flexDirection: 'column' }}>
      <Group justify="space-between" align="flex-start" wrap="nowrap">
        <div>
          <Title order={4}>{plan.name}</Title>
          <Text size="xs" c="dimmed" ff="monospace">{plan.code}</Text>
        </div>
        <StatusBadge value={plan.status} />
      </Group>

      <Group gap={6} align="baseline" mt="sm">
        <Text fz={28} fw={800} lh={1}>{money(plan.price)}</Text>
        <Text c="dimmed" size="sm">{periodSuffix(plan)}</Text>
      </Group>
      <Text size="xs" c="dimmed">
        {plan.joiningFee > 0 ? `+ ${money(plan.joiningFee)} joining fee` : 'No joining fee'} · excl. GST
      </Text>
      {pending && <Group mt={6}><ScheduledPriceBadge change={pending} /></Group>}
      {plan.description && <Text size="sm" mt="sm">{plan.description}</Text>}

      {plan.benefits.length > 0 && (
        <List
          mt="sm"
          spacing={4}
          size="sm"
          icon={<ThemeIcon color="teal" size={18} radius="xl" variant="light"><IconCheck size={12} /></ThemeIcon>}
        >
          {plan.benefits.map((b) => <List.Item key={b}>{b}</List.Item>)}
        </List>
      )}

      <Divider my="sm" />
      <Stack gap={6} style={{ flex: 1 }}>
        <Group gap={6}>
          <Badge variant="light" color={access.allBranchAccess ? 'blue' : 'gray'}>
            {access.allBranchAccess ? 'All branches' : 'Home branch only'}
          </Badge>
          {access.accessStartTime && access.accessEndTime && (
            <Badge variant="light" color="orange" leftSection={<IconClock size={12} />}>
              {hhmm(access.accessStartTime)}–{hhmm(access.accessEndTime)}
            </Badge>
          )}
          {access.maxVisitsPerMonth != null && (
            <Badge variant="light" color="grape">{access.maxVisitsPerMonth} visits / month</Badge>
          )}
          <Badge variant="light" color={plan.classCreditsPerMonth === 0 ? 'gray' : 'indigo'}>
            {plan.classCreditsPerMonth == null
              ? 'Unlimited classes'
              : plan.classCreditsPerMonth === 0
                ? 'No group classes'
                : `${plan.classCreditsPerMonth} classes / month`}
          </Badge>
        </Group>
        <Text size="sm">
          <IconSnowflake size={14} style={{ verticalAlign: -2 }} />{' '}
          {freezePolicy.allowed
            ? `Freeze ${freezePolicy.minDays}–${freezePolicy.maxDays} days, up to ${freezePolicy.maxCount}×`
            : 'Freezing not allowed'}
        </Text>
        <Text size="sm">
          <b>Cancellation:</b> {label(cancellation.policy)}
          {cancellation.coolingOffDays > 0 && ` · ${cancellation.coolingOffDays}-day cooling-off`}
          {cancellation.cancellationFee > 0 && ` · fee ${money(cancellation.cancellationFee)}`}
        </Text>
        <Text size="sm" c="dimmed">
          Grace period: {plan.gracePeriodDays} day{plan.gracePeriodDays === 1 ? '' : 's'}
        </Text>
      </Stack>

      {canPrice && (
        <Group mt="md" gap="xs">
          {plan.status !== 'ARCHIVED' && (
            <Button size="xs" variant="light" color="orange" leftSection={<IconTag size={14} />} onClick={onChangePrice}
              disabled={!!pending}
              title={pending ? 'Cancel the scheduled change (price history) before scheduling another' : undefined}>
              Change price
            </Button>
          )}
          <Button size="xs" variant="subtle" leftSection={<IconHistory size={14} />} onClick={onHistory}>Price history</Button>
        </Group>
      )}
      {canEdit && (
        <Group mt="xs" gap="xs">
          {plan.status !== 'ARCHIVED' && (
            <Button size="xs" variant="light" leftSection={<IconEdit size={14} />} onClick={onEdit}>Edit</Button>
          )}
          {plan.status === 'DRAFT' && (
            <Button size="xs" variant="light" color="teal" leftSection={<IconPlayerPlay size={14} />} onClick={() => onStatus('ACTIVE')}>
              Activate
            </Button>
          )}
          {plan.status !== 'ARCHIVED' ? (
            <Button size="xs" variant="subtle" color="red" leftSection={<IconArchive size={14} />} onClick={() => onStatus('ARCHIVED')}>
              Archive
            </Button>
          ) : (
            <Tooltip label="Archived plans cannot be edited or re-activated">
              <Text size="xs" c="dimmed">Archived · read-only</Text>
            </Tooltip>
          )}
        </Group>
      )}
    </Card>
  );
}

// ------------------------------------------------------------------ form

interface PlanFormValues {
  code: string;
  name: string;
  description: string;
  billingPeriod: BillingPeriod;
  customDurationDays: number | string;
  price: number | string;
  joiningFee: number | string;
  allBranchAccess: boolean;
  restrictHours: boolean;
  accessStartTime: string;
  accessEndTime: string;
  maxVisitsPerMonth: number | string;
  classCreditsPerMonth: number | string;
  freezeAllowed: boolean;
  minFreezeDays: number | string;
  maxFreezeDays: number | string;
  maxFreezeCount: number | string;
  cancellationPolicy: CancellationPolicy;
  coolingOffDays: number | string;
  cancellationFee: number | string;
  gracePeriodDays: number | string;
  benefits: string;
  priceChangeReason: string;
}

const toNum = (v: number | string): number | null => (v === '' || v == null ? null : Number(v));
const int = (v: number | string) => toNum(v) ?? 0;
const inRange = (v: number | string, min: number, max: number, msg: string) => {
  const n = toNum(v);
  return n == null || (Number.isInteger(n) && n >= min && n <= max) ? null : msg;
};

function initialValues(plan: Plan | null): PlanFormValues {
  if (!plan) {
    return {
      code: '', name: '', description: '', billingPeriod: 'MONTHLY', customDurationDays: '', price: '', joiningFee: 0,
      allBranchAccess: false, restrictHours: false, accessStartTime: '', accessEndTime: '', maxVisitsPerMonth: '',
      classCreditsPerMonth: '',
      freezeAllowed: false, minFreezeDays: 7, maxFreezeDays: 30, maxFreezeCount: 1, cancellationPolicy: 'NON_REFUNDABLE',
      coolingOffDays: 0, cancellationFee: 0, gracePeriodDays: 3, benefits: '', priceChangeReason: '',
    };
  }
  return {
    code: plan.code,
    name: plan.name,
    description: plan.description ?? '',
    billingPeriod: plan.billingPeriod,
    customDurationDays: plan.billingPeriod === 'CUSTOM' ? plan.durationDays ?? '' : '',
    price: plan.price,
    joiningFee: plan.joiningFee,
    allBranchAccess: plan.access.allBranchAccess,
    restrictHours: !!plan.access.accessStartTime,
    accessStartTime: hhmm(plan.access.accessStartTime),
    accessEndTime: hhmm(plan.access.accessEndTime),
    maxVisitsPerMonth: plan.access.maxVisitsPerMonth ?? '',
    classCreditsPerMonth: plan.classCreditsPerMonth ?? '',
    freezeAllowed: plan.freezePolicy.allowed,
    minFreezeDays: plan.freezePolicy.minDays,
    maxFreezeDays: plan.freezePolicy.maxDays,
    maxFreezeCount: plan.freezePolicy.maxCount,
    cancellationPolicy: plan.cancellation.policy,
    coolingOffDays: plan.cancellation.coolingOffDays,
    cancellationFee: plan.cancellation.cancellationFee,
    gracePeriodDays: plan.gracePeriodDays,
    benefits: plan.benefits.join('\n'),
    priceChangeReason: '',
  };
}

const benefitLines = (s: string) => s.split('\n').map((l) => l.trim()).filter(Boolean);

function PlanForm({ plan, onDone }: { plan: Plan | null; onDone: () => void }) {
  const form = useForm<PlanFormValues>({
    initialValues: initialValues(plan),
    validate: {
      code: (v) => (/^[A-Za-z0-9_-]{2,30}$/.test(v) ? null : '2-30 letters, digits, - or _'),
      name: (v) => (!v.trim() ? 'Required' : v.length > 120 ? 'Max 120 characters' : null),
      description: (v) => (v.length > 1000 ? 'Max 1000 characters' : null),
      customDurationDays: (v, vals) =>
        vals.billingPeriod !== 'CUSTOM' ? null : toNum(v) == null ? 'Required for custom plans' : inRange(v, 1, 730, '1-730 days'),
      price: (v) => (toNum(v) == null ? 'Required' : Number(v) < 0 ? 'Must be ≥ 0' : null),
      joiningFee: (v) => (toNum(v) != null && Number(v) < 0 ? 'Must be ≥ 0' : null),
      accessStartTime: (v, vals) => (vals.restrictHours && !v ? 'Required' : null),
      accessEndTime: (v, vals) =>
        !vals.restrictHours ? null : !v ? 'Required' : vals.accessStartTime && v <= vals.accessStartTime ? 'Must be after start' : null,
      maxVisitsPerMonth: (v) => inRange(v, 1, 100, '1-100 (leave empty for unlimited)'),
      classCreditsPerMonth: (v) => inRange(v, 0, 100, '0-100 (leave empty for unlimited)'),
      minFreezeDays: (v, vals) => (vals.freezeAllowed ? inRange(v, 0, 365, '0-365') : null),
      maxFreezeDays: (v, vals) => {
        if (!vals.freezeAllowed) return null;
        if (int(v) <= 0) return 'Must be > 0';
        if (int(v) < int(vals.minFreezeDays)) return 'Must be ≥ minimum';
        return inRange(v, 1, 365, '1-365');
      },
      maxFreezeCount: (v, vals) => (vals.freezeAllowed ? inRange(v, 1, 12, '1-12') ?? (int(v) < 1 ? '1-12' : null) : null),
      coolingOffDays: (v) => inRange(v, 0, 60, '0-60'),
      cancellationFee: (v) => (toNum(v) != null && Number(v) < 0 ? 'Must be ≥ 0' : null),
      gracePeriodDays: (v) => inRange(v, 0, 60, '0-60'),
      benefits: (v) => {
        const lines = benefitLines(v);
        if (lines.length > 20) return 'At most 20 benefits';
        if (lines.some((l) => l.length > 200)) return 'Each benefit max 200 characters';
        return null;
      },
    },
  });

  const save = useApiMutation<PlanFormValues, Plan>({
    request: (v) => ({
      path: plan ? `/api/v1/plans/${plan.id}` : '/api/v1/plans',
      method: plan ? 'PUT' : 'POST',
      body: {
        code: v.code.trim(),
        name: v.name.trim(),
        description: v.description.trim() || null,
        billingPeriod: v.billingPeriod,
        customDurationDays: v.billingPeriod === 'CUSTOM' ? toNum(v.customDurationDays) : null,
        price: toNum(v.price),
        joiningFee: toNum(v.joiningFee) ?? 0,
        allBranchAccess: v.allBranchAccess,
        accessStartTime: v.restrictHours ? `${v.accessStartTime}:00` : null,
        accessEndTime: v.restrictHours ? `${v.accessEndTime}:00` : null,
        maxVisitsPerMonth: toNum(v.maxVisitsPerMonth),
        classCreditsPerMonth: toNum(v.classCreditsPerMonth),
        freezeAllowed: v.freezeAllowed,
        minFreezeDays: v.freezeAllowed ? int(v.minFreezeDays) : 0,
        maxFreezeDays: v.freezeAllowed ? int(v.maxFreezeDays) : 0,
        maxFreezeCount: v.freezeAllowed ? int(v.maxFreezeCount) : 0,
        cancellationPolicy: v.cancellationPolicy,
        coolingOffDays: int(v.coolingOffDays),
        cancellationFee: toNum(v.cancellationFee) ?? 0,
        gracePeriodDays: int(v.gracePeriodDays),
        benefits: benefitLines(v.benefits),
        priceChangeReason: v.priceChangeReason.trim() || null,
      },
    }),
    invalidate: ['/api/v1/plans'],
    successMessage: (p) => (plan ? `${p.name} updated` : `${p.name} created as a draft`),
    silentError: true,
    onSuccess: onDone,
  });

  const v = form.values;
  const priceEdited = plan != null
    && (toNum(v.price) !== plan.price || (toNum(v.joiningFee) ?? 0) !== plan.joiningFee);
  return (
    <form onSubmit={form.onSubmit((vals) => save.mutate(vals))}>
      <Stack>
        {!plan && (
          <Alert color="blue" variant="light">New plans start as drafts. Activate a plan to put it on sale.</Alert>
        )}
        {plan?.status === 'ACTIVE' && (
          <Alert color="yellow" variant="light">
            This plan is on sale. Changes apply to new subscriptions; existing members keep the terms they bought.
          </Alert>
        )}
        <SimpleGrid cols={{ base: 1, sm: 2 }}>
          <TextInput label="Code" placeholder="GOLD-ANNUAL" withAsterisk {...form.getInputProps('code')} />
          <TextInput label="Name" withAsterisk {...form.getInputProps('name')} />
        </SimpleGrid>
        <Textarea label="Description" autosize minRows={2} {...form.getInputProps('description')} />

        <Divider label="Pricing & duration" labelPosition="left" />
        <SimpleGrid cols={{ base: 1, sm: 2, md: 4 }}>
          <Select label="Billing period" data={PERIODS} allowDeselect={false} withAsterisk {...form.getInputProps('billingPeriod')} />
          {v.billingPeriod === 'CUSTOM' && (
            <NumberInput label="Duration (days)" min={1} max={730} allowDecimal={false} withAsterisk
              {...form.getInputProps('customDurationDays')} />
          )}
          <NumberInput label="Price (₹, excl. GST)" min={0} decimalScale={2} thousandSeparator="," withAsterisk
            {...form.getInputProps('price')} />
          <NumberInput label="Joining fee (₹)" min={0} decimalScale={2} thousandSeparator="," {...form.getInputProps('joiningFee')} />
          <NumberInput label="Grace period (days)" min={0} max={60} allowDecimal={false} {...form.getInputProps('gracePeriodDays')} />
        </SimpleGrid>
        {priceEdited && (
          <Alert color="orange" variant="light" title="Changing the price here applies immediately">
            <Stack gap="xs">
              <Text size="sm">
                New sales and the next renewals use the new price; the change is recorded in the price history. Increases
                on plans with autopay members are blocked here: use <b>Change price</b> on the plan card to schedule
                them with notice and a policy for existing members.
              </Text>
              <TextInput label="Reason for the price change" placeholder="e.g. GST-inclusive pricing correction"
                maxLength={500} {...form.getInputProps('priceChangeReason')} />
            </Stack>
          </Alert>
        )}

        <Divider label="Access rules" labelPosition="left" />
        <Group>
          <Switch label="Access to all branches" {...form.getInputProps('allBranchAccess', { type: 'checkbox' })} />
          <Switch label="Restrict access hours (off-peak)" {...form.getInputProps('restrictHours', { type: 'checkbox' })} />
        </Group>
        <SimpleGrid cols={{ base: 1, sm: 3 }}>
          {v.restrictHours && (
            <>
              <TimeInput label="Access from" {...form.getInputProps('accessStartTime')} />
              <TimeInput label="Access until" {...form.getInputProps('accessEndTime')} />
            </>
          )}
          <NumberInput label="Max visits / month" placeholder="Unlimited" min={1} max={100} allowDecimal={false}
            {...form.getInputProps('maxVisitsPerMonth')} />
          <NumberInput label="Group classes / month" placeholder="Unlimited" min={0} max={100} allowDecimal={false}
            description="0 = classes not included" {...form.getInputProps('classCreditsPerMonth')} />
        </SimpleGrid>

        <Divider label="Freeze policy" labelPosition="left" />
        <Switch label="Members may freeze this membership" {...form.getInputProps('freezeAllowed', { type: 'checkbox' })} />
        {v.freezeAllowed && (
          <SimpleGrid cols={{ base: 1, sm: 3 }}>
            <NumberInput label="Min days per freeze" min={0} max={365} allowDecimal={false} {...form.getInputProps('minFreezeDays')} />
            <NumberInput label="Max days per freeze" min={1} max={365} allowDecimal={false} {...form.getInputProps('maxFreezeDays')} />
            <NumberInput label="Max freezes per term" min={1} max={12} allowDecimal={false} {...form.getInputProps('maxFreezeCount')} />
          </SimpleGrid>
        )}

        <Divider label="Cancellation" labelPosition="left" />
        <SimpleGrid cols={{ base: 1, sm: 3 }}>
          <Select
            label="Policy"
            data={POLICIES.map((p) => ({ value: p.value, label: p.label }))}
            description={POLICIES.find((p) => p.value === v.cancellationPolicy)?.hint}
            allowDeselect={false}
            {...form.getInputProps('cancellationPolicy')}
          />
          <NumberInput label="Cooling-off (days)" min={0} max={60} allowDecimal={false} {...form.getInputProps('coolingOffDays')} />
          <NumberInput label="Cancellation fee (₹)" min={0} decimalScale={2} {...form.getInputProps('cancellationFee')} />
        </SimpleGrid>

        <Textarea
          label="Benefits"
          description="One per line (max 20)"
          autosize
          minRows={3}
          {...form.getInputProps('benefits')}
        />

        {save.error && <Alert color="red" style={{ whiteSpace: 'pre-line' }}>{errorMessage(save.error)}</Alert>}
        <Group justify="flex-end">
          <Button variant="default" onClick={onDone}>Cancel</Button>
          <Button type="submit" loading={save.isPending}>{plan ? 'Save changes' : 'Create draft'}</Button>
        </Group>
      </Stack>
    </form>
  );
}
