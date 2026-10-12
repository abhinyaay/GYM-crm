import {
  ActionIcon, Alert, Badge, Button, Center, CopyButton, Group, Loader, Paper, RingProgress, SimpleGrid, Stack, Text, Title,
  Tooltip,
} from '@mantine/core';
import { IconCheck, IconCopy, IconFingerprint, IconId, IconQrcode, IconShieldCheck, IconShieldOff, IconUserScan } from '@tabler/icons-react';
import QRCode from 'qrcode';
import { useEffect, useState } from 'react';
import type { AccessEvent, ConsentStatus, Credential, EntryPass } from '@/api/access';
import type { Page } from '@/api/types';
import { AccessEventTable, GrantConsentModal, WithdrawConsentModal } from '@/components/access/MemberAccessPanel';
import { PageHeader } from '@/components/PageHeader';
import { Pager } from '@/components/Pager';
import { QueryState } from '@/components/QueryState';
import { StatusBadge } from '@/components/StatusBadge';
import { dateTime, label } from '@/lib/format';
import { errorMessage } from '@/lib/notify';
import { useFeatures } from '@/lib/features';
import { useApiQuery } from '@/lib/useApi';

/**
 * Member's rotating QR entry pass: a new code every 30 seconds (HMAC of member + time window, verified by the gate,
 * usable once). Also shows recent gate entries / exits and lets the member manage biometric consent.
 */
export default function MemberEntryPassPage() {
  const { gateAccess, biometrics } = useFeatures();
  if (!gateAccess) {
    return <PageHeader title="Entry pass" subtitle="Gate entry is not used at this gym; check in at the front desk." />;
  }
  return (
    <>
      <PageHeader title="Entry pass" subtitle="Show this code at the gate. It changes every 30 seconds and works once." />
      <SimpleGrid cols={{ base: 1, md: 2 }} spacing="md">
        <PassCard />
        <Stack gap="md">
          <MyCredentials />
          {biometrics && <MyConsent />}
        </Stack>
      </SimpleGrid>
      <RecentEntries />
    </>
  );
}

function PassCard() {
  const pass = useApiQuery<EntryPass>('/api/v1/access/me/pass', undefined, {
    // refresh right after the current window ends
    refetchInterval: (q) => {
      const data = q.state.data as EntryPass | undefined;
      if (!data) return 30_000;
      const skew = Date.parse(data.serverTime) - q.state.dataUpdatedAt;
      return Math.max(1000, Date.parse(data.expiresAt) - (Date.now() + skew) + 300);
    },
    refetchIntervalInBackground: false,
    staleTime: 0,
  });
  const [image, setImage] = useState<string | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const token = pass.data?.token;

  useEffect(() => {
    let cancelled = false;
    if (!token) return undefined;
    QRCode.toDataURL(token, { errorCorrectionLevel: 'M', margin: 1, width: 280 })
      .then((url) => {
        if (!cancelled) setImage(url);
      })
      .catch(() => setImage(null));
    return () => {
      cancelled = true;
    };
  }, [token]);

  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 250);
    return () => clearInterval(t);
  }, []);

  const data = pass.data;
  // server clock minus local clock at fetch time: the countdown follows the server's windows
  const skew = data ? Date.parse(data.serverTime) - pass.dataUpdatedAt : 0;
  const remaining = data ? Math.max(0, Math.ceil((Date.parse(data.expiresAt) - (now + skew)) / 1000)) : 0;
  const total = data?.windowSeconds ?? 30;
  const expired = data != null && remaining === 0;
  const { refetch, isFetching } = pass;
  // belt and braces next to refetchInterval: fetch the next code as soon as the countdown hits zero
  useEffect(() => {
    if (expired && !isFetching) void refetch();
  }, [expired, isFetching, refetch]);

  return (
    <Paper withBorder p="lg" radius="md">
      {pass.error ? (
        <Alert color="red" title="Entry pass unavailable">{errorMessage(pass.error)}</Alert>
      ) : !data ? (
        <Center mih={340}><Loader /></Center>
      ) : (
        <Stack align="center" gap="sm">
          <Text fw={700} fz="lg">{data.memberName}</Text>
          <Badge variant="light">{data.memberCode}</Badge>
          <Paper p={8} radius="md" bg="white" withBorder>
            {image ? <img src={image} alt="Entry QR code" width={260} height={260} style={{ display: 'block' }} />
              : <Center w={260} h={260}><Loader size="sm" /></Center>}
          </Paper>
          <Group gap="sm">
            <RingProgress size={54} thickness={5} roundCaps
              sections={[{ value: (remaining / total) * 100, color: remaining <= 5 ? 'orange' : 'teal' }]}
              label={<Text ta="center" size="xs" fw={700}>{remaining}s</Text>} />
            <Text size="sm" c="dimmed">New code in {remaining}s</Text>
          </Group>
          <Group gap={4}>
            <Text size="xs" c="dimmed" ff="monospace">{data.token.slice(0, 18)}…</Text>
            <CopyButton value={data.token} timeout={1500}>
              {({ copied, copy }) => (
                <Tooltip label={copied ? 'Copied' : 'Copy code (for the gate simulator demo)'}>
                  <ActionIcon size="sm" variant="subtle" onClick={copy} color={copied ? 'teal' : 'gray'} aria-label="Copy code">
                    {copied ? <IconCheck size={14} /> : <IconCopy size={14} />}
                  </ActionIcon>
                </Tooltip>
              )}
            </CopyButton>
          </Group>
          <Text size="xs" c="dimmed" ta="center" maw={320}>
            Screenshots stop working within a minute, and each code opens the gate only once.
          </Text>
        </Stack>
      )}
    </Paper>
  );
}

const ICONS = { FINGERPRINT: IconFingerprint, FACE: IconUserScan, RFID_CARD: IconId, QR: IconQrcode };

function MyCredentials() {
  const creds = useApiQuery<Credential[]>('/api/v1/access/me/credentials');
  const list = (creds.data ?? []).filter((c) => c.status !== 'REVOKED');
  return (
    <Paper withBorder p="md">
      <Title order={5} mb="sm">My access methods</Title>
      <QueryState isLoading={creds.isLoading} error={creds.error} empty={list.length === 0}
        emptyText="Only the QR pass. Ask the front desk for a card or fingerprint enrolment.">
        <Stack gap={6}>
          {list.map((c) => {
            const Icon = ICONS[c.type];
            return (
              <Group key={c.id} justify="space-between">
                <Group gap="xs"><Icon size={18} /><Text size="sm">{label(c.type)}{c.label ? ` · ${c.label}` : ''}</Text>
                  <Text size="xs" c="dimmed">{c.displayHint}</Text></Group>
                <StatusBadge value={c.status} />
              </Group>
            );
          })}
        </Stack>
      </QueryState>
    </Paper>
  );
}

function MyConsent() {
  const status = useApiQuery<ConsentStatus>('/api/v1/access/me/consent');
  const [granting, setGranting] = useState(false);
  const [withdrawing, setWithdrawing] = useState(false);
  const s = status.data;
  return (
    <Paper withBorder p="md">
      <Group justify="space-between" mb="xs">
        <Group gap="xs">
          <Title order={5}>Biometric consent</Title>
          {s && (s.active ? <Badge color="teal" variant="light" leftSection={<IconShieldCheck size={12} />}>Given</Badge>
            : <Badge color="gray" variant="light" leftSection={<IconShieldOff size={12} />}>Not given</Badge>)}
        </Group>
        {s && (s.active
          ? <Button size="xs" variant="light" color="red" onClick={() => setWithdrawing(true)}>Withdraw</Button>
          : <Button size="xs" variant="light" onClick={() => setGranting(true)}>Read notice &amp; consent</Button>)}
      </Group>
      <Text size="xs" c="dimmed">
        Fingerprint / face entry is optional. Your fingerprint or face never leaves the gate device; GymOps keeps only a
        reference number. {s?.current ? `Given ${dateTime(s.current.grantedAt)}.` : ''}
      </Text>
      <GrantConsentModal opened={granting} onClose={() => setGranting(false)} path="/api/v1/access/me/consent" />
      <WithdrawConsentModal opened={withdrawing} onClose={() => setWithdrawing(false)} path="/api/v1/access/me/consent/withdraw" />
    </Paper>
  );
}

function RecentEntries() {
  const [page, setPage] = useState(0);
  const q = useApiQuery<Page<AccessEvent>>('/api/v1/access/me/events', { page, size: 10 });
  return (
    <Paper withBorder p="md" mt="md">
      <Title order={5} mb="sm">Recent entries &amp; exits</Title>
      <QueryState isLoading={q.isLoading} error={q.error} empty={(q.data?.content.length ?? 0) === 0}
        emptyText="No gate entries yet.">
        <AccessEventTable events={q.data?.content ?? []} showMember={false} />
        <Pager page={q.data} onChange={setPage} />
      </QueryState>
    </Paper>
  );
}
