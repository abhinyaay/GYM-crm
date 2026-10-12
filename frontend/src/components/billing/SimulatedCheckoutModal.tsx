import { Alert, Badge, Button, Checkbox, Group, Modal, Select, Stack, Text, TextInput } from '@mantine/core';
import { IconFlask, IconLock } from '@tabler/icons-react';
import { useEffect, useState } from 'react';
import type { CheckoutCompletion, CheckoutSession } from '@/api/payments';
import { METHOD_LABELS } from '@/api/payments';
import { money } from '@/lib/format';
import { errorMessage } from '@/lib/notify';
import { useApiMutation } from '@/lib/useApi';

/** Test instruments, in the spirit of payment providers' published test cards / VPAs. */
const TEST_CARDS = [
  { value: 'Visa|4242|ok', label: 'Visa •••• 4242 — succeeds' },
  { value: 'Mastercard|5100|ok', label: 'Mastercard •••• 5100 — succeeds' },
  { value: 'RuPay|6070|ok', label: 'RuPay •••• 6070 — succeeds' },
  { value: 'Visa|0002|card_declined', label: 'Visa •••• 0002 — declined' },
  { value: 'Visa|9995|insufficient_funds', label: 'Visa •••• 9995 — insufficient funds' },
  { value: 'Visa|3220|authentication_failed', label: 'Visa •••• 3220 — 3-D Secure fails' },
];
const BANKS = ['HDFC', 'ICICI', 'SBI', 'AXIS', 'KOTAK'];
const WALLETS = ['PAYTM', 'PHONEPE', 'AMAZONPAY', 'MOBIKWIK'];

/**
 * Stand-in for the provider's hosted checkout page when the simulated gateway is active. It returns
 * a payment id, order id and HMAC signature exactly like Razorpay Checkout does, so the rest of the
 * flow (server-side signature verification, recording, webhooks) is the real one.
 */
export function SimulatedCheckoutModal({ session, opened, onComplete, onCancel }: {
  session: CheckoutSession | null;
  opened: boolean;
  onComplete: (c: CheckoutCompletion) => void;
  onCancel: () => void;
}) {
  const [card, setCard] = useState<string>(TEST_CARDS[0].value);
  const [vpa, setVpa] = useState('success@razorpay');
  const [bank, setBank] = useState<string>(BANKS[0]);
  const [wallet, setWallet] = useState<string>(WALLETS[0]);
  const [bankOutcome, setBankOutcome] = useState<'ok' | 'fail'>('ok');
  const [failFuture, setFailFuture] = useState(false);

  useEffect(() => {
    if (opened) setFailFuture(false);
  }, [opened]);

  const simulate = useApiMutation<Record<string, unknown>, CheckoutCompletion>({
    request: (body) => ({ path: `/api/v1/payments/checkout/${session?.intentId}/simulate`, body }),
    silentError: true,
    onSuccess: onComplete,
  });

  if (!session) return null;
  const method = session.method;

  const submit = () => {
    if (method === 'CARD' || method === 'EMI') {
      const [brand, last4, outcome] = card.split('|');
      simulate.mutate({ success: outcome === 'ok', failureReason: outcome === 'ok' ? undefined : outcome, brand, last4,
        failFutureDebits: failFuture });
    } else if (method === 'UPI') {
      const fails = vpa.trim().toLowerCase().startsWith('failure@');
      simulate.mutate({ success: !fails, failureReason: fails ? 'upi_request_declined' : undefined, vpa: vpa.trim(),
        failFutureDebits: failFuture });
    } else {
      simulate.mutate({ success: bankOutcome === 'ok', failureReason: bankOutcome === 'ok' ? undefined : 'authentication_failed',
        bank: method === 'WALLET' ? wallet : bank });
    }
  };

  return (
    <Modal opened={opened} onClose={onCancel} centered size="sm" withCloseButton={false} closeOnClickOutside={false}>
      <Stack>
        <Group justify="space-between">
          <Group gap={6}><IconLock size={16} /><Text fw={600}>Secure checkout</Text></Group>
          <Badge color="orange" variant="light" leftSection={<IconFlask size={12} />}>Test mode</Badge>
        </Group>
        <div>
          <Text size="xs" c="dimmed">{session.description}</Text>
          <Text fz={28} fw={700}>{money(session.amount)}</Text>
          <Text size="sm" c="dimmed">
            {METHOD_LABELS[method]}{session.emiTenureMonths ? ` · ${session.emiTenureMonths} monthly instalments` : ''}
          </Text>
        </div>
        <Alert color="orange" variant="light" p="xs">
          <Text size="xs">Simulated payment gateway: no real money moves. Pick a test instrument to see success or failure.</Text>
        </Alert>

        {(method === 'CARD' || method === 'EMI') && (
          <Select label="Test card" data={TEST_CARDS} value={card} onChange={(v) => v && setCard(v)} allowDeselect={false} />
        )}
        {method === 'UPI' && (
          <TextInput label="UPI ID" description="success@razorpay pays; failure@razorpay is declined"
            value={vpa} onChange={(e) => setVpa(e.currentTarget.value)} />
        )}
        {method === 'NET_BANKING' && (
          <Select label="Bank" data={BANKS} value={bank} onChange={(v) => v && setBank(v)} allowDeselect={false} />
        )}
        {method === 'WALLET' && (
          <Select label="Wallet" data={WALLETS} value={wallet} onChange={(v) => v && setWallet(v)} allowDeselect={false} />
        )}
        {(method === 'NET_BANKING' || method === 'WALLET') && (
          <Select label="Bank page result" value={bankOutcome} onChange={(v) => setBankOutcome((v as 'ok' | 'fail') ?? 'ok')}
            data={[{ value: 'ok', label: 'Approve payment' }, { value: 'fail', label: 'Fail authentication' }]} allowDeselect={false} />
        )}
        {session.saveMethod && (
          <Checkbox size="xs" checked={failFuture} onChange={(e) => setFailFuture(e.currentTarget.checked)}
            label="Test: decline future autopay debits on this method (to see retries)" />
        )}
        {session.setupAutopay && (
          <Text size="xs" c="dimmed">
            You are also authorising automatic renewal debits of up to {money(session.mandateMaxAmount ?? 0)} each.
            You'll be notified before every debit and can cancel any time.
          </Text>
        )}
        {simulate.error && <Alert color="red">{errorMessage(simulate.error)}</Alert>}
        <Group grow>
          <Button variant="default" onClick={onCancel}>Cancel</Button>
          <Button onClick={submit} loading={simulate.isPending}>Pay {money(session.amount)}</Button>
        </Group>
      </Stack>
    </Modal>
  );
}
