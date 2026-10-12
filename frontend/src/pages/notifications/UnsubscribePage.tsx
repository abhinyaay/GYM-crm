import { Alert, Anchor, Button, Center, Group, Paper, Stack, Text, ThemeIcon, Title } from '@mantine/core';
import { IconAlertCircle, IconMailOff, IconMailCheck } from '@tabler/icons-react';
import { useMutation } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { api } from '@/api/client';
import type { UnsubscribeResponse } from '@/api/notifications';
import { errorMessage } from '@/lib/notify';

/**
 * Public page behind the unsubscribe link in marketing e-mails. Nothing happens on page load (mail scanners
 * prefetch links); one click unsubscribes, and the change can be undone right away.
 */
export default function UnsubscribePage() {
  const [params] = useSearchParams();
  const token = params.get('token') ?? '';
  const call = useMutation<UnsubscribeResponse, Error, boolean>({
    mutationFn: (resubscribe) => api<UnsubscribeResponse>('/api/v1/public/notifications/unsubscribe', {
      method: 'POST', auth: false, body: { token, resubscribe },
    }),
  });
  const result = call.data;

  return (
    <Center mih="100vh" p="md">
      <Paper withBorder shadow="sm" p="xl" radius="md" maw={460} w="100%">
        <Stack gap="md" align="center" ta="center">
          <ThemeIcon size={56} radius="xl" variant="light" color={result && !result.subscribed ? 'teal' : 'blue'}>
            {result?.subscribed === false ? <IconMailCheck size={30} /> : <IconMailOff size={30} />}
          </ThemeIcon>
          {!token ? (
            <>
              <Title order={3}>Unsubscribe</Title>
              <Alert color="red" icon={<IconAlertCircle size={18} />} w="100%">
                This link is incomplete. Use the unsubscribe link from the e-mail you received.
              </Alert>
            </>
          ) : result ? (
            <>
              <Title order={3}>{result.subscribed ? 'You are subscribed again' : 'You have been unsubscribed'}</Title>
              <Text c="dimmed">
                {result.subscribed
                  ? `${result.email} will receive ${result.categoryLabel.toLowerCase()} by e-mail again.`
                  : `${result.email} will no longer receive ${result.categoryLabel.toLowerCase()} by e-mail. `
                    + 'Receipts, membership and class updates are not affected.'}
              </Text>
              <Group>
                <Button variant="default" loading={call.isPending} onClick={() => call.mutate(!result.subscribed)}>
                  {result.subscribed ? 'Unsubscribe again' : 'Undo – keep me subscribed'}
                </Button>
              </Group>
            </>
          ) : (
            <>
              <Title order={3}>Unsubscribe from offers &amp; news</Title>
              <Text c="dimmed">
                Stop promotional e-mails from your gym. You will still get receipts, membership reminders and class updates.
              </Text>
              {call.error && (
                <Alert color="red" icon={<IconAlertCircle size={18} />} w="100%">{errorMessage(call.error)}</Alert>
              )}
              <Button color="red" loading={call.isPending} onClick={() => call.mutate(false)}>Unsubscribe</Button>
            </>
          )}
          <Text size="xs" c="dimmed">
            Members can manage all notification settings after <Anchor component={Link} to="/me/notifications" size="xs">signing in</Anchor>.
          </Text>
        </Stack>
      </Paper>
    </Center>
  );
}
