import { Alert, Badge, Button, Card, Group, Modal, SimpleGrid, Stack, Text, TextInput } from '@mantine/core';
import { NegotiatedPriceFields, useNegotiatedPrice } from './NegotiatedPriceFields';
import { DatePickerInput } from '@mantine/dates';
import dayjs from 'dayjs';
import { useState } from 'react';
import type { Plan, Subscription } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { isMemberOnly } from '@/auth/roles';
import { label, money } from '@/lib/format';
import { errorMessage } from '@/lib/notify';
import { useApiMutation, useApiQuery } from '@/lib/useApi';

export type PlanAction =
  | { kind: 'subscribe'; memberId: number }
  | { kind: 'renew'; subscriptionId: number; currentPlanId: number }
  | { kind: 'change'; subscriptionId: number; currentPlanId: number };

const TITLES = { subscribe: 'Buy a membership', renew: 'Renew membership', change: 'Change plan' };

/** Plan picker for new purchase, renewal or plan change. Creates a PENDING_PAYMENT subscription + invoice. */
export function SubscribeModal({ action, opened, onClose, onCreated }: {
  action: PlanAction | null;
  opened: boolean;
  onClose: () => void;
  onCreated: (subscription: Subscription) => void;
}) {
  const { user } = useAuth();
  const { data: allPlans = [] } = useApiQuery<Plan[]>('/api/v1/plans');
  // free plans (trials) are granted by staff, not bought by members
  const plans = isMemberOnly(user) ? allPlans.filter((p) => p.price > 0 || p.joiningFee > 0) : allPlans;
  const [planId, setPlanId] = useState<number | null>(null);
  const [startDate, setStartDate] = useState<Date | null>(null);
  const [discountCode, setDiscountCode] = useState('');
  // managers / admins may sell a new membership at a negotiated price (see NegotiatedPriceFields)
  const negotiated = useNegotiatedPrice(action?.kind === 'subscribe');

  const create = useApiMutation<number, Subscription>({
    request: (planId) => {
      if (!action) throw new Error('no action');
      const discount = discountCode.trim() || undefined;
      if (action.kind === 'subscribe') {
        return {
          path: '/api/v1/subscriptions',
          body: { memberId: action.memberId, planId, discountCode: discount,
            startDate: startDate ? dayjs(startDate).format('YYYY-MM-DD') : undefined, ...negotiated.body() },
        };
      }
      if (action.kind === 'renew') {
        return { path: `/api/v1/subscriptions/${action.subscriptionId}/renew`, body: { planId, discountCode: discount } };
      }
      return { path: `/api/v1/subscriptions/${action.subscriptionId}/change-plan`, body: { newPlanId: planId, discountCode: discount } };
    },
    invalidate: ['/api/v1/subscriptions', '/api/v1/members', '/api/v1/payments', '/api/v1/dashboard'],
    silentError: true,
    onSuccess: (s) => {
      onCreated(s);
      setPlanId(null);
      setDiscountCode('');
      setStartDate(null);
      negotiated.reset();
    },
  });

  if (!action) return null;
  const selectable = plans.filter((p) => action.kind !== 'change' || p.id !== action.currentPlanId);
  const selected = planId ?? (action.kind === 'renew' ? action.currentPlanId : null);

  return (
    <Modal opened={opened} onClose={onClose} title={TITLES[action.kind]} size="xl" centered>
      <Stack>
        {action.kind === 'change' && (
          <Text size="sm" c="dimmed">
            A more expensive plan is an upgrade: it starts today and the unused part of the current term is credited.
            A cheaper plan is a downgrade and starts when the current term ends.
          </Text>
        )}
        <SimpleGrid cols={{ base: 1, sm: 2, md: 3 }}>
          {selectable.map((p) => (
            <Card
              key={p.id}
              withBorder
              padding="md"
              radius="md"
              onClick={() => setPlanId(p.id)}
              style={{ cursor: 'pointer', outline: selected === p.id ? '2px solid var(--mantine-color-teal-6)' : undefined }}
            >
              <Group justify="space-between" mb={4}>
                <Text fw={600}>{p.name}</Text>
                <Badge variant="light" size="sm">{label(p.billingPeriod)}</Badge>
              </Group>
              <Text fz={22} fw={700}>{money(p.price)}</Text>
              {p.joiningFee > 0 && <Text size="xs" c="dimmed">+ {money(p.joiningFee)} joining fee (first time)</Text>}
              <Stack gap={2} mt="xs">
                {p.benefits.map((b) => (
                  <Text size="xs" key={b}>• {b}</Text>
                ))}
                {p.access.accessStartTime && (
                  <Text size="xs" c="orange">Entry {p.access.accessStartTime.slice(0, 5)}–{p.access.accessEndTime?.slice(0, 5)}</Text>
                )}
              </Stack>
            </Card>
          ))}
        </SimpleGrid>
        <Group grow align="flex-end">
          {action.kind === 'subscribe' && (
            <DatePickerInput
              label="Start date"
              placeholder="Today"
              value={startDate}
              onChange={setStartDate}
              minDate={new Date()}
              maxDate={dayjs().add(90, 'day').toDate()}
              clearable
            />
          )}
          <TextInput label="Discount code" placeholder="Optional" value={discountCode} disabled={negotiated.active}
            onChange={(e) => setDiscountCode(e.currentTarget.value.toUpperCase())} />
        </Group>
        {negotiated.allowed && (
          <NegotiatedPriceFields state={negotiated} listPrice={plans.find((p) => p.id === selected)?.price ?? null} />
        )}
        {create.error && <Alert color="red">{errorMessage(create.error)}</Alert>}
        <Button disabled={!selected || !negotiated.valid(plans.find((p) => p.id === selected)?.price ?? null)}
          loading={create.isPending} onClick={() => selected && create.mutate(selected)}>
          Create invoice
        </Button>
      </Stack>
    </Modal>
  );
}
