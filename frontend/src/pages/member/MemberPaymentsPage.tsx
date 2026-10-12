import {
  Alert, Badge, Button, Divider, Group, Modal, Paper, SegmentedControl, SimpleGrid, Stack, Table, Tabs, Text,
} from '@mantine/core';
import { IconAlertTriangle, IconCreditCard, IconFileInvoice, IconReceipt } from '@tabler/icons-react';
import { useState } from 'react';
import type { Invoice, InvoiceStatus, Page, Payment } from '@/api/types';
import { InvoiceSummary } from '@/components/billing/InvoiceSummary';
import { CreditNoteButtons, TaxDocumentButton } from '@/components/billing/TaxDocumentButton';
import { PayInvoiceModal } from '@/components/billing/PayInvoiceModal';
import { METHOD_LABELS } from '@/api/payments';
import { PageHeader } from '@/components/PageHeader';
import { Pager } from '@/components/Pager';
import { QueryState } from '@/components/QueryState';
import { StatCard } from '@/components/StatCard';
import { StatusBadge } from '@/components/StatusBadge';
import { date, dateTime, label, money } from '@/lib/format';
import { useApiQuery } from '@/lib/useApi';

type InvoiceFilter = 'ALL' | InvoiceStatus;

function InvoiceDetails({ invoice, onPay }: { invoice: Invoice; onPay: () => void }) {
  return (
    <Stack>
      <InvoiceSummary invoice={invoice} />
      <Divider />
      <SimpleGrid cols={2} spacing="xs">
        <Text size="sm" c="dimmed">Issued</Text>
        <Text size="sm" ta="right">{dateTime(invoice.issuedAt)}</Text>
        <Text size="sm" c="dimmed">Amount paid</Text>
        <Text size="sm" ta="right">{money(invoice.amountPaid)}</Text>
        {invoice.paidAt && (
          <>
            <Text size="sm" c="dimmed">Paid on</Text>
            <Text size="sm" ta="right">{dateTime(invoice.paidAt)}</Text>
          </>
        )}
        {invoice.amountRefunded > 0 && (
          <>
            <Text size="sm" c="dimmed">Refunded</Text>
            <Text size="sm" ta="right" c="grape">{money(invoice.amountRefunded)}</Text>
          </>
        )}
        {invoice.subscriptionId && (
          <>
            <Text size="sm" c="dimmed">Subscription</Text>
            <Text size="sm" ta="right">#{invoice.subscriptionId}</Text>
          </>
        )}
      </SimpleGrid>
      <TaxDocumentButton doc={{ kind: 'invoice', invoiceId: invoice.id, number: invoice.invoiceNumber }}
        label="Download GST tax invoice (PDF)" />
      {invoice.status === 'ISSUED' && (
        <Button leftSection={<IconCreditCard size={16} />} onClick={onPay}>Pay {money(invoice.totalAmount)}</Button>
      )}
    </Stack>
  );
}

export default function MemberPaymentsPage() {
  const [tab, setTab] = useState<string | null>('invoices');
  const [filter, setFilter] = useState<InvoiceFilter>('ALL');
  const [invoicePage, setInvoicePage] = useState(0);
  const [paymentPage, setPaymentPage] = useState(0);
  const [viewing, setViewing] = useState<Invoice | null>(null);
  const [payInvoiceId, setPayInvoiceId] = useState<number | null>(null);

  const invoices = useApiQuery<Page<Invoice>>('/api/v1/payments/invoices', {
    status: filter === 'ALL' ? undefined : filter, page: invoicePage, size: 20,
  });
  const outstanding = useApiQuery<Page<Invoice>>('/api/v1/payments/invoices', { status: 'ISSUED', size: 100 });
  const payments = useApiQuery<Page<Payment>>('/api/v1/payments', { page: paymentPage, size: 20 });

  const due = outstanding.data?.content ?? [];
  const dueTotal = due.reduce((sum, i) => sum + (i.totalAmount - i.amountPaid), 0);
  const overdue = due.filter((i) => i.overdue);

  const pay = (id: number) => {
    setViewing(null);
    setPayInvoiceId(id);
  };

  return (
    <>
      <PageHeader title="Payments" subtitle="Your invoices and payment history" />
      <Stack gap="lg">
        <SimpleGrid cols={{ base: 1, sm: 3 }}>
          <StatCard label="Amount due" value={money(dueTotal)} hint={`${due.length} unpaid invoice${due.length === 1 ? '' : 's'}`}
            icon={<IconFileInvoice size={20} />} color={due.length ? 'yellow' : 'teal'} />
          <StatCard label="Overdue" value={overdue.length} hint={overdue.length ? 'Please pay to keep access' : 'All good'}
            icon={<IconAlertTriangle size={20} />} color={overdue.length ? 'red' : 'teal'} />
          <StatCard label="Payments made" value={payments.data?.totalElements ?? '—'}
            hint="All time"
            icon={<IconReceipt size={20} />} color="indigo" />
        </SimpleGrid>

        {due.length > 0 && (
          <Alert color={overdue.length ? 'red' : 'yellow'} icon={<IconCreditCard size={18} />} title="Payment due">
            <Stack gap="xs">
              {due.map((i) => (
                <Group key={i.id} justify="space-between" wrap="wrap" gap="xs">
                  <Text size="sm">
                    {i.invoiceNumber} · {money(i.totalAmount)} · due {date(i.dueDate)}
                    {i.overdue && <Badge ml="xs" size="xs" color="red">overdue</Badge>}
                  </Text>
                  <Button size="xs" onClick={() => setPayInvoiceId(i.id)}>Pay now</Button>
                </Group>
              ))}
            </Stack>
          </Alert>
        )}

        <Tabs value={tab} onChange={setTab}>
          <Tabs.List>
            <Tabs.Tab value="invoices" leftSection={<IconFileInvoice size={16} />}>Invoices</Tabs.Tab>
            <Tabs.Tab value="payments" leftSection={<IconReceipt size={16} />}>Payment history</Tabs.Tab>
          </Tabs.List>

          <Tabs.Panel value="invoices" pt="md">
            <Group mb="sm">
              <SegmentedControl
                size="xs"
                value={filter}
                onChange={(v) => {
                  setFilter(v as InvoiceFilter);
                  setInvoicePage(0);
                }}
                data={[
                  { value: 'ALL', label: 'All' },
                  { value: 'ISSUED', label: 'Unpaid' },
                  { value: 'PAID', label: 'Paid' },
                  { value: 'REFUNDED', label: 'Refunded' },
                  { value: 'VOID', label: 'Void' },
                ]}
                aria-label="Filter invoices by status"
              />
            </Group>
            <Paper withBorder radius="md">
              <QueryState isLoading={invoices.isLoading} error={invoices.error}
                empty={invoices.data?.content.length === 0} emptyText="No invoices found.">
                <Table.ScrollContainer minWidth={720}>
                  <Table verticalSpacing="sm" highlightOnHover>
                    <Table.Thead>
                      <Table.Tr>
                        <Table.Th>Invoice</Table.Th>
                        <Table.Th>Issued</Table.Th>
                        <Table.Th>Due</Table.Th>
                        <Table.Th ta="right">Total</Table.Th>
                        <Table.Th ta="right">Paid</Table.Th>
                        <Table.Th>Status</Table.Th>
                        <Table.Th ta="right">Actions</Table.Th>
                      </Table.Tr>
                    </Table.Thead>
                    <Table.Tbody>
                      {invoices.data?.content.map((i) => (
                        <Table.Tr key={i.id}>
                          <Table.Td>
                            <Text size="sm" fw={500}>{i.invoiceNumber}</Text>
                            <Text size="xs" c="dimmed" lineClamp={1}>{i.lines.map((l) => l.description).join(', ')}</Text>
                          </Table.Td>
                          <Table.Td>{date(i.issuedAt)}</Table.Td>
                          <Table.Td>
                            <Text size="sm" c={i.overdue ? 'red' : undefined}>{date(i.dueDate)}</Text>
                          </Table.Td>
                          <Table.Td ta="right">{money(i.totalAmount)}</Table.Td>
                          <Table.Td ta="right">{money(i.amountPaid)}</Table.Td>
                          <Table.Td>
                            <Group gap={4}>
                              <StatusBadge value={i.status} />
                              {i.overdue && <Badge size="xs" color="red" variant="filled">Overdue</Badge>}
                            </Group>
                          </Table.Td>
                          <Table.Td>
                            <Group gap="xs" justify="flex-end" wrap="nowrap">
                              <Button size="xs" variant="subtle" onClick={() => setViewing(i)}
                                aria-label={`View invoice ${i.invoiceNumber}`}>
                                View
                              </Button>
                              <TaxDocumentButton compact doc={{ kind: 'invoice', invoiceId: i.id, number: i.invoiceNumber }} />
                              {i.status === 'ISSUED' && (
                                <Button size="xs" onClick={() => setPayInvoiceId(i.id)} aria-label={`Pay invoice ${i.invoiceNumber}`}>
                                  Pay
                                </Button>
                              )}
                            </Group>
                          </Table.Td>
                        </Table.Tr>
                      ))}
                    </Table.Tbody>
                  </Table>
                </Table.ScrollContainer>
              </QueryState>
            </Paper>
            <Pager page={invoices.data} onChange={setInvoicePage} />
          </Tabs.Panel>

          <Tabs.Panel value="payments" pt="md">
            <Paper withBorder radius="md">
              <QueryState isLoading={payments.isLoading} error={payments.error}
                empty={payments.data?.content.length === 0} emptyText="No payments yet.">
                <Table.ScrollContainer minWidth={720}>
                  <Table verticalSpacing="sm">
                    <Table.Thead>
                      <Table.Tr>
                        <Table.Th>Date</Table.Th>
                        <Table.Th>Invoice</Table.Th>
                        <Table.Th>Method</Table.Th>
                        <Table.Th ta="right">Amount</Table.Th>
                        <Table.Th ta="right">Refunded</Table.Th>
                        <Table.Th>Status</Table.Th>
                        <Table.Th>Reference</Table.Th>
                      </Table.Tr>
                    </Table.Thead>
                    <Table.Tbody>
                      {payments.data?.content.map((p) => (
                        <Table.Tr key={p.id}>
                          <Table.Td>{dateTime(p.processedAt)}</Table.Td>
                          <Table.Td>{p.invoiceNumber}</Table.Td>
                          <Table.Td>
                            {METHOD_LABELS[p.method] ?? label(p.method)}
                            {p.autopay && <Text span size="xs" c="teal"> · autopay</Text>}
                            {p.instrument && <Text size="xs" c="dimmed">{p.instrument}</Text>}
                          </Table.Td>
                          <Table.Td ta="right">{money(p.amount)}</Table.Td>
                          <Table.Td ta="right">
                            {p.refundedAmount > 0 ? money(p.refundedAmount) : '—'}
                            {p.refundedAmount > 0 && <CreditNoteButtons paymentId={p.id} />}
                          </Table.Td>
                          <Table.Td>
                            <StatusBadge value={p.status} />
                            {p.failureReason && <Text size="xs" c="red">{p.failureReason}</Text>}
                          </Table.Td>
                          <Table.Td>
                            <Text size="xs" c="dimmed" ff="monospace" truncate="end" maw={200}>{p.gatewayReference ?? '—'}</Text>
                          </Table.Td>
                        </Table.Tr>
                      ))}
                    </Table.Tbody>
                  </Table>
                </Table.ScrollContainer>
              </QueryState>
            </Paper>
            <Pager page={payments.data} onChange={setPaymentPage} />
          </Tabs.Panel>
        </Tabs>
      </Stack>

      <Modal opened={viewing != null} onClose={() => setViewing(null)} title="Invoice details" centered>
        {viewing && <InvoiceDetails invoice={viewing} onPay={() => pay(viewing.id)} />}
      </Modal>
      <PayInvoiceModal invoiceId={payInvoiceId} opened={payInvoiceId != null} onClose={() => setPayInvoiceId(null)} />
    </>
  );
}
