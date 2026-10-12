import { ActionIcon, Badge, Button, Drawer, Group, Indicator, Paper, ScrollArea, Stack, Text, Tooltip } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { IconBell } from '@tabler/icons-react';
import type { NotificationItem, Page } from '@/api/types';
import { fromNow, label } from '@/lib/format';
import { useApiMutation, useApiQuery } from '@/lib/useApi';

/** Bell icon with unread count + in-app inbox (the notification outbox's IN_APP channel). */
export function NotificationsBell() {
  const [opened, { open, close }] = useDisclosure(false);
  const unread = useApiQuery<{ unread: number }>('/api/v1/notifications/unread-count', undefined, { refetchInterval: 60_000 });
  const inbox = useApiQuery<Page<NotificationItem>>(opened ? '/api/v1/notifications' : null, { size: 30 });
  const markRead = useApiMutation<number>({
    request: (id) => ({ path: `/api/v1/notifications/${id}/read`, method: 'PATCH' }),
    invalidate: ['/api/v1/notifications'],
  });
  const markAll = useApiMutation({
    request: () => ({ path: '/api/v1/notifications/read-all' }),
    invalidate: ['/api/v1/notifications'],
  });
  const count = unread.data?.unread ?? 0;

  return (
    <>
      <Tooltip label="Notifications">
        <Indicator label={count} size={16} disabled={count === 0} color="red" offset={4}>
          <ActionIcon variant="subtle" size="lg" onClick={open} aria-label="Notifications">
            <IconBell size={20} />
          </ActionIcon>
        </Indicator>
      </Tooltip>
      <Drawer opened={opened} onClose={close} position="right" title="Notifications" size="md">
        <Group justify="flex-end" mb="sm">
          <Button size="xs" variant="subtle" onClick={() => markAll.mutate()} disabled={count === 0}>
            Mark all as read
          </Button>
        </Group>
        <ScrollArea h="calc(100vh - 140px)">
          <Stack gap="xs">
            {(inbox.data?.content ?? []).map((n) => (
              <Paper
                key={n.id}
                withBorder
                p="sm"
                radius="md"
                bg={n.readAt ? undefined : 'var(--mantine-color-teal-light)'}
                onClick={() => !n.readAt && markRead.mutate(n.id)}
                style={{ cursor: n.readAt ? 'default' : 'pointer' }}
              >
                <Group justify="space-between" align="flex-start" wrap="nowrap">
                  <Text fw={600} size="sm">{n.title}</Text>
                  {!n.readAt && <Badge size="xs" color="teal">new</Badge>}
                </Group>
                <Text size="sm" mt={4}>{n.body}</Text>
                <Text size="xs" c="dimmed" mt={4}>{label(n.type)} · {fromNow(n.createdAt)}</Text>
              </Paper>
            ))}
            {inbox.data && inbox.data.content.length === 0 && (
              <Text c="dimmed" ta="center" py="xl">No notifications yet.</Text>
            )}
          </Stack>
        </ScrollArea>
      </Drawer>
    </>
  );
}
