import { Alert, Badge, Group, Paper, Stack, Switch, Text, Tooltip } from '@mantine/core';
import { IconDeviceMobileOff, IconLock, IconMoon } from '@tabler/icons-react';
import {
  preferencesPath, type CategoryPreferences, type ChannelPreference, type NotificationChannel, type PreferenceChange,
  type PreferencesResponse,
} from '@/api/notifications';
import { QueryState } from '@/components/QueryState';
import { useApiMutation, useApiQuery } from '@/lib/useApi';

const COLUMNS: { channel: NotificationChannel; label: string }[] = [
  { channel: 'EMAIL', label: 'E-mail' },
  { channel: 'SMS', label: 'SMS' },
];

/**
 * Category x channel notification preferences for one member. Used by the member themselves (/me/notifications)
 * and by staff on the member detail page. Locked channels (legal / security notices) are shown on and disabled.
 */
export function PreferenceMatrix({ memberId, staff = false }: { memberId: number; staff?: boolean }) {
  const path = preferencesPath(memberId);
  const q = useApiQuery<PreferencesResponse>(path);
  const save = useApiMutation<PreferenceChange, PreferencesResponse>({
    request: (change) => ({ path, method: 'PUT', body: { preferences: [change] } }),
    invalidate: [path],
    successMessage: (r) => (staff ? `Preferences updated for member #${r.memberId}` : 'Preferences saved'),
  });
  const data = q.data;

  return (
    <QueryState isLoading={q.isLoading} error={q.error}>
      {data && (
        <Stack gap="md">
          <Group gap="xs" wrap="wrap">
            <Text size="sm" c="dimmed">E-mails go to <b>{data.email}</b>.</Text>
            {data.smsReachable
              ? <Text size="sm" c="dimmed">Text messages go to <b>{data.smsNumber}</b>.</Text>
              : null}
          </Group>
          {!data.smsReachable && (
            <Alert color="yellow" icon={<IconDeviceMobileOff size={18} />} p="sm">
              {staff ? 'This member' : 'Your profile'} has no mobile number that can receive SMS, so text messages are not sent.
              {staff ? ' Update the phone number on the profile to enable them.' : ' Ask the front desk to update your phone number.'}
            </Alert>
          )}
          <Paper withBorder>
            {data.categories.map((cat, i) => (
              <Group key={cat.category} justify="space-between" align="center" wrap="wrap" gap="sm" px="md" py="sm"
                style={i > 0 ? { borderTop: '1px solid var(--mantine-color-default-border)' } : undefined}>
                <Stack gap={2} style={{ flex: '1 1 260px', minWidth: 0 }}>
                  <Group gap={6}>
                    <Text fw={600} size="sm">{cat.label}</Text>
                    {cat.promotional && (
                      <Tooltip label={`Promotional texts are never sent during quiet hours (${data.quietHours}); they wait until morning.`}
                        multiline w={260}>
                        <Badge size="xs" variant="light" color="grape" leftSection={<IconMoon size={10} />}>Quiet hours</Badge>
                      </Tooltip>
                    )}
                  </Group>
                  <Text size="xs" c="dimmed">{cat.description}</Text>
                  {cat.lockedReason && (
                    <Text size="xs" c="dimmed"><IconLock size={11} style={{ verticalAlign: -1 }} /> {cat.lockedReason}</Text>
                  )}
                </Stack>
                <Group gap="lg" wrap="nowrap">
                  {COLUMNS.map((c) => (
                    <Stack key={c.channel} gap={4} align="center" w={72}>
                      <Text size="xs" c="dimmed">{c.label}</Text>
                      <ChannelToggle category={cat} pref={cat.channels.find((p) => p.channel === c.channel)}
                        smsReachable={data.smsReachable} pending={save.isPending
                          && save.variables?.category === cat.category && save.variables?.channel === c.channel}
                        onChange={(enabled) => save.mutate({ category: cat.category, channel: c.channel, enabled })} />
                    </Stack>
                  ))}
                </Group>
              </Group>
            ))}
          </Paper>
          <Text size="xs" c="dimmed">
            In-app notifications are always on. Messages you must legally receive (payment receipts, autopay pre-debit
            notices) are always e-mailed.
          </Text>
        </Stack>
      )}
    </QueryState>
  );
}

function ChannelToggle({ category, pref, smsReachable, pending, onChange }: {
  category: CategoryPreferences;
  pref: ChannelPreference | undefined;
  smsReachable: boolean;
  pending: boolean;
  onChange: (enabled: boolean) => void;
}) {
  if (!pref) {
    return (
      <Tooltip label={`${category.label} are not sent by text message`}>
        <Text c="dimmed" size="sm" aria-label="Not available">—</Text>
      </Tooltip>
    );
  }
  const label = `${category.label} by ${pref.channel === 'SMS' ? 'SMS' : 'e-mail'}`;
  if (pref.locked) {
    // shown as an explicit "always on" marker: a disabled switch reads as "off" in some themes
    return (
      <Tooltip label={category.lockedReason ?? 'Always on'} multiline w={260}>
        <Badge variant="light" color="gray" size="sm" tt="none" style={{ overflow: 'visible' }} leftSection={<IconLock size={11} />}
          aria-label={`${label}: always on`}>
          On
        </Badge>
      </Tooltip>
    );
  }
  const unreachable = pref.channel === 'SMS' && !smsReachable;
  const toggle = (
    <Switch
      aria-label={label}
      checked={pref.enabled}
      disabled={pending}
      onChange={(e) => onChange(e.currentTarget.checked)}
      style={{ display: 'inline-block' }}
      color={unreachable ? 'gray' : undefined}
    />
  );
  if (unreachable) {
    return <Tooltip label="Saved, but no SMS is sent until a mobile number is on file">{toggle}</Tooltip>;
  }
  return toggle;
}
