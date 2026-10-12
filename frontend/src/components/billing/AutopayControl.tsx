import { Alert, Badge, Button, Group, Modal, Select, Stack, Switch, Text } from '@mantine/core';
import { IconRepeat } from '@tabler/icons-react';
import { useState } from 'react';
import type { SavedPaymentMethod } from '@/api/payments';
import type { Subscription } from '@/api/types';
import { date } from '@/lib/format';
import { errorMessage } from '@/lib/notify';
import { useApiMutation, useApiQuery } from '@/lib/useApi';

const AUTOPAY_STATUSES = ['PENDING_PAYMENT', 'SCHEDULED', 'ACTIVE', 'FROZEN'];

/**
 * Autopay switch for one subscription. Enabling needs a saved card / UPI handle with an active
 * mandate (created by paying once with "renew automatically" ticked).
 */
export function AutopayControl({ subscription, compact = false }: { subscription: Subscription; compact?: boolean }) {
  const methods = useApiQuery<SavedPaymentMethod[]>(`/api/v1/members/${subscription.memberId}/payment-methods`);
  const mandated = (methods.data ?? []).filter((m) => m.mandateStatus === 'ACTIVE');
  const current = (methods.data ?? []).find((m) => m.id === subscription.autopayMethodId);
  const [choose, setChoose] = useState(false);
  const [methodId, setMethodId] = useState<string | null>(null);

  const update = useApiMutation<{ enabled: boolean; paymentMethodId?: number }, Subscription>({
    request: (body) => ({ path: `/api/v1/subscriptions/${subscription.id}/autopay`, method: 'PUT', body }),
    invalidate: ['/api/v1/subscriptions', '/api/v1/members', '/api/v1/autopay'],
    successMessage: (s) => (s.autoRenew ? 'Autopay is on' : 'Autopay is off'),
    silentError: true,
    onSuccess: () => setChoose(false),
  });

  if (!AUTOPAY_STATUSES.includes(subscription.status)) return null;
  const on = subscription.autoRenew;

  const toggle = (checked: boolean) => {
    if (!checked) {
      update.mutate({ enabled: false });
    } else if (mandated.length === 1) {
      update.mutate({ enabled: true, paymentMethodId: mandated[0].id });
    } else {
      setMethodId(mandated[0] ? String(mandated[0].id) : null);
      setChoose(true);
    }
  };

  return (
    <Stack gap={4}>
      <Group gap="sm" wrap="nowrap">
        <Switch checked={on} onChange={(e) => toggle(e.currentTarget.checked)} disabled={update.isPending}
          label={<Group gap={6}><IconRepeat size={16} /><Text size="sm" fw={500}>Autopay</Text></Group>}
          aria-label="Autopay" />
        {on && <Badge color="teal" variant="light" size="sm" tt="none">{current?.label ?? 'On'}</Badge>}
      </Group>
      {!compact && (
        <Text size="xs" c="dimmed">
          {on
            ? `Renews automatically before ${date(subscription.endDate)}. You'll get a notice 24 h before each debit.`
            : mandated.length === 0
              ? 'To turn on autopay, pay once with "Renew my membership automatically" selected.'
              : 'Turn on to renew automatically with a saved method.'}
        </Text>
      )}
      {update.error && !choose && <Text size="xs" c="red">{errorMessage(update.error)}</Text>}
      <Modal opened={choose} onClose={() => setChoose(false)} title="Turn on autopay" centered>
        <Stack>
          {mandated.length === 0 ? (
            <Alert color="yellow">
              No saved method is authorised for automatic debits yet. Pay an invoice online with
              &ldquo;Renew my membership automatically&rdquo; selected to set one up.
            </Alert>
          ) : (
            <Select label="Charge" data={mandated.map((m) => ({ value: String(m.id), label: m.label }))}
              value={methodId} onChange={setMethodId} allowDeselect={false} />
          )}
          {update.error && <Alert color="red">{errorMessage(update.error)}</Alert>}
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setChoose(false)}>Cancel</Button>
            <Button disabled={!methodId} loading={update.isPending}
              onClick={() => methodId && update.mutate({ enabled: true, paymentMethodId: Number(methodId) })}>
              Turn on
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}
