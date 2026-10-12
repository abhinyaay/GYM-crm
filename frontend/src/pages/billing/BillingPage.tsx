import {
  ActionIcon, Alert, Anchor, Badge, Button, Divider, Drawer, Group, Modal, NumberInput, SegmentedControl, Select,
  SimpleGrid, Stack, Switch, Table, Tabs, Text, Textarea, TextInput, Tooltip,
} from '@mantine/core';
import { DateInput } from '@mantine/dates';
import { useForm } from '@mantine/form';
import { IconCash, IconEye, IconPlus, IconReceipt, IconReceiptRefund, IconRepeat, IconTicket } from '@tabler/icons-react';
import dayjs from 'dayjs';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { newIdempotencyKey } from '@/api/client';
import { ALL_METHODS, METHOD_LABELS } from '@/api/payments';
import { AutopayTab } from './AutopayTab';
import type {
  Discount, Invoice, InvoiceStatus, Page, Payment, PaymentMethod, PaymentStatus, Refund,
} from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { hasAnyRole, MANAGERS, ORG_WIDE } from '@/auth/roles';
import { BranchSelect, useBranchNames } from '@/components/BranchSelect';
import { InvoiceSummary } from '@/components/billing/InvoiceSummary';
import { TaxDocumentButton } from '@/components/billing/TaxDocumentButton';
import type { RefundWithCreditNote } from '@/api/pricing';
import { PayInvoiceModal } from '@/components/billing/PayInvoiceModal';
import { PageHeader } from '@/components/PageHeader';
import { Pager } from '@/components/Pager';
import { QueryState } from '@/components/QueryState';
import { StatusBadge } from '@/components/StatusBadge';
import { date, dateTime, label, money } from '@/lib/format';
import { errorMessage } from '@/lib/notify';
import { useApiMutation, useApiQuery } from '@/lib/useApi';

const PAGE_SIZE = 20;
const iso = (d: Date | null) => (d ? dayjs(d).format('YYYY-MM-DD') : undefined);
const options = (values: string[]) => values.map((v) => ({ value: v, label: METHOD_LABELS[v as PaymentMethod] ?? label(v) }));

const INVOICE_STATUSES: InvoiceStatus[] = ['ISSUED', 'PAID', 'VOID', 'PARTIALLY_REFUNDED', 'REFUNDED'];
const PAYMENT_STATUSES: PaymentStatus[] = ['SUCCEEDED', 'FAILED', 'PARTIALLY_REFUNDED', 'REFUNDED'];
const METHODS: PaymentMethod[] = ALL_METHODS;

export default function BillingPage() {
  const { user } = useAuth();
  const isManager = hasAnyRole(user, MANAGERS);
  const [tab, setTab] = useState<string | null>('invoices');
  return (
    <>
      <PageHeader title="Billing" subtitle="Invoices, payments, refunds and discount codes" />
      <Tabs value={tab} onChange={setTab} keepMounted={false}>
        <Tabs.List mb="md">
          <Tabs.Tab value="invoices" leftSection={<IconReceipt size={16} />}>Invoices</Tabs.Tab>
          <Tabs.Tab value="payments" leftSection={<IconCash size={16} />}>Payments</Tabs.Tab>
          <Tabs.Tab value="autopay" leftSection={<IconRepeat size={16} />}>Autopay</Tabs.Tab>
          {isManager && <Tabs.Tab value="discounts" leftSection={<IconTicket size={16} />}>Discounts</Tabs.Tab>}
        </Tabs.List>
        <Tabs.Panel value="invoices"><InvoicesTab /></Tabs.Panel>
        <Tabs.Panel value="payments"><PaymentsTab /></Tabs.Panel>
        <Tabs.Panel value="autopay"><AutopayTab /></Tabs.Panel>
        {isManager && <Tabs.Panel value="discounts"><DiscountsTab /></Tabs.Panel>}
      </Tabs>
    </>
  );
}

/** Branch filter: org-wide roles get "All branches", branch staff can clear back to "all my branches". */
function BranchFilter({ value, onChange }: { value: number | null; onChange: (v: number | null) => void }) {
  const { user } = useAuth();
  const orgWide = hasAnyRole(user, ORG_WIDE);
  return (
    <BranchSelect
      value={value}
      onChange={onChange}
      allowAll
      clearable={!orgWide}
      placeholder="All my branches"
      label="Branch"
      w={220}
    />
  );
}

// ------------------------------------------------------------------ invoices

function InvoicesTab() {
  const branchNames = useBranchNames();
  const [view, setView] = useState<'all' | 'outstanding'>('all');
  const [status, setStatus] = useState<string | null>(null);
  const [overdue, setOverdue] = useState(false);
  const [branchId, setBranchId] = useState<number | null>(null);
  const [from, setFrom] = useState<Date | null>(null);
  const [to, setTo] = useState<Date | null>(null);
  const [sort, setSort] = useState<string>('issuedAt,desc');
  const [page, setPage] = useState(0);
  const [payId, setPayId] = useState<number | null>(null);
  const [viewing, setViewing] = useState<Invoice | null>(null);

  const reset = <T,>(set: (v: T) => void) => (v: T) => { set(v); setPage(0); };
  const outstanding = view === 'outstanding';
  const query = useApiQuery<Page<Invoice>>(
    outstanding ? '/api/v1/payments/invoices/outstanding' : '/api/v1/payments/invoices',
    outstanding
      ? { branchId: branchId ?? undefined, overdue: overdue || undefined, page, size: PAGE_SIZE }
      : {
        status: status ?? undefined, overdue: overdue || undefined, branchId: branchId ?? undefined,
        from: iso(from), to: iso(to), page, size: PAGE_SIZE, sort,
      },
  );
  const rows = query.data?.content ?? [];

  return (
    <>
      <Group mb="md" align="flex-end" wrap="wrap">
        <SegmentedControl
          value={view}
          onChange={(v) => { setView(v as 'all' | 'outstanding'); setPage(0); }}
          data={[{ value: 'all', label: 'All invoices' }, { value: 'outstanding', label: 'Outstanding' }]}
        />
        <BranchFilter value={branchId} onChange={reset(setBranchId)} />
        {!outstanding && (
          <>
            <Select label="Status" placeholder="Any status" clearable data={options(INVOICE_STATUSES)} value={status}
              onChange={reset(setStatus)} w={170} />
            <DateInput label="Issued from" clearable valueFormat="DD MMM YYYY" value={from} onChange={reset(setFrom)} w={150}
              maxDate={to ?? undefined} />
            <DateInput label="Issued to" clearable valueFormat="DD MMM YYYY" value={to} onChange={reset(setTo)} w={150}
              minDate={from ?? undefined} />
            <Select label="Sort" allowDeselect={false} value={sort} onChange={(v) => v && reset(setSort)(v)} w={170}
              data={[
                { value: 'issuedAt,desc', label: 'Newest first' },
                { value: 'issuedAt,asc', label: 'Oldest first' },
                { value: 'dueDate,asc', label: 'Due date' },
                { value: 'totalAmount,desc', label: 'Highest amount' },
              ]} />
          </>
        )}
        <Switch label="Overdue only" checked={overdue} onChange={(e) => reset(setOverdue)(e.currentTarget.checked)} mb={8} />
      </Group>

      <QueryState isLoading={query.isLoading} error={query.error} empty={rows.length === 0}
        emptyText={outstanding ? 'No outstanding invoices — all caught up.' : 'No invoices match these filters.'}>
        <Table.ScrollContainer minWidth={900}>
          <Table striped highlightOnHover verticalSpacing="sm">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Invoice</Table.Th>
                <Table.Th>Member</Table.Th>
                <Table.Th>Branch</Table.Th>
                <Table.Th>Issued</Table.Th>
                <Table.Th>Due</Table.Th>
                <Table.Th ta="right">Total</Table.Th>
                <Table.Th ta="right">Paid</Table.Th>
                <Table.Th>Status</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((inv) => (
                <Table.Tr key={inv.id}>
                  <Table.Td>
                    <Anchor component="button" type="button" size="sm" fw={600} style={{ whiteSpace: 'nowrap' }} onClick={() => setViewing(inv)}>
                      {inv.invoiceNumber}
                    </Anchor>
                  </Table.Td>
                  <Table.Td>
                    <Anchor component={Link} to={`/members/${inv.memberId}`} size="sm">{inv.memberName}</Anchor>
                    <Text size="xs" c="dimmed">{inv.memberCode}</Text>
                  </Table.Td>
                  <Table.Td><Text size="sm">{branchNames[inv.branchId] ?? `#${inv.branchId}`}</Text></Table.Td>
                  <Table.Td><Text size="sm">{date(inv.issuedAt)}</Text></Table.Td>
                  <Table.Td>
                    <Group gap={6} wrap="nowrap">
                      <Text size="sm" c={inv.overdue ? 'red' : undefined}>{date(inv.dueDate)}</Text>
                      {inv.overdue && <Badge color="red" size="xs" variant="filled">Overdue</Badge>}
                    </Group>
                  </Table.Td>
                  <Table.Td ta="right"><Text size="sm" fw={600}>{money(inv.totalAmount)}</Text></Table.Td>
                  <Table.Td ta="right"><Text size="sm">{money(inv.amountPaid)}</Text></Table.Td>
                  <Table.Td><StatusBadge value={inv.status} /></Table.Td>
                  <Table.Td>
                    <Group gap={4} justify="flex-end" wrap="nowrap">
                      <Tooltip label="Details">
                        <ActionIcon variant="subtle" onClick={() => setViewing(inv)} aria-label="Invoice details">
                          <IconEye size={16} />
                        </ActionIcon>
                      </Tooltip>
                      <TaxDocumentButton compact doc={{ kind: 'invoice', invoiceId: inv.id, number: inv.invoiceNumber }} />
                      {inv.status === 'ISSUED' && (
                        <Button size="compact-xs" leftSection={<IconCash size={14} />} onClick={() => setPayId(inv.id)}>
                          Collect
                        </Button>
                      )}
                    </Group>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
        <Pager page={query.data} onChange={setPage} />
      </QueryState>

      <PayInvoiceModal invoiceId={payId} opened={payId !== null} onClose={() => setPayId(null)} />

      <Modal opened={viewing !== null} onClose={() => setViewing(null)} title="Invoice details" centered size="md">
        {viewing && (
          <Stack>
            <InvoiceSummary invoice={viewing} />
            <Divider />
            <SimpleGrid cols={2} spacing="xs">
              <Text size="sm" c="dimmed">Member</Text>
              <Anchor component={Link} to={`/members/${viewing.memberId}`} size="sm">{viewing.memberName} ({viewing.memberCode})</Anchor>
              <Text size="sm" c="dimmed">Branch</Text>
              <Text size="sm">{branchNames[viewing.branchId] ?? `#${viewing.branchId}`}</Text>
              <Text size="sm" c="dimmed">Issued</Text>
              <Text size="sm">{dateTime(viewing.issuedAt)}</Text>
              <Text size="sm" c="dimmed">Paid</Text>
              <Text size="sm">{viewing.paidAt ? `${money(viewing.amountPaid)} on ${dateTime(viewing.paidAt)}` : '—'}</Text>
              {viewing.amountRefunded > 0 && (
                <>
                  <Text size="sm" c="dimmed">Refunded</Text>
                  <Text size="sm" c="grape">{money(viewing.amountRefunded)}</Text>
                </>
              )}
              {viewing.subscriptionId && (
                <>
                  <Text size="sm" c="dimmed">Subscription</Text>
                  <Text size="sm">#{viewing.subscriptionId}</Text>
                </>
              )}
            </SimpleGrid>
            <Group>
              <TaxDocumentButton doc={{ kind: 'invoice', invoiceId: viewing.id, number: viewing.invoiceNumber }}
                label="Download GST tax invoice (PDF)" />
            </Group>
            {viewing.status === 'ISSUED' && (
              <Button leftSection={<IconCash size={16} />} onClick={() => { setPayId(viewing.id); setViewing(null); }}>
                Collect payment
              </Button>
            )}
          </Stack>
        )}
      </Modal>
    </>
  );
}

// ------------------------------------------------------------------ payments

function PaymentsTab() {
  const [status, setStatus] = useState<string | null>(null);
  const [method, setMethod] = useState<string | null>(null);
  const [branchId, setBranchId] = useState<number | null>(null);
  const [from, setFrom] = useState<Date | null>(null);
  const [to, setTo] = useState<Date | null>(null);
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<Payment | null>(null);
  const reset = <T,>(set: (v: T) => void) => (v: T) => { set(v); setPage(0); };

  const query = useApiQuery<Page<Payment>>('/api/v1/payments', {
    status: status ?? undefined, method: method ?? undefined, branchId: branchId ?? undefined,
    from: iso(from), to: iso(to), page, size: PAGE_SIZE,
  });
  const rows = query.data?.content ?? [];

  return (
    <>
      <Group mb="md" align="flex-end" wrap="wrap">
        <BranchFilter value={branchId} onChange={reset(setBranchId)} />
        <Select label="Status" placeholder="Any status" clearable data={options(PAYMENT_STATUSES)} value={status}
          onChange={reset(setStatus)} w={170} />
        <Select label="Method" placeholder="Any method" clearable data={options(METHODS)} value={method}
          onChange={reset(setMethod)} w={160} />
        <DateInput label="From" clearable valueFormat="DD MMM YYYY" value={from} onChange={reset(setFrom)} w={150}
          maxDate={to ?? undefined} />
        <DateInput label="To" clearable valueFormat="DD MMM YYYY" value={to} onChange={reset(setTo)} w={150}
          minDate={from ?? undefined} />
      </Group>

      <QueryState isLoading={query.isLoading} error={query.error} empty={rows.length === 0} emptyText="No payments match these filters.">
        <Table.ScrollContainer minWidth={860}>
          <Table striped highlightOnHover verticalSpacing="sm">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Processed</Table.Th>
                <Table.Th>Invoice</Table.Th>
                <Table.Th>Member</Table.Th>
                <Table.Th>Method</Table.Th>
                <Table.Th ta="right">Amount</Table.Th>
                <Table.Th ta="right">Refunded</Table.Th>
                <Table.Th>Status</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((p) => (
                <Table.Tr key={p.id}>
                  <Table.Td><Text size="sm">{dateTime(p.processedAt)}</Text></Table.Td>
                  <Table.Td><Text size="sm">{p.invoiceNumber}</Text></Table.Td>
                  <Table.Td>
                    <Anchor component={Link} to={`/members/${p.memberId}`} size="sm">{p.memberName}</Anchor>
                    <Text size="xs" c="dimmed">{p.memberCode}</Text>
                  </Table.Td>
                  <Table.Td>
                    <Group gap={4} wrap="nowrap">
                      <Badge variant="outline" color="gray" size="sm">{METHOD_LABELS[p.method] ?? label(p.method)}</Badge>
                      {p.autopay && <Badge variant="light" color="teal" size="sm">Autopay</Badge>}
                    </Group>
                    {p.instrument && <Text size="xs" c="dimmed">{p.instrument}</Text>}
                  </Table.Td>
                  <Table.Td ta="right"><Text size="sm" fw={600}>{money(p.amount)}</Text></Table.Td>
                  <Table.Td ta="right">
                    <Text size="sm" c={p.refundedAmount > 0 ? 'grape' : 'dimmed'}>
                      {p.refundedAmount > 0 ? money(p.refundedAmount) : '—'}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <StatusBadge value={p.status} />
                    {p.failureReason && <Text size="xs" c="red">{p.failureReason}</Text>}
                  </Table.Td>
                  <Table.Td>
                    <Group justify="flex-end">
                      <Button size="compact-xs" variant="light" onClick={() => setSelected(p)}>Details</Button>
                    </Group>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
        <Pager page={query.data} onChange={setPage} />
      </QueryState>

      <Drawer opened={selected !== null} onClose={() => setSelected(null)} position="right" size="md" title="Payment details">
        {selected && <PaymentDetails key={selected.id} paymentId={selected.id} fallback={selected} />}
      </Drawer>
    </>
  );
}

function PaymentDetails({ paymentId, fallback }: { paymentId: number; fallback: Payment }) {
  const { user } = useAuth();
  const canRefund = hasAnyRole(user, MANAGERS);
  // re-read the payment so the refundable balance is fresh after a refund
  const { data } = useApiQuery<Payment>(`/api/v1/payments/${paymentId}`);
  const payment = data ?? fallback;
  const refunds = useApiQuery<Refund[]>(`/api/v1/payments/${paymentId}/refunds`);
  const [refundOpen, setRefundOpen] = useState(false);
  const refundable = Math.round((payment.amount - payment.refundedAmount) * 100) / 100;
  const refundAllowed = canRefund && payment.status !== 'FAILED' && refundable > 0;

  return (
    <Stack>
      <Group justify="space-between">
        <Text fw={700} fz="xl">{money(payment.amount)}</Text>
        <StatusBadge value={payment.status} size="lg" />
      </Group>
      <SimpleGrid cols={2} spacing="xs">
        <Text size="sm" c="dimmed">Invoice</Text><Text size="sm">{payment.invoiceNumber}</Text>
        <Text size="sm" c="dimmed">Member</Text>
        <Anchor component={Link} to={`/members/${payment.memberId}`} size="sm">{payment.memberName} ({payment.memberCode})</Anchor>
        <Text size="sm" c="dimmed">Method</Text>
        <Text size="sm">
          {METHOD_LABELS[payment.method] ?? label(payment.method)}
          {payment.emiTenureMonths ? ` · ${payment.emiTenureMonths}-month EMI` : ''}
          {payment.autopay ? ' · autopay' : ''}
        </Text>
        <Text size="sm" c="dimmed">Instrument</Text><Text size="sm">{payment.instrument ?? '—'}</Text>
        <Text size="sm" c="dimmed">Provider</Text><Text size="sm">{label(payment.provider)}</Text>
        <Text size="sm" c="dimmed">Processed</Text><Text size="sm">{dateTime(payment.processedAt)}</Text>
        <Text size="sm" c="dimmed">Reference</Text><Text size="sm" ff="monospace" style={{ wordBreak: 'break-all' }}>{payment.gatewayReference ?? '—'}</Text>
        <Text size="sm" c="dimmed">Refunded</Text><Text size="sm">{money(payment.refundedAmount)}</Text>
        <Text size="sm" c="dimmed">Refundable</Text><Text size="sm" fw={600}>{money(refundable)}</Text>
      </SimpleGrid>
      {payment.failureReason && <Alert color="red">Declined: {payment.failureReason}</Alert>}

      <Divider label="Refunds" labelPosition="left" />
      <QueryState isLoading={refunds.isLoading} error={refunds.error} empty={refunds.data?.length === 0} emptyText="No refunds.">
        <Stack gap="xs">
          {refunds.data?.map((r) => (
            <Group key={r.id} justify="space-between" align="flex-start" wrap="nowrap">
              <div style={{ minWidth: 0 }}>
                <Text size="sm">{r.reason}</Text>
                <Text size="xs" c="dimmed" style={{ wordBreak: 'break-all' }}>{dateTime(r.processedAt)} · {r.gatewayReference ?? '—'}</Text>
                {(r as RefundWithCreditNote).creditNoteNumber && (
                  <Text size="xs" c="dimmed">Credit note {(r as RefundWithCreditNote).creditNoteNumber}</Text>
                )}
              </div>
              <Stack gap={2} align="flex-end">
                <Text size="sm" fw={600} c="grape" style={{ whiteSpace: 'nowrap' }}>−{money(r.amount)}</Text>
                <Group gap={2} wrap="nowrap">
                  {r.status === 'SUCCEEDED' && (
                    <TaxDocumentButton compact
                      doc={{ kind: 'creditNote', refundId: r.id, number: (r as RefundWithCreditNote).creditNoteNumber }} />
                  )}
                  <StatusBadge value={r.status} size="xs" />
                </Group>
              </Stack>
            </Group>
          ))}
        </Stack>
      </QueryState>

      {refundAllowed && (
        <Button color="grape" variant="light" leftSection={<IconReceiptRefund size={16} />} onClick={() => setRefundOpen(true)}>
          Issue refund
        </Button>
      )}
      <Modal opened={refundOpen} onClose={() => setRefundOpen(false)} title="Refund payment" centered>
        {refundOpen && <RefundForm payment={payment} refundable={refundable} onDone={() => setRefundOpen(false)} />}
      </Modal>
    </Stack>
  );
}

function RefundForm({ payment, refundable, onDone }: { payment: Payment; refundable: number; onDone: () => void }) {
  // one key per form opening: a double-click or network retry replays instead of refunding twice
  const [idempotencyKey] = useState(() => newIdempotencyKey('refund'));
  const creditOnly = payment.provider === 'INTERNAL';
  const [destination, setDestination] = useState<'ORIGINAL_METHOD' | 'STORE_CREDIT'>(creditOnly ? 'STORE_CREDIT' : 'ORIGINAL_METHOD');
  const form = useForm<{ amount: number | string; reason: string }>({
    initialValues: { amount: refundable, reason: '' },
    validate: {
      amount: (v) => {
        const n = Number(v);
        if (v === '' || Number.isNaN(n) || n < 0.01) return 'At least ₹0.01';
        return n > refundable ? `At most ${money(refundable)}` : null;
      },
      reason: (v) => (!v.trim() ? 'Required' : v.length > 300 ? 'Max 300 characters' : null),
    },
  });
  const refund = useApiMutation<{ amount: number; reason: string }, Refund>({
    request: (body) => ({
      path: `/api/v1/payments/${payment.id}/refunds`,
      headers: { 'Idempotency-Key': idempotencyKey },
      body: { ...body, destination },
    }),
    invalidate: ['/api/v1/payments', '/api/v1/subscriptions', '/api/v1/members', '/api/v1/dashboard'],
    successMessage: (r) => `Refunded ${money(r.amount)}${r.destination === 'STORE_CREDIT' ? ' to store credit' : ''}`,
    silentError: true,
    onSuccess: onDone,
  });
  const amount = Number(form.values.amount) || 0;
  return (
    <form onSubmit={form.onSubmit((v) => refund.mutate({ amount: Number(v.amount), reason: v.reason.trim() }))}>
      <Stack>
        <Text size="sm">
          Payment of {money(payment.amount)} via {label(payment.method)} for {payment.invoiceNumber}. Up to{' '}
          <b>{money(refundable)}</b> can still be refunded.
        </Text>
        <NumberInput label="Refund amount (₹)" min={0.01} max={refundable} decimalScale={2} thousandSeparator="," withAsterisk
          {...form.getInputProps('amount')} />
        <Textarea label="Reason" withAsterisk autosize minRows={2} maxLength={300} {...form.getInputProps('reason')} />
        <div>
          <Text size="sm" fw={500} mb={4}>Refund to</Text>
          <SegmentedControl fullWidth value={destination} disabled={creditOnly}
            onChange={(v) => setDestination(v as 'ORIGINAL_METHOD' | 'STORE_CREDIT')}
            data={[{ value: 'ORIGINAL_METHOD', label: payment.provider === 'OFFLINE' ? 'Cash / bank (at desk)' : 'Original payment method' },
              { value: 'STORE_CREDIT', label: 'Store credit' }]} />
        </div>
        {refund.error && <Alert color="red">{errorMessage(refund.error)}</Alert>}
        <Alert color="yellow" variant="light">
          {destination === 'STORE_CREDIT'
            ? 'The amount is added to the member\'s store credit immediately.'
            : payment.provider === 'OFFLINE'
              ? 'Hand the money back at the desk; the refund is recorded immediately.'
              : 'Refunds are sent to the payment provider immediately and cannot be undone.'}
        </Alert>
        <Group justify="flex-end">
          <Button variant="default" onClick={onDone}>Cancel</Button>
          <Button type="submit" color="grape" loading={refund.isPending}>Refund {money(amount)}</Button>
        </Group>
      </Stack>
    </form>
  );
}

// ------------------------------------------------------------------ discounts

function DiscountsTab() {
  const { user } = useAuth();
  const isAdmin = hasAnyRole(user, ORG_WIDE);
  const [page, setPage] = useState(0);
  const [creating, setCreating] = useState(false);
  const [deactivating, setDeactivating] = useState<Discount | null>(null);
  const query = useApiQuery<Page<Discount>>('/api/v1/payments/discounts', { page, size: PAGE_SIZE });
  const rows = query.data?.content ?? [];
  const deactivate = useApiMutation<number, Discount>({
    request: (id) => ({ path: `/api/v1/payments/discounts/${id}/deactivate`, method: 'PATCH' }),
    invalidate: ['/api/v1/payments/discounts'],
    successMessage: (d) => `${d.code} deactivated`,
    onSuccess: () => setDeactivating(null),
  });
  const today = dayjs().format('YYYY-MM-DD');

  return (
    <>
      {isAdmin && (
        <Group justify="flex-end" mb="md">
          <Button leftSection={<IconPlus size={16} />} onClick={() => setCreating(true)}>New discount code</Button>
        </Group>
      )}
      <QueryState isLoading={query.isLoading} error={query.error} empty={rows.length === 0} emptyText="No discount codes yet.">
        <Table.ScrollContainer minWidth={760}>
          <Table striped highlightOnHover verticalSpacing="sm">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Code</Table.Th>
                <Table.Th>Discount</Table.Th>
                <Table.Th>Valid</Table.Th>
                <Table.Th>Redemptions</Table.Th>
                <Table.Th>Status</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((d) => {
                const expired = d.validUntil != null && d.validUntil < today;
                const exhausted = d.maxRedemptions != null && d.timesRedeemed >= d.maxRedemptions;
                return (
                  <Table.Tr key={d.id}>
                    <Table.Td>
                      <Text fw={600} ff="monospace" size="sm">{d.code}</Text>
                      {d.description && <Text size="xs" c="dimmed">{d.description}</Text>}
                    </Table.Td>
                    <Table.Td>
                      <Text size="sm" fw={600}>{d.discountType === 'PERCENTAGE' ? `${d.value}% off` : `${money(d.value)} off`}</Text>
                    </Table.Td>
                    <Table.Td>
                      <Text size="sm">{date(d.validFrom)} – {d.validUntil ? date(d.validUntil) : 'no end'}</Text>
                    </Table.Td>
                    <Table.Td>
                      <Text size="sm">{d.timesRedeemed}{d.maxRedemptions != null ? ` / ${d.maxRedemptions}` : ' (unlimited)'}</Text>
                    </Table.Td>
                    <Table.Td>
                      {!d.active ? <Badge color="gray" variant="light">Inactive</Badge>
                        : expired ? <Badge color="orange" variant="light">Expired</Badge>
                          : exhausted ? <Badge color="orange" variant="light">Fully redeemed</Badge>
                            : <Badge color="teal" variant="light">Active</Badge>}
                    </Table.Td>
                    <Table.Td>
                      {isAdmin && d.active && (
                        <Group justify="flex-end">
                          <Button size="compact-xs" color="red" variant="subtle" onClick={() => setDeactivating(d)}>Deactivate</Button>
                        </Group>
                      )}
                    </Table.Td>
                  </Table.Tr>
                );
              })}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
        <Pager page={query.data} onChange={setPage} />
      </QueryState>

      <Modal opened={creating} onClose={() => setCreating(false)} title="New discount code" centered size="lg">
        {creating && <DiscountForm onDone={() => setCreating(false)} />}
      </Modal>

      <Modal opened={deactivating !== null} onClose={() => setDeactivating(null)} title="Deactivate discount" centered>
        {deactivating && (
          <Stack>
            <Text size="sm">
              Deactivate <b>{deactivating.code}</b>? It can no longer be applied to new invoices. This cannot be undone.
            </Text>
            <Group justify="flex-end">
              <Button variant="default" onClick={() => setDeactivating(null)}>Cancel</Button>
              <Button color="red" loading={deactivate.isPending} onClick={() => deactivate.mutate(deactivating.id)}>Deactivate</Button>
            </Group>
          </Stack>
        )}
      </Modal>
    </>
  );
}

interface DiscountValues {
  code: string;
  description: string;
  discountType: 'PERCENTAGE' | 'FIXED_AMOUNT';
  value: number | string;
  validFrom: Date | null;
  validUntil: Date | null;
  maxRedemptions: number | string;
}

function DiscountForm({ onDone }: { onDone: () => void }) {
  const form = useForm<DiscountValues>({
    initialValues: {
      code: '', description: '', discountType: 'PERCENTAGE', value: '', validFrom: new Date(), validUntil: null, maxRedemptions: '',
    },
    validate: {
      code: (v) => (/^[A-Za-z0-9_-]{3,30}$/.test(v) ? null : '3-30 letters, digits, - or _'),
      description: (v) => (v.length > 300 ? 'Max 300 characters' : null),
      value: (v, vals) => {
        const n = Number(v);
        if (v === '' || Number.isNaN(n) || n < 0.01) return 'Must be at least 0.01';
        return vals.discountType === 'PERCENTAGE' && n > 100 ? 'Percentage cannot exceed 100' : null;
      },
      validFrom: (v) => (v ? null : 'Required'),
      validUntil: (v, vals) => (v && vals.validFrom && dayjs(v).isBefore(vals.validFrom, 'day') ? 'Must not be before start' : null),
      maxRedemptions: (v) => (v === '' || (Number.isInteger(Number(v)) && Number(v) >= 1) ? null : 'At least 1'),
    },
  });
  const create = useApiMutation<DiscountValues, Discount>({
    request: (v) => ({
      path: '/api/v1/payments/discounts',
      body: {
        code: v.code.trim(),
        description: v.description.trim() || null,
        discountType: v.discountType,
        value: Number(v.value),
        validFrom: iso(v.validFrom),
        validUntil: iso(v.validUntil) ?? null,
        maxRedemptions: v.maxRedemptions === '' ? null : Number(v.maxRedemptions),
      },
    }),
    invalidate: ['/api/v1/payments/discounts'],
    successMessage: (d) => `Discount ${d.code} created`,
    silentError: true,
    onSuccess: onDone,
  });
  const pct = form.values.discountType === 'PERCENTAGE';
  return (
    <form onSubmit={form.onSubmit((v) => create.mutate(v))}>
      <Stack>
        <SimpleGrid cols={{ base: 1, sm: 2 }}>
          <TextInput label="Code" placeholder="SUMMER10" withAsterisk {...form.getInputProps('code')} />
          <SegmentedControl
            mt={24}
            data={[{ value: 'PERCENTAGE', label: 'Percentage' }, { value: 'FIXED_AMOUNT', label: 'Fixed amount' }]}
            {...form.getInputProps('discountType')}
          />
        </SimpleGrid>
        <TextInput label="Description" {...form.getInputProps('description')} />
        <SimpleGrid cols={{ base: 1, sm: 2 }}>
          <NumberInput label={pct ? 'Percent off' : 'Amount off (₹)'} suffix={pct ? '%' : undefined} min={0.01}
            max={pct ? 100 : undefined} decimalScale={2} withAsterisk {...form.getInputProps('value')} />
          <NumberInput label="Max redemptions" placeholder="Unlimited" min={1} allowDecimal={false}
            {...form.getInputProps('maxRedemptions')} />
          <DateInput label="Valid from" valueFormat="DD MMM YYYY" withAsterisk {...form.getInputProps('validFrom')} />
          <DateInput label="Valid until" placeholder="No end date" valueFormat="DD MMM YYYY" clearable
            minDate={form.values.validFrom ?? undefined} {...form.getInputProps('validUntil')} />
        </SimpleGrid>
        {create.error && <Alert color="red" style={{ whiteSpace: 'pre-line' }}>{errorMessage(create.error)}</Alert>}
        <Group justify="flex-end">
          <Button variant="default" onClick={onDone}>Cancel</Button>
          <Button type="submit" loading={create.isPending}>Create discount</Button>
        </Group>
      </Stack>
    </form>
  );
}
