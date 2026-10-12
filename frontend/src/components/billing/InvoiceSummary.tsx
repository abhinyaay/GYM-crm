import { Divider, Group, Stack, Text } from '@mantine/core';
import type { Invoice } from '@/api/types';
import { date, money } from '@/lib/format';
import { StatusBadge } from '../StatusBadge';

export function InvoiceSummary({ invoice }: { invoice: Invoice }) {
  return (
    <Stack gap={6}>
      <Group justify="space-between">
        <Text fw={600}>{invoice.invoiceNumber}</Text>
        <StatusBadge value={invoice.status} />
      </Group>
      {invoice.lines.map((l, i) => (
        <Group key={i} justify="space-between">
          <Text size="sm">{l.description}</Text>
          <Text size="sm" c={l.amount < 0 ? 'teal' : undefined}>{money(l.amount)}</Text>
        </Group>
      ))}
      {invoice.discountAmount > 0 && (
        <Group justify="space-between">
          <Text size="sm">Discount {invoice.discountCode ? `(${invoice.discountCode})` : ''}</Text>
          <Text size="sm" c="teal">−{money(invoice.discountAmount)}</Text>
        </Group>
      )}
      <Group justify="space-between">
        <Text size="sm" c="dimmed">GST {(invoice.taxRate * 100).toFixed(0)}%</Text>
        <Text size="sm" c="dimmed">{money(invoice.taxAmount)}</Text>
      </Group>
      <Divider />
      <Group justify="space-between">
        <Text fw={700}>Total</Text>
        <Text fw={700}>{money(invoice.totalAmount)}</Text>
      </Group>
      <Text size="xs" c={invoice.overdue ? 'red' : 'dimmed'}>Due {date(invoice.dueDate)}{invoice.overdue ? ' · overdue' : ''}</Text>
    </Stack>
  );
}
