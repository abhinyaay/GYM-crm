import { Paper, Stack, Text, Title } from '@mantine/core';
import type { Member, Subscription } from '@/api/types';
import { AutopayControl } from '@/components/billing/AutopayControl';
import { PaymentMethodsPanel } from '@/components/billing/PaymentMethodsPanel';
import { PageHeader } from '@/components/PageHeader';
import { QueryState } from '@/components/QueryState';
import { useApiQuery } from '@/lib/useApi';

export default function MemberPaymentMethodsPage() {
  const me = useApiQuery<Member>('/api/v1/members/me');
  const currentId = me.data?.membership.subscriptionId ?? null;
  const current = useApiQuery<Subscription>(currentId ? `/api/v1/subscriptions/${currentId}` : null);

  return (
    <>
      <PageHeader title="Cards & autopay" subtitle="Saved payment methods, automatic renewal and store credit" />
      <QueryState isLoading={me.isLoading} error={me.error}>
        {me.data && (
          <Stack gap="xl">
            <Paper withBorder radius="md" p="lg">
              <Title order={4} mb={4}>Automatic renewal</Title>
              {current.data ? (
                <>
                  <Text size="sm" c="dimmed" mb="sm">{current.data.planName}</Text>
                  <AutopayControl subscription={current.data} />
                </>
              ) : (
                <Text size="sm" c="dimmed">You don't have an active membership to renew.</Text>
              )}
            </Paper>
            <PaymentMethodsPanel memberId={me.data.id} />
          </Stack>
        )}
      </QueryState>
    </>
  );
}
