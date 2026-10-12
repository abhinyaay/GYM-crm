import {
  ActionIcon, Alert, Badge, Button, Card, Group, Modal, NumberInput, Paper, SimpleGrid, Stack, Table, Text, Textarea,
  Title, Tooltip,
} from '@mantine/core';
import { IconCreditCard, IconDeviceMobile, IconPlus, IconStar, IconStarFilled, IconTrash } from '@tabler/icons-react';
import { useState } from 'react';
import type { AutopayAttempt, CreditAccount, SavedPaymentMethod } from '@/api/payments';
import type { Page } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { hasAnyRole, MANAGERS } from '@/auth/roles';
import { QueryState } from '@/components/QueryState';
import { StatusBadge } from '@/components/StatusBadge';
import { dateTime, label, money } from '@/lib/format';
import { errorMessage } from '@/lib/notify';
import { useApiMutation, useApiQuery } from '@/lib/useApi';

const INVALIDATE = ['/api/v1/members', '/api/v1/subscriptions', '/api/v1/autopay'];

export function SavedMethodsSection({ memberId }: { memberId: number }) {
  const methods = useApiQuery<SavedPaymentMethod[]>(`/api/v1/members/${memberId}/payment-methods`);
  const [removing, setRemoving] = useState<SavedPaymentMethod | null>(null);

  const makeDefault = useApiMutation<number, SavedPaymentMethod[]>({
    request: (id) => ({ path: `/api/v1/members/${memberId}/payment-methods/${id}/default`, method: 'PUT' }),
    invalidate: INVALIDATE,
    successMessage: 'Default payment method updated',
  });
  const remove = useApiMutation<number, void>({
    request: (id) => ({ path: `/api/v1/members/${memberId}/payment-methods/${id}`, method: 'DELETE' }),
    invalidate: INVALIDATE,
    successMessage: 'Payment method removed',
    silentError: true,
    onSuccess: () => setRemoving(null),
  });
  const list = methods.data ?? [];

  return (
    <div>
      <Title order={4} mb={4}>Saved cards &amp; UPI</Title>
      <Text size="sm" c="dimmed" mb="sm">
        Stored as payment-provider tokens: we never see or keep your card number or UPI PIN.
      </Text>
      <QueryState isLoading={methods.isLoading} error={methods.error} empty={list.length === 0}
        emptyText="No saved methods. Tick “Save” when paying online to add one.">
        <SimpleGrid cols={{ base: 1, sm: 2, md: 3 }}>
          {list.map((m) => (
            <Card key={m.id} withBorder radius="md" p="md">
              <Group justify="space-between" wrap="nowrap" align="flex-start">
                <Group gap="sm" wrap="nowrap">
                  {m.type === 'CARD' ? <IconCreditCard size={28} stroke={1.5} /> : <IconDeviceMobile size={28} stroke={1.5} />}
                  <div>
                    <Text fw={600} size="sm">{m.label}</Text>
                    <Text size="xs" c="dimmed">
                      {m.type === 'CARD' && m.expMonth ? `Expires ${String(m.expMonth).padStart(2, '0')}/${m.expYear}` : 'UPI'}
                      {m.provider === 'SIMULATED' ? ' · test' : ''}
                    </Text>
                  </div>
                </Group>
                <Group gap={2} wrap="nowrap">
                  <Tooltip label={m.isDefault ? 'Default method' : 'Make default'}>
                    <ActionIcon variant="subtle" color="yellow" aria-label="Make default"
                      onClick={() => !m.isDefault && makeDefault.mutate(m.id)}>
                      {m.isDefault ? <IconStarFilled size={16} /> : <IconStar size={16} />}
                    </ActionIcon>
                  </Tooltip>
                  <Tooltip label="Remove">
                    <ActionIcon variant="subtle" color="red" aria-label={`Remove ${m.label}`} onClick={() => setRemoving(m)}>
                      <IconTrash size={16} />
                    </ActionIcon>
                  </Tooltip>
                </Group>
              </Group>
              <Group gap={6} mt="sm">
                {m.mandateStatus === 'NONE'
                  ? <Badge size="xs" variant="outline" color="gray">One-click only</Badge>
                  : <Badge size="xs" variant="light" color={m.mandateStatus === 'ACTIVE' ? 'teal' : 'orange'}>
                      Autopay mandate {label(m.mandateStatus).toLowerCase()}
                    </Badge>}
                {m.mandateMaxAmount != null && <Text size="xs" c="dimmed">up to {money(m.mandateMaxAmount)}/debit</Text>}
              </Group>
            </Card>
          ))}
        </SimpleGrid>
      </QueryState>
      <Modal opened={removing != null} onClose={() => { setRemoving(null); remove.reset(); }} title="Remove payment method" centered>
        <Stack>
          <Text size="sm">
            Remove <b>{removing?.label}</b>? Any autopay using it will be switched off and scheduled debits cancelled.
          </Text>
          {remove.error && <Alert color="red">{errorMessage(remove.error)}</Alert>}
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setRemoving(null)}>Keep</Button>
            <Button color="red" loading={remove.isPending} onClick={() => removing && remove.mutate(removing.id)}>Remove</Button>
          </Group>
        </Stack>
      </Modal>
    </div>
  );
}

export function StoreCreditSection({ memberId }: { memberId: number }) {
  const { user } = useAuth();
  const canIssue = hasAnyRole(user, MANAGERS);
  const credit = useApiQuery<CreditAccount>(`/api/v1/members/${memberId}/credit`);
  const [open, setOpen] = useState(false);
  const [amount, setAmount] = useState<number | string>(500);
  const [reason, setReason] = useState('');
  const adjust = useApiMutation<void, CreditAccount>({
    request: () => ({ path: `/api/v1/members/${memberId}/credit`, body: { amount: Number(amount), reason: reason.trim() } }),
    invalidate: ['/api/v1/members'],
    successMessage: (a) => `Store credit balance is now ${money(a.balance)}`,
    silentError: true,
    onSuccess: () => { setOpen(false); setReason(''); },
  });
  const entries = credit.data?.entries ?? [];

  return (
    <div>
      <Group justify="space-between" mb="sm">
        <div>
          <Title order={4}>Store credit</Title>
          <Text size="sm" c="dimmed">Spend it on any invoice. Refunds can also be paid out as credit.</Text>
        </div>
        {canIssue && <Button size="xs" variant="light" leftSection={<IconPlus size={14} />} onClick={() => setOpen(true)}>Adjust credit</Button>}
      </Group>
      <QueryState isLoading={credit.isLoading} error={credit.error}>
        <Paper withBorder radius="md" p="md">
          <Text size="xs" c="dimmed" tt="uppercase" fw={600}>Balance</Text>
          <Text fz={26} fw={700}>{money(credit.data?.balance ?? 0)}</Text>
          {entries.length > 0 && (
            <Table.ScrollContainer minWidth={520} mt="sm">
              <Table verticalSpacing={6} fz="sm">
                <Table.Thead>
                  <Table.Tr><Table.Th>When</Table.Th><Table.Th>Type</Table.Th><Table.Th>Reason</Table.Th>
                    <Table.Th ta="right">Amount</Table.Th><Table.Th ta="right">Balance</Table.Th></Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {entries.map((e) => (
                    <Table.Tr key={e.id}>
                      <Table.Td>{dateTime(e.createdAt)}</Table.Td>
                      <Table.Td>{label(e.entryType)}</Table.Td>
                      <Table.Td><Text size="sm" lineClamp={1}>{e.reason}</Text></Table.Td>
                      <Table.Td ta="right"><Text size="sm" c={e.amount < 0 ? 'red' : 'teal'}>{e.amount > 0 ? '+' : ''}{money(e.amount)}</Text></Table.Td>
                      <Table.Td ta="right">{money(e.balanceAfter)}</Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </Table.ScrollContainer>
          )}
        </Paper>
      </QueryState>
      <Modal opened={open} onClose={() => setOpen(false)} title="Adjust store credit" centered>
        <Stack>
          <NumberInput label="Amount (₹)" description="Positive to add credit, negative to correct a mistake"
            decimalScale={2} value={amount} onChange={setAmount} />
          <Textarea label="Reason" withAsterisk maxLength={300} value={reason} onChange={(e) => setReason(e.currentTarget.value)} />
          {adjust.error && <Alert color="red">{errorMessage(adjust.error)}</Alert>}
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setOpen(false)}>Cancel</Button>
            <Button disabled={!reason.trim() || !Number(amount)} loading={adjust.isPending} onClick={() => adjust.mutate()}>Save</Button>
          </Group>
        </Stack>
      </Modal>
    </div>
  );
}

export function AutopayHistorySection({ memberId, canRetry = false }: { memberId: number; canRetry?: boolean }) {
  const attempts = useApiQuery<Page<AutopayAttempt>>('/api/v1/autopay/attempts', { memberId, size: 10 });
  const rows = attempts.data?.content ?? [];
  const retry = useApiMutation<number, AutopayAttempt>({
    request: (id) => ({ path: `/api/v1/autopay/attempts/${id}/retry` }),
    invalidate: ['/api/v1/autopay', '/api/v1/subscriptions', '/api/v1/members', '/api/v1/payments'],
    successMessage: (a) => (a.status === 'SUCCEEDED' ? 'Debit succeeded' : `Debit ${label(a.status).toLowerCase()}`),
  });
  const latestFailed = rows.find((r) => r.status === 'FAILED');
  return (
    <div>
      <Title order={4} mb="sm">Autopay history</Title>
      <QueryState isLoading={attempts.isLoading} error={attempts.error} empty={rows.length === 0}
        emptyText="No automatic debits yet.">
        <Paper withBorder radius="md">
          <Table.ScrollContainer minWidth={640}>
            <Table verticalSpacing="xs" fz="sm">
              <Table.Thead>
                <Table.Tr><Table.Th>Scheduled</Table.Th><Table.Th>Invoice</Table.Th><Table.Th>Method</Table.Th>
                  <Table.Th ta="right">Amount</Table.Th><Table.Th>Status</Table.Th><Table.Th /></Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {rows.map((a) => (
                  <Table.Tr key={a.id}>
                    <Table.Td>{dateTime(a.scheduledAt)}<Text size="xs" c="dimmed">Attempt {a.attemptNo}</Text></Table.Td>
                    <Table.Td>{a.invoiceNumber}</Table.Td>
                    <Table.Td>{a.paymentMethodLabel}</Table.Td>
                    <Table.Td ta="right">{money(a.amount)}</Table.Td>
                    <Table.Td>
                      <StatusBadge value={a.status} />
                      {a.failureReason && <Text size="xs" c="dimmed">{a.failureReason}</Text>}
                    </Table.Td>
                    <Table.Td>
                      {canRetry && latestFailed?.id === a.id && (
                        <Button size="compact-xs" variant="light" loading={retry.isPending} onClick={() => retry.mutate(a.id)}>
                          Retry now
                        </Button>
                      )}
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
        </Paper>
      </QueryState>
    </div>
  );
}

/** Saved methods + store credit + autopay history for one member. */
export function PaymentMethodsPanel({ memberId, staff = false }: { memberId: number; staff?: boolean }) {
  const { user } = useAuth();
  return (
    <Stack gap="xl">
      <SavedMethodsSection memberId={memberId} />
      <StoreCreditSection memberId={memberId} />
      <AutopayHistorySection memberId={memberId} canRetry={staff && hasAnyRole(user, MANAGERS)} />
    </Stack>
  );
}

