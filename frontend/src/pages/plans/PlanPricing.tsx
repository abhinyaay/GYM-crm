import {
  Alert, Badge, Button, Divider, Drawer, Group, List, Modal, NumberInput, Radio, ScrollArea, SegmentedControl,
  SimpleGrid, Stack, Table, Text, Textarea, Timeline, Tooltip,
} from '@mantine/core';
import { DatePickerInput } from '@mantine/dates';
import { useForm } from '@mantine/form';
import { IconBan, IconCalendarDue, IconCheck, IconEdit, IconShieldCheck } from '@tabler/icons-react';
import dayjs from 'dayjs';
import { useState } from 'react';
import {
  PRICING, type ExistingMemberPolicy, type PriceChange, type PriceChangePreview, type PriceChangeRequest,
  type PricingSettings,
} from '@/api/pricing';
import type { Plan } from '@/api/types';
import { QueryState } from '@/components/QueryState';
import { date, dateTime, money } from '@/lib/format';
import { errorMessage } from '@/lib/notify';
import { useApiMutation, useApiQuery } from '@/lib/useApi';

const pctText = (v: number | null) => (v == null ? '' : `${v > 0 ? '+' : ''}${v}%`);

// ------------------------------------------------------------------ badge on plan cards

/** "₹2,500 from 1 Nov" badge for a plan with a pending price change. */
export function ScheduledPriceBadge({ change }: { change: PriceChange }) {
  const feeOnly = change.newPrice === change.oldPrice;
  return (
    <Tooltip
      multiline
      w={260}
      label={`${feeOnly ? 'Joining fee' : 'Price'} changes on ${date(change.effectiveDate)}: ${change.reason}`}
    >
      <Badge color="orange" variant="light" leftSection={<IconCalendarDue size={12} />}>
        {feeOnly ? `Fee ${money(change.newJoiningFee)}` : money(change.newPrice)} from {dayjs(change.effectiveDate).format('D MMM')}
      </Badge>
    </Tooltip>
  );
}

// ------------------------------------------------------------------ change price

interface FormValues {
  newPrice: number | string;
  newJoiningFee: number | string;
  effectiveDate: Date | null;
  policy: ExistingMemberPolicy;
  protectBy: 'months' | 'date';
  protectMonths: number | string;
  protectUntil: Date | null;
  reason: string;
}

const iso = (d: Date | null) => (d ? dayjs(d).format('YYYY-MM-DD') : '');

function toRequest(v: FormValues): PriceChangeRequest {
  const protect = v.policy === 'PROTECT_EXISTING';
  return {
    newPrice: v.newPrice === '' ? null : Number(v.newPrice),
    newJoiningFee: v.newJoiningFee === '' ? null : Number(v.newJoiningFee),
    effectiveDate: iso(v.effectiveDate),
    existingMemberPolicy: v.policy,
    protectMonths: protect && v.protectBy === 'months' ? Number(v.protectMonths) : null,
    protectUntil: protect && v.protectBy === 'date' ? iso(v.protectUntil) : null,
    reason: v.reason.trim(),
  };
}

/** Schedule a price / joining-fee change with a policy for existing members and a preview of who is affected. */
export function ChangePriceModal({ plan, opened, onClose }: { plan: Plan | null; opened: boolean; onClose: () => void }) {
  return (
    <Modal opened={opened} onClose={onClose} title={plan ? `Change price: ${plan.name}` : ''} size="xl">
      {plan && <ChangePriceForm key={plan.id} plan={plan} onDone={onClose} />}
    </Modal>
  );
}

function ChangePriceForm({ plan, onDone }: { plan: Plan; onDone: () => void }) {
  const settings = useApiQuery<PricingSettings>(PRICING.settings);
  const [preview, setPreview] = useState<PriceChangePreview | null>(null);
  const form = useForm<FormValues>({
    initialValues: {
      newPrice: plan.price,
      newJoiningFee: plan.joiningFee,
      effectiveDate: dayjs().add(30, 'day').toDate(),
      policy: 'NEW_PRICE_AT_RENEWAL',
      protectBy: 'months',
      protectMonths: 3,
      protectUntil: null,
      reason: '',
    },
    validate: {
      newPrice: (v) => (v === '' || Number(v) < 0 ? 'Enter a price ≥ 0' : null),
      newJoiningFee: (v) => (v !== '' && Number(v) < 0 ? 'Must be ≥ 0' : null),
      effectiveDate: (v) => (!v ? 'Required' : null),
      protectMonths: (v, vals) =>
        vals.policy === 'PROTECT_EXISTING' && vals.protectBy === 'months' && !(Number(v) >= 1 && Number(v) <= 36)
          ? '1-36 months' : null,
      protectUntil: (v, vals) =>
        vals.policy === 'PROTECT_EXISTING' && vals.protectBy === 'date' && !v ? 'Pick a date' : null,
      reason: (v) => (v.trim().length < 5 ? 'Explain the change (at least 5 characters)' : null),
    },
  });
  const v = form.values;
  const unchanged = Number(v.newPrice) === plan.price && Number(v.newJoiningFee || 0) === plan.joiningFee;
  const increase = Number(v.newPrice) > plan.price;
  const effectiveToday = v.effectiveDate != null && dayjs(v.effectiveDate).isSame(dayjs(), 'day');

  const previewMut = useApiMutation<PriceChangeRequest, PriceChangePreview>({
    request: (body) => ({ path: PRICING.preview(plan.id), body }),
    silentError: true,
    onSuccess: setPreview,
  });
  const schedule = useApiMutation<PriceChangeRequest, PriceChange>({
    request: (body) => ({ path: PRICING.schedule(plan.id), body }),
    invalidate: ['/api/v1/plans', '/api/v1/pricing'],
    successMessage: (c) => (c.status === 'APPLIED' ? `${c.planName} now costs ${money(c.newPrice)}`
      : `Price change scheduled for ${date(c.effectiveDate)}`),
    silentError: true,
    onSuccess: onDone,
  });
  const edited = () => setPreview(null);

  return (
    <form onSubmit={form.onSubmit((vals) => schedule.mutate(toRequest(vals)))}>
      <Stack>
        <Text size="sm" c="dimmed">
          Current price {money(plan.price)}{plan.joiningFee > 0 ? ` + ${money(plan.joiningFee)} joining fee` : ''} (excl. GST).
          Autopay members are told about a rise and are not debited the new price until
          {' '}{settings.data?.priceChangeNoticeDays ?? 7} days after the notice.
        </Text>
        <SimpleGrid cols={{ base: 1, sm: 3 }}>
          <NumberInput label="New price (₹, excl. GST)" min={0} decimalScale={2} thousandSeparator="," withAsterisk
            {...form.getInputProps('newPrice')} onChange={(x) => { form.setFieldValue('newPrice', x); edited(); }} />
          <NumberInput label="New joining fee (₹)" min={0} decimalScale={2} thousandSeparator=","
            {...form.getInputProps('newJoiningFee')} onChange={(x) => { form.setFieldValue('newJoiningFee', x); edited(); }} />
          <DatePickerInput label="Effective from" withAsterisk minDate={new Date()} maxDate={dayjs().add(365, 'day').toDate()}
            {...form.getInputProps('effectiveDate')}
            onChange={(d) => { form.setFieldValue('effectiveDate', d); edited(); }} />
        </SimpleGrid>

        <Radio.Group
          label="Existing members"
          value={v.policy}
          onChange={(p) => { form.setFieldValue('policy', p as ExistingMemberPolicy); edited(); }}
        >
          <Stack gap="xs" mt={6}>
            <Radio value="NEW_PRICE_AT_RENEWAL" label="Pay the new price from their next renewal (with notice)" />
            <Radio value="PROTECT_EXISTING" disabled={!increase}
              label="Keep their current price for a while; only new sales pay the new price straight away" />
          </Stack>
        </Radio.Group>
        {v.policy === 'PROTECT_EXISTING' && (
          <Group align="flex-end">
            <SegmentedControl
              value={v.protectBy}
              onChange={(x) => { form.setFieldValue('protectBy', x as 'months' | 'date'); edited(); }}
              data={[{ value: 'months', label: 'For N months' }, { value: 'date', label: 'Until a date' }]}
            />
            {v.protectBy === 'months' ? (
              <NumberInput label="Months after the effective date" min={1} max={36} allowDecimal={false} w={220}
                {...form.getInputProps('protectMonths')} onChange={(x) => { form.setFieldValue('protectMonths', x); edited(); }} />
            ) : (
              <DatePickerInput label="Protected until" minDate={v.effectiveDate ?? new Date()} w={220}
                {...form.getInputProps('protectUntil')} onChange={(d) => { form.setFieldValue('protectUntil', d); edited(); }} />
            )}
          </Group>
        )}
        <Textarea label="Reason" description="Shown in the price history and in the notice to autopay members" withAsterisk
          autosize minRows={2} maxLength={500} {...form.getInputProps('reason')} />

        {preview && <PreviewPanel preview={preview} />}
        {previewMut.error && <Alert color="red" style={{ whiteSpace: 'pre-line' }}>{errorMessage(previewMut.error)}</Alert>}
        {schedule.error && <Alert color="red" style={{ whiteSpace: 'pre-line' }}>{errorMessage(schedule.error)}</Alert>}

        <Group justify="space-between">
          <Button variant="default" onClick={onDone}>Cancel</Button>
          <Group>
            <Button variant="light" disabled={unchanged} loading={previewMut.isPending}
              onClick={() => { if (!form.validate().hasErrors) previewMut.mutate(toRequest(v)); }}>
              Preview impact
            </Button>
            <Tooltip label="Preview the impact first" disabled={preview !== null}>
              <Button type="submit" disabled={unchanged || preview === null} loading={schedule.isPending}
                color={effectiveToday ? 'orange' : undefined}>
                {effectiveToday ? 'Apply today' : 'Schedule change'}
              </Button>
            </Tooltip>
          </Group>
        </Group>
      </Stack>
    </form>
  );
}

function PreviewPanel({ preview: p }: { preview: PriceChangePreview }) {
  return (
    <Stack gap="xs">
      <Divider label="Impact" labelPosition="left" />
      <SimpleGrid cols={{ base: 2, sm: 4 }}>
        <Stat label="Price" value={`${money(p.currentPrice)} → ${money(p.newPrice)}`} hint={pctText(p.changePct)} />
        <Stat label="Joining fee" value={`${money(p.currentJoiningFee)} → ${money(p.newJoiningFee)}`} />
        <Stat label="Open subscriptions" value={String(p.openSubscriptions)} />
        <Stat label="Autopay members affected" value={`${p.autopayMembersAffected} of ${p.autopaySubscriptions}`}
          hint={p.autopayMembersAffected > 0 ? `notified ${date(p.noticeSentOn)}` : undefined} />
      </SimpleGrid>
      {p.notes.length > 0 && (
        <List size="sm" spacing={2}>
          {p.notes.map((n) => <List.Item key={n}>{n}</List.Item>)}
        </List>
      )}
      {p.autopayMembers.length > 0 && (
        <ScrollArea.Autosize mah={220}>
          <Table striped fz="sm" stickyHeader>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Member</Table.Th>
                <Table.Th>Term ends</Table.Th>
                <Table.Th ta="right">Now</Table.Th>
                <Table.Th ta="right">Renews at</Table.Th>
                <Table.Th>First debit at new price</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {p.autopayMembers.map((m) => (
                <Table.Tr key={m.subscriptionId}>
                  <Table.Td>{m.memberName} <Text span size="xs" c="dimmed">{m.memberCode}</Text></Table.Td>
                  <Table.Td>{date(m.termEnd)}</Table.Td>
                  <Table.Td ta="right">{money(m.currentPrice)}</Table.Td>
                  <Table.Td ta="right">
                    {money(m.newRenewalPrice)}
                    {m.renewalBasis === 'NEGOTIATED' && <Badge size="xs" ml={4} variant="light">negotiated</Badge>}
                  </Table.Td>
                  <Table.Td>{m.firstDebitAtNewPrice ? date(m.firstDebitAtNewPrice) : '—'}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </ScrollArea.Autosize>
      )}
    </Stack>
  );
}

function Stat({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div>
      <Text size="xs" c="dimmed">{label}</Text>
      <Text fw={600} size="sm">{value}</Text>
      {hint && <Text size="xs" c="dimmed">{hint}</Text>}
    </div>
  );
}

// ------------------------------------------------------------------ history

const STATUS_COLOR: Record<PriceChange['status'], string> = { SCHEDULED: 'orange', APPLIED: 'teal', CANCELLED: 'gray' };

/** Full price history of a plan; a scheduled change can be cancelled from here. */
export function PriceHistoryDrawer({ plan, opened, onClose, canManage }: {
  plan: Plan | null;
  opened: boolean;
  onClose: () => void;
  canManage: boolean;
}) {
  const history = useApiQuery<PriceChange[]>(plan && opened ? PRICING.history(plan.id) : null);
  const [cancelling, setCancelling] = useState<PriceChange | null>(null);
  const [reason, setReason] = useState('');
  const cancel = useApiMutation<{ id: number; reason: string }, PriceChange>({
    request: ({ id, reason: r }) => ({ path: PRICING.cancel(id), body: { reason: r } }),
    invalidate: ['/api/v1/pricing', '/api/v1/plans'],
    successMessage: 'Scheduled price change cancelled',
    silentError: true,
    onSuccess: () => { setCancelling(null); setReason(''); },
  });

  return (
    <Drawer opened={opened} onClose={onClose} position="right" size="md" title={plan ? `Price history: ${plan.name}` : ''}>
      <QueryState isLoading={history.isLoading} error={history.error} empty={history.data?.length === 0}
        emptyText="No price changes yet. The plan still has the price it was created with.">
        <Timeline active={-1} bulletSize={22} lineWidth={2}>
          {history.data?.map((c) => (
            <Timeline.Item
              key={c.id}
              color={STATUS_COLOR[c.status]}
              bullet={c.status === 'APPLIED' ? <IconCheck size={12} /> : c.status === 'CANCELLED' ? <IconBan size={12} />
                : <IconCalendarDue size={12} />}
              title={
                <Group gap={6}>
                  <Text fw={600} size="sm">{money(c.oldPrice)} → {money(c.newPrice)}</Text>
                  {c.changePct != null && c.changePct !== 0 && (
                    <Text size="xs" c={c.changePct > 0 ? 'red' : 'teal'}>{pctText(c.changePct)}</Text>
                  )}
                  <Badge size="xs" color={STATUS_COLOR[c.status]} variant="light">{c.status.toLowerCase()}</Badge>
                  {c.source === 'DIRECT_EDIT' && (
                    <Badge size="xs" variant="outline" color="gray" leftSection={<IconEdit size={10} />}>plan edit</Badge>
                  )}
                </Group>
              }
            >
              <Stack gap={2}>
                {c.oldJoiningFee !== c.newJoiningFee && (
                  <Text size="xs">Joining fee {money(c.oldJoiningFee)} → {money(c.newJoiningFee)}</Text>
                )}
                <Text size="xs">Effective {date(c.effectiveDate)}{c.appliedAt ? ` · applied ${dateTime(c.appliedAt)}` : ''}</Text>
                {c.existingMemberPolicy === 'PROTECT_EXISTING' && (
                  <Text size="xs" c="blue">
                    <IconShieldCheck size={12} style={{ verticalAlign: -2 }} /> Existing members protected until {date(c.protectedUntil)}
                    {c.protectedCount != null ? ` (${c.protectedCount} subscriptions)` : ''}
                  </Text>
                )}
                <Text size="xs" c="dimmed">“{c.reason}” · {c.createdByName ?? 'system'}, {dateTime(c.createdAt)}</Text>
                {c.notifiedCount > 0 && <Text size="xs" c="dimmed">{c.notifiedCount} autopay member(s) notified</Text>}
                {c.status === 'CANCELLED' && (
                  <Text size="xs" c="dimmed">Cancelled by {c.cancelledByName ?? '—'}: {c.cancelReason}</Text>
                )}
                {c.status === 'SCHEDULED' && canManage && (
                  cancelling?.id === c.id ? (
                    <Stack gap={4} mt={4}>
                      <Textarea size="xs" placeholder="Why is it cancelled?" value={reason} autosize minRows={1}
                        onChange={(e) => setReason(e.currentTarget.value)} />
                      {cancel.error && <Alert color="red" p={6}>{errorMessage(cancel.error)}</Alert>}
                      <Group gap={6}>
                        <Button size="compact-xs" color="red" disabled={reason.trim().length < 3} loading={cancel.isPending}
                          onClick={() => cancel.mutate({ id: c.id, reason: reason.trim() })}>Cancel change</Button>
                        <Button size="compact-xs" variant="default" onClick={() => setCancelling(null)}>Keep</Button>
                      </Group>
                    </Stack>
                  ) : (
                    <Button size="compact-xs" variant="subtle" color="red" mt={4} w="fit-content"
                      leftSection={<IconBan size={12} />} onClick={() => { setCancelling(c); setReason(''); }}>
                      Cancel this change
                    </Button>
                  )
                )}
              </Stack>
            </Timeline.Item>
          ))}
        </Timeline>
      </QueryState>
    </Drawer>
  );
}

