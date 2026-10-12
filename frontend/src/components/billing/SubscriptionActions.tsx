import { Alert, Button, Checkbox, Group, Modal, NumberInput, Stack, Text, Textarea } from '@mantine/core';
import { useState } from 'react';
import type { CancellationResponse, Subscription } from '@/api/types';
import { money } from '@/lib/format';
import { errorMessage } from '@/lib/notify';
import { useApiMutation } from '@/lib/useApi';

const INVALIDATE = ['/api/v1/subscriptions', '/api/v1/members', '/api/v1/payments', '/api/v1/dashboard'];

export function FreezeModal({ subscription, opened, onClose }: { subscription: Subscription | null; opened: boolean; onClose: () => void }) {
  const [days, setDays] = useState<number | string>(7);
  const [reason, setReason] = useState('');
  const freeze = useApiMutation<void, Subscription>({
    request: () => ({ path: `/api/v1/subscriptions/${subscription?.id}/freeze`, body: { days: Number(days), reason } }),
    invalidate: INVALIDATE,
    successMessage: (s) => `Frozen until ${s.frozenUntil}; membership now ends ${s.endDate}`,
    silentError: true,
    onSuccess: onClose,
  });
  return (
    <Modal opened={opened} onClose={onClose} title="Freeze membership" centered>
      <Stack>
        <Text size="sm" c="dimmed">The freeze starts today and extends the end date by the same number of days.</Text>
        <NumberInput label="Days" min={1} max={365} value={days} onChange={setDays} />
        <Textarea label="Reason" value={reason} onChange={(e) => setReason(e.currentTarget.value)} />
        {freeze.error && <Alert color="red">{errorMessage(freeze.error)}</Alert>}
        <Button onClick={() => freeze.mutate()} loading={freeze.isPending}>Freeze</Button>
      </Stack>
    </Modal>
  );
}

export function CancelModal({ subscription, opened, onClose, allowSkipRefund }: {
  subscription: Subscription | null;
  opened: boolean;
  onClose: () => void;
  allowSkipRefund: boolean;
}) {
  const [reason, setReason] = useState('');
  const [refund, setRefund] = useState(true);
  const cancel = useApiMutation<void, CancellationResponse>({
    request: () => ({ path: `/api/v1/subscriptions/${subscription?.id}/cancel`, body: { reason, refund } }),
    invalidate: INVALIDATE,
    successMessage: (r) => r.refundAmount > 0 ? `Cancelled - refund of ${money(r.refundAmount)} issued` : 'Cancelled',
    silentError: true,
    onSuccess: () => {
      setReason('');
      onClose();
    },
  });
  return (
    <Modal opened={opened} onClose={onClose} title="Cancel membership" centered>
      <Stack>
        <Text size="sm">
          Any refund is calculated from the plan's cancellation policy (non-refundable, prorated or cooling-off).
        </Text>
        <Textarea label="Reason" required value={reason} onChange={(e) => setReason(e.currentTarget.value)} />
        {allowSkipRefund && (
          <Checkbox label="Refund according to policy" checked={refund} onChange={(e) => setRefund(e.currentTarget.checked)} />
        )}
        {cancel.error && <Alert color="red">{errorMessage(cancel.error)}</Alert>}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>Keep membership</Button>
          <Button color="red" disabled={!reason.trim()} onClick={() => cancel.mutate()} loading={cancel.isPending}>
            Cancel membership
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
