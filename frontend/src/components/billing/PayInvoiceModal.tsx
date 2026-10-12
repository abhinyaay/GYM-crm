import {
  Alert, Badge, Button, Checkbox, Chip, Group, Modal, Radio, SegmentedControl, Stack, Text, TextInput,
} from '@mantine/core';
import { IconBuildingBank, IconCash, IconCreditCard, IconStar, IconWallet } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { newIdempotencyKey } from '@/api/client';
import type {
  CheckoutCompletion, CheckoutResult, CheckoutSession, CreditAccount, PaymentConfig, SavedPaymentMethod,
} from '@/api/payments';
import { METHOD_LABELS } from '@/api/payments';
import type { Invoice, Payment, PaymentMethod } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { isMemberOnly } from '@/auth/roles';
import { money } from '@/lib/format';
import { errorMessage, notifySuccess } from '@/lib/notify';
import { useApiMutation, useApiQuery } from '@/lib/useApi';
import { InvoiceSummary } from './InvoiceSummary';
import { CheckoutDismissed, openRazorpayCheckout } from './razorpay';
import { SimulatedCheckoutModal } from './SimulatedCheckoutModal';

const INVALIDATE = ['/api/v1/payments', '/api/v1/subscriptions', '/api/v1/members', '/api/v1/dashboard',
  '/api/v1/notifications', '/api/v1/autopay'];

type Mode = 'saved' | 'online' | 'counter' | 'credit';

/**
 * Collects payment for an invoice:
 * - saved card / UPI in one click,
 * - online via hosted checkout (card, UPI, net banking, wallet, card EMI), optionally saving the
 *   method and setting up autopay for the membership,
 * - store credit,
 * - cash / bank transfer recorded at the desk (staff only).
 * Every request carries an idempotency key generated per modal opening, so a double-click or a
 * network retry never charges twice.
 */
export function PayInvoiceModal({ invoiceId, opened, onClose, onPaid }: {
  invoiceId: number | null;
  opened: boolean;
  onClose: () => void;
  onPaid?: (payment: Payment) => void;
}) {
  const { user } = useAuth();
  const member = isMemberOnly(user);
  const client = useQueryClient();
  const { data: invoice } = useApiQuery<Invoice>(invoiceId ? `/api/v1/payments/invoices/${invoiceId}` : null);
  const memberId = invoice?.memberId ?? null;
  const config = useApiQuery<PaymentConfig>(opened ? '/api/v1/payments/config' : null);
  const saved = useApiQuery<SavedPaymentMethod[]>(opened && memberId ? `/api/v1/members/${memberId}/payment-methods` : null);
  const credit = useApiQuery<CreditAccount>(opened && memberId ? `/api/v1/members/${memberId}/credit` : null);

  const baseKey = useMemo(() => newIdempotencyKey('pay'), [opened, invoiceId]); // eslint-disable-line react-hooks/exhaustive-deps
  const usableSaved = (saved.data ?? []).filter((m) => m.provider === 'SIMULATED' && m.status === 'ACTIVE');
  const total = invoice?.totalAmount ?? 0;
  const balance = credit.data?.balance ?? 0;

  const [mode, setMode] = useState<Mode>('online');
  const [savedId, setSavedId] = useState<string | null>(null);
  const [method, setMethod] = useState<PaymentMethod>('UPI');
  const [emiTenure, setEmiTenure] = useState<string>('6');
  const [saveMethod, setSaveMethod] = useState(false);
  const [autopay, setAutopay] = useState(false);
  const [counterMethod, setCounterMethod] = useState<PaymentMethod>('CASH');
  const [reference, setReference] = useState('');
  const [session, setSession] = useState<CheckoutSession | null>(null);
  const [simOpen, setSimOpen] = useState(false);
  const [outcome, setOutcome] = useState<{ color: string; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  // sensible default tab once we know what the member has
  useEffect(() => {
    if (!opened) return;
    setOutcome(null);
    setSession(null);
    if (usableSaved.length > 0) {
      setMode('saved');
      setSavedId(String((usableSaved.find((m) => m.isDefault) ?? usableSaved[0]).id));
    } else {
      setMode('online');
    }
  }, [opened, saved.data]); // eslint-disable-line react-hooks/exhaustive-deps

  const tokenisable = method === 'CARD' || method === 'UPI';
  const autopayAvailable = tokenisable && invoice?.subscriptionId != null
    && total <= (config.data?.mandateMaxAmount ?? Number.MAX_SAFE_INTEGER);
  const emiAvailable = total >= (config.data?.emiMinAmount ?? Number.MAX_SAFE_INTEGER);

  const finish = (payment: Payment, message: string) => {
    INVALIDATE.forEach((prefix) => client.invalidateQueries({
      predicate: (q) => typeof q.queryKey[0] === 'string' && (q.queryKey[0] as string).startsWith(prefix),
    }));
    notifySuccess(message);
    onPaid?.(payment);
    onClose();
  };

  // ---------------------------------------------------------------- direct payments (saved / credit / counter)
  const direct = useApiMutation<{ method: PaymentMethod; paymentMethodId?: number; externalReference?: string }, Payment>({
    request: (v) => ({
      path: '/api/v1/payments',
      headers: { 'Idempotency-Key': `${baseKey}-${v.method}-${v.paymentMethodId ?? 'x'}` },
      body: { invoiceId, amount: total, ...v },
    }),
    silentError: true,
    onSuccess: (p) => finish(p, `Payment of ${money(p.amount)} received${p.instrument ? ` (${p.instrument})` : ''}`),
  });

  // ---------------------------------------------------------------- hosted checkout
  const confirm = useApiMutation<{ intentId: number; completion: CheckoutCompletion }, CheckoutResult>({
    request: ({ intentId, completion }) => ({ path: `/api/v1/payments/checkout/${intentId}/confirm`, body: completion }),
    silentError: true,
    onSuccess: (r) => {
      setBusy(false);
      if ((r.outcome === 'SUCCEEDED' || r.outcome === 'ALREADY_PROCESSED') && r.payment) {
        const extras = [r.savedPaymentMethodId && 'method saved', r.autopayEnabled && 'autopay is on'].filter(Boolean);
        finish(r.payment, `Payment of ${money(r.payment.amount)} received${extras.length ? ` — ${extras.join(', ')}` : ''}`);
      } else if (r.outcome === 'FAILED') {
        setOutcome({ color: 'red', text: `Payment failed: ${r.payment?.failureReason ?? r.failureReason ?? 'declined'}. You can try again.` });
      } else if (r.outcome === 'PENDING') {
        setOutcome({ color: 'blue', text: 'The bank is still confirming this payment. It will be applied automatically once confirmed.' });
      } else {
        setOutcome({ color: 'yellow', text: 'This invoice was already paid, so this payment was refunded automatically.' });
      }
    },
  });

  const runCheckout = async (s: CheckoutSession) => {
    setOutcome(null);
    if (s.provider === 'SIMULATED') {
      setSimOpen(true);
      return;
    }
    setBusy(true);
    try {
      const completion = await openRazorpayCheckout(s);
      confirm.mutate({ intentId: s.intentId, completion });
    } catch (e) {
      setBusy(false);
      if (!(e instanceof CheckoutDismissed)) setOutcome({ color: 'red', text: (e as Error).message });
    }
  };

  const start = useApiMutation<void, CheckoutSession>({
    request: () => {
      const options = { method, emiTenureMonths: method === 'EMI' ? Number(emiTenure) : undefined,
        saveMethod: tokenisable && (saveMethod || autopay), setupAutopay: autopayAvailable && autopay };
      return {
        path: '/api/v1/payments/checkout',
        headers: { 'Idempotency-Key': `${baseKey}-${method}-${options.emiTenureMonths ?? 0}-${options.saveMethod ? 1 : 0}${options.setupAutopay ? 1 : 0}` },
        body: { invoiceId, ...options },
      };
    },
    silentError: true,
    onSuccess: (s) => {
      setSession(s);
      void runCheckout(s);
    },
  });

  const error = direct.error ?? start.error ?? confirm.error;
  const onlineMethods = (config.data?.onlineMethods ?? ['CARD', 'UPI', 'NET_BANKING', 'WALLET', 'EMI'])
    .filter((m) => m !== 'EMI' || emiAvailable);
  const modes = [
    usableSaved.length > 0 && { value: 'saved', label: 'Saved' },
    { value: 'online', label: 'Online' },
    balance > 0 && { value: 'credit', label: 'Store credit' },
    !member && { value: 'counter', label: 'At the desk' },
  ].filter(Boolean) as { value: Mode; label: string }[];

  const pay = () => {
    setOutcome(null);
    if (mode === 'saved') {
      const m = usableSaved.find((x) => String(x.id) === savedId);
      if (m) direct.mutate({ method: m.type, paymentMethodId: m.id });
    } else if (mode === 'credit') {
      direct.mutate({ method: 'STORE_CREDIT' });
    } else if (mode === 'counter') {
      direct.mutate({ method: counterMethod, externalReference: reference.trim() || undefined });
    } else if (session && session.status === 'CREATED' && session.method === method && outcome?.color === 'red') {
      void runCheckout(session);   // retry on the same order after a failed attempt
    } else {
      start.mutate();
    }
  };

  const loading = direct.isPending || start.isPending || confirm.isPending || busy;
  return (
    <>
      <Modal opened={opened && !simOpen} onClose={onClose} title="Collect payment" centered size="lg">
        {!invoice ? (
          <Text c="dimmed">Loading invoice…</Text>
        ) : (
          <Stack>
            <InvoiceSummary invoice={invoice} />
            {invoice.status !== 'ISSUED' ? (
              <Alert color="teal">This invoice is {invoice.status.toLowerCase()}.</Alert>
            ) : (
              <>
                {modes.length > 1 && (
                  <SegmentedControl fullWidth value={mode} onChange={(v) => { setMode(v as Mode); setOutcome(null); }} data={modes} />
                )}

                {mode === 'saved' && (
                  <Radio.Group value={savedId} onChange={setSavedId} label="Pay with a saved method">
                    <Stack gap="xs" mt="xs">
                      {usableSaved.map((m) => (
                        <Radio key={m.id} value={String(m.id)} label={
                          <Group gap={6}>
                            <Text size="sm">{m.label}</Text>
                            {m.isDefault && <Badge size="xs" variant="light" leftSection={<IconStar size={10} />}>Default</Badge>}
                            {m.mandateStatus === 'ACTIVE' && <Badge size="xs" color="teal" variant="light">Autopay</Badge>}
                          </Group>
                        } />
                      ))}
                    </Stack>
                  </Radio.Group>
                )}

                {mode === 'online' && (
                  <Stack gap="sm">
                    <Chip.Group value={method} onChange={(v) => { setMethod(v as PaymentMethod); setSession(null); setOutcome(null); }}>
                      <Group gap="xs">
                        {onlineMethods.map((m) => (
                          <Chip key={m} value={m} variant="outline">
                            {METHOD_LABELS[m]}
                          </Chip>
                        ))}
                      </Group>
                    </Chip.Group>
                    {method === 'EMI' && (
                      <div>
                        <Text size="sm" fw={500} mb={4}>EMI tenure</Text>
                        <SegmentedControl value={emiTenure} onChange={setEmiTenure}
                          data={(config.data?.emiTenures ?? [3, 6, 9, 12]).map((t) => ({
                            value: String(t), label: `${t} mo · ${money(Math.ceil((total / t) * 100) / 100)}`,
                          }))} />
                        <Text size="xs" c="dimmed" mt={4}>Instalment amounts exclude any bank interest.</Text>
                      </div>
                    )}
                    {tokenisable && (
                      <Stack gap={6}>
                        <Checkbox label={`Save this ${method === 'CARD' ? 'card' : 'UPI ID'} for faster payments`}
                          checked={saveMethod || autopay} disabled={autopay}
                          onChange={(e) => setSaveMethod(e.currentTarget.checked)} />
                        {autopayAvailable && (
                          <Checkbox
                            label="Renew my membership automatically (autopay)"
                            description={`Debited a few days before each term ends, with a notice 24 h before every debit. Up to ${money(config.data?.mandateMaxAmount ?? 0)} per debit; turn it off any time.`}
                            checked={autopay} onChange={(e) => setAutopay(e.currentTarget.checked)} />
                        )}
                      </Stack>
                    )}
                    <Text size="xs" c="dimmed">
                      {config.data?.provider === 'RAZORPAY'
                        ? 'You will complete the payment on Razorpay\'s secure page. Card and UPI details never reach our servers.'
                        : 'Simulated gateway: a test checkout page opens next.'}
                    </Text>
                  </Stack>
                )}

                {mode === 'credit' && (
                  <Alert color={balance >= total ? 'teal' : 'yellow'} icon={<IconWallet size={18} />}>
                    Store credit balance: <b>{money(balance)}</b>.{' '}
                    {balance >= total ? `${money(total)} will be deducted.` : 'Not enough to cover this invoice.'}
                  </Alert>
                )}

                {mode === 'counter' && (
                  <Stack gap="sm">
                    <SegmentedControl value={counterMethod} onChange={(v) => setCounterMethod(v as PaymentMethod)} data={[
                      { value: 'CASH', label: <Group gap={6} justify="center"><IconCash size={16} />Cash</Group> },
                      { value: 'BANK_TRANSFER', label: <Group gap={6} justify="center"><IconBuildingBank size={16} />Bank transfer</Group> },
                    ]} />
                    <TextInput label={counterMethod === 'CASH' ? 'Receipt number (optional)' : 'Bank UTR / reference'}
                      value={reference} onChange={(e) => setReference(e.currentTarget.value)} />
                  </Stack>
                )}

                {outcome && <Alert color={outcome.color}>{outcome.text}</Alert>}
                {error && <Alert color="red">{errorMessage(error)}</Alert>}
                <Button onClick={pay} loading={loading} size="md" leftSection={<IconCreditCard size={18} />}
                  disabled={(mode === 'saved' && !savedId) || (mode === 'credit' && balance < total)}>
                  {outcome?.color === 'red' && mode === 'online' ? 'Try again' : `Pay ${money(total)}`}
                </Button>
              </>
            )}
          </Stack>
        )}
      </Modal>
      <SimulatedCheckoutModal
        session={session}
        opened={simOpen}
        onCancel={() => setSimOpen(false)}
        onComplete={(completion) => {
          setSimOpen(false);
          if (session) confirm.mutate({ intentId: session.intentId, completion });
        }}
      />
    </>
  );
}
