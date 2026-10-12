import { Alert, Paper, Stack, Switch, Text, Title } from '@mantine/core';
import { IconFingerprint, IconDoorEnter } from '@tabler/icons-react';
import type { Features } from '@/lib/features';
import { PageHeader } from '@/components/PageHeader';
import { QueryState } from '@/components/QueryState';
import { useApiMutation, useApiQuery } from '@/lib/useApi';

/** Owner switches for optional modules. Everything else in GymOps works without them. */
export default function SettingsPage() {
  const features = useApiQuery<Features>('/api/v1/settings/features');
  const update = useApiMutation<Partial<Features>, Features>({
    request: (body) => ({ path: '/api/v1/settings/features', method: 'PUT', body }),
    invalidate: ['/api/v1/settings', '/api/v1/access'],
    successMessage: 'Settings saved',
  });
  const f = features.data;

  return (
    <>
      <PageHeader title="Settings" subtitle="Optional modules for your gym" />
      <QueryState isLoading={features.isLoading} error={features.error}>
        {f && (
          <Stack gap="md" maw={760}>
            <Paper withBorder radius="md" p="lg">
              <Switch size="md" checked={f.gateAccess} disabled={update.isPending}
                onChange={(e) => update.mutate({ gateAccess: e.currentTarget.checked })}
                label={<Title order={5}>Gate access control</Title>}
                thumbIcon={<IconDoorEnter size={12} />} />
              <Text size="sm" c="dimmed" mt="xs">
                Turnstiles / doors that check members at the entrance (RFID card, QR pass from the app and, optionally,
                fingerprint or face). Turn it off if your gym has no gate hardware: the front desk checks members in as
                usual, and the Access control menu, the member Entry pass and the Gate access tab are hidden. Gate devices
                are refused while it is off.
              </Text>
            </Paper>
            <Paper withBorder radius="md" p="lg" opacity={f.gateAccess ? 1 : 0.6}>
              <Switch size="md" checked={f.biometrics} disabled={!f.gateAccess || update.isPending}
                onChange={(e) => update.mutate({ biometrics: e.currentTarget.checked })}
                label={<Title order={5}>Fingerprint &amp; face</Title>}
                thumbIcon={<IconFingerprint size={12} />} />
              <Text size="sm" c="dimmed" mt="xs">
                Biometric entry is optional. When it is off, members enter with their RFID card or the QR pass, biometric
                consent and enrolment are hidden, and fingerprint / face scans are refused (they are also left out of the
                gates&apos; offline list). Existing enrolments are kept, so you can switch it back on later.
              </Text>
              {!f.gateAccess && <Text size="xs" c="dimmed" mt="xs">Turn on gate access control first.</Text>}
            </Paper>
            <Alert variant="light" color="blue">
              How members leave (exit button or scan to exit) is set per branch in Access control → Entry &amp; exit
              settings. Daily entry limits are set per plan on the Plans page.
            </Alert>
          </Stack>
        )}
      </QueryState>
    </>
  );
}
