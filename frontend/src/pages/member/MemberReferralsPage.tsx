import {
  Alert, Badge, Button, Code, CopyButton, Group, Paper, SimpleGrid, Stack, Table, Text, TextInput, ThemeIcon, Title,
  Tooltip,
} from '@mantine/core';
import {
  IconCheck, IconCoin, IconCopy, IconGift, IconHourglass, IconShare, IconTicket, IconUserPlus, IconUsers,
} from '@tabler/icons-react';
import { useState } from 'react';
import {
  REFERRAL_STATUS_COLOR, REFERRAL_STATUS_LABEL, REJECTION_LABEL, REWARD_STATUS_COLOR, SKIP_LABEL,
  type MyReferral, type MyReferralDashboard, type RewardView,
} from '@/api/growth';
import { PageHeader } from '@/components/PageHeader';
import { QueryState } from '@/components/QueryState';
import { StatCard } from '@/components/StatCard';
import { StatusBadge } from '@/components/StatusBadge';
import { date, money, number } from '@/lib/format';
import { notifySuccess } from '@/lib/notify';
import { useApiMutation, useApiQuery } from '@/lib/useApi';

/** Member portal: my referral code, share link, how it works, my referrals and rewards. */
export default function MemberReferralsPage() {
  const q = useApiQuery<MyReferralDashboard>('/api/v1/referrals/me');
  const d = q.data;
  return (
    <>
      <PageHeader title="Refer a friend" subtitle="Share your code - you both get rewarded when your friend joins" />
      <QueryState isLoading={q.isLoading} error={q.error}>
        {d && (
          <Stack gap="lg">
            <ShareCard d={d} />
            <SimpleGrid cols={{ base: 2, md: 4 }}>
              <StatCard label="Friends invited" value={number(d.invited)} icon={<IconUsers size={20} />} color="blue" />
              <StatCard label="Joined" value={number(d.joined)} icon={<IconUserPlus size={20} />} color="teal" />
              <StatCard label="Credit earned" value={money(d.creditEarned)} icon={<IconCoin size={20} />} color="teal"
                hint="Added to your store credit" />
              <StatCard label="On its way" value={money(d.creditPending)} icon={<IconHourglass size={20} />}
                color="orange" hint={`Released ${d.holdDays} days after your friend joins`} />
            </SimpleGrid>
            <HowItWorks d={d} />
            <MyReferrals items={d.referrals} />
            <WelcomeRewards d={d} />
          </Stack>
        )}
      </QueryState>
    </>
  );
}

function ShareCard({ d }: { d: MyReferralDashboard }) {
  const link = `${window.location.origin}${d.sharePath}`;
  const canShare = typeof navigator !== 'undefined' && typeof navigator.share === 'function';
  const share = async () => {
    const text = `${d.headline}. ${d.offerText}`;
    if (canShare) {
      try {
        await navigator.share({ title: 'Join me at the gym', text, url: link });
        return;
      } catch (err) {
        if (err instanceof DOMException && err.name === 'AbortError') return;   // user closed the share sheet
      }
    }
    try {
      await navigator.clipboard.writeText(link);
      notifySuccess('Link copied - paste it into WhatsApp, SMS or email');
    } catch {
      notifySuccess(`Copy this link: ${link}`);
    }
  };
  return (
    <Paper withBorder p="lg" radius="md" style={{ background: 'var(--mantine-color-teal-light)' }}>
      <Group justify="space-between" align="flex-start" wrap="wrap" gap="lg">
        <Stack gap={6} style={{ minWidth: 0, flex: '1 1 280px' }}>
          <Text size="sm" c="dimmed" tt="uppercase" fw={600}>Your referral code</Text>
          <Group gap="xs">
            <Title order={1} ff="monospace" style={{ letterSpacing: 2 }}>{d.code}</Title>
            <CopyButton value={d.code}>
              {({ copied, copy }) => (
                <Tooltip label={copied ? 'Copied' : 'Copy code'}>
                  <Button variant="subtle" size="compact-sm" onClick={copy} aria-label="Copy code"
                    leftSection={copied ? <IconCheck size={14} /> : <IconCopy size={14} />}>
                    {copied ? 'Copied' : 'Copy'}
                  </Button>
                </Tooltip>
              )}
            </CopyButton>
          </Group>
          <Text size="sm">{d.referrerRewardText}</Text>
          <Text size="sm" c="dimmed">Your friend: {d.offerText}</Text>
        </Stack>
        <Stack gap="xs" style={{ flex: '1 1 300px', minWidth: 0 }}>
          <TextInput label="Share link" value={link} readOnly onFocus={(e) => e.currentTarget.select()} />
          <Group gap="xs" grow>
            {canShare && <Button leftSection={<IconShare size={16} />} onClick={share}>Share</Button>}
            <CopyButton value={link}>
              {({ copied, copy }) => (
                <Button variant={canShare ? 'default' : 'filled'} onClick={copy}
                  leftSection={copied ? <IconCheck size={16} /> : <IconCopy size={16} />}>
                  {copied ? 'Copied' : 'Copy link'}
                </Button>
              )}
            </CopyButton>
          </Group>
        </Stack>
      </Group>
    </Paper>
  );
}

function HowItWorks({ d }: { d: MyReferralDashboard }) {
  const steps = [
    { icon: <IconShare size={20} />, title: 'Share your link or code',
      text: 'Friends can book a free trial through your link, or give your code at the front desk.' },
    { icon: <IconUserPlus size={20} />, title: 'Your friend joins',
      text: 'When they buy their first paid membership, the referral counts. Existing members do not qualify.' },
    { icon: <IconGift size={20} />, title: 'You both get rewarded',
      text: `After a ${d.holdDays}-day confirmation period your credit is added and your friend gets their welcome reward. `
        + `Up to ${d.monthlyCap} rewarded referrals per month.` },
  ];
  return (
    <Paper withBorder p="md" radius="md">
      <Title order={4} mb="md">How it works</Title>
      <SimpleGrid cols={{ base: 1, sm: 3 }}>
        {steps.map((s, i) => (
          <Group key={s.title} align="flex-start" wrap="nowrap" gap="sm">
            <ThemeIcon size={38} radius="xl" variant="light" color="teal">{s.icon}</ThemeIcon>
            <div>
              <Text fw={600}>{i + 1}. {s.title}</Text>
              <Text size="sm" c="dimmed">{s.text}</Text>
            </div>
          </Group>
        ))}
      </SimpleGrid>
    </Paper>
  );
}

function rewardCell(r: MyReferral) {
  if (r.referrerRewardSkipped) return <Text size="sm" c="dimmed">{SKIP_LABEL[r.referrerRewardSkipped]}</Text>;
  if (!r.reward) return <Text size="sm" c="dimmed">{r.status === 'PENDING' ? 'When they join' : '—'}</Text>;
  const w = r.reward;
  return (
    <Group gap={6} wrap="nowrap">
      <Text size="sm" fw={500}>{money(w.amount)}</Text>
      <StatusBadge value={w.status} color={REWARD_STATUS_COLOR[w.status]} size="sm" />
      {w.status === 'HELD' && <Text size="xs" c="dimmed">on {date(w.availableAt)}</Text>}
    </Group>
  );
}

function MyReferrals({ items }: { items: MyReferral[] }) {
  return (
    <Paper withBorder p="md" radius="md">
      <Title order={4} mb="sm">Your referrals</Title>
      {items.length === 0 ? (
        <Text c="dimmed" ta="center" py="lg">No referrals yet. Share your link to get started!</Text>
      ) : (
        <Table.ScrollContainer minWidth={560}>
          <Table verticalSpacing="sm" highlightOnHover>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Friend</Table.Th>
                <Table.Th>Status</Table.Th>
                <Table.Th>Signed up</Table.Th>
                <Table.Th>Joined</Table.Th>
                <Table.Th>Your reward</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {items.map((r) => (
                <Table.Tr key={r.id}>
                  <Table.Td><Text fw={500}>{r.refereeName}</Text></Table.Td>
                  <Table.Td>
                    <Badge variant="light" color={REFERRAL_STATUS_COLOR[r.status]} radius="sm">
                      {REFERRAL_STATUS_LABEL[r.status]}
                    </Badge>
                    {r.rejectionReason && <Text size="xs" c="dimmed">{REJECTION_LABEL[r.rejectionReason]}</Text>}
                  </Table.Td>
                  <Table.Td>{date(r.createdAt)}</Table.Td>
                  <Table.Td>{date(r.convertedAt)}</Table.Td>
                  <Table.Td>{rewardCell(r)}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
    </Paper>
  );
}

function WelcomeRewards({ d }: { d: MyReferralDashboard }) {
  const [code, setCode] = useState('');
  const claim = useApiMutation<string, MyReferralDashboard>({
    request: (c) => ({ path: '/api/v1/referrals/me/claim', body: { code: c } }),
    invalidate: ['/api/v1/referrals/me'],
    successMessage: 'Referral code added - your welcome reward follows your first membership',
  });
  if (d.welcomeRewards.length === 0 && !d.referredBy) {
    if (!d.canClaimCode) return null;
    return (
      <Paper withBorder p="md" radius="md">
        <Title order={5} mb={4}>Did a friend invite you?</Title>
        <Text size="sm" c="dimmed" mb="sm">
          Enter their code before you buy your first membership and you'll get a welcome reward.
        </Text>
        <Group align="flex-end" gap="xs">
          <TextInput placeholder="e.g. ASHA7K2" value={code} onChange={(e) => setCode(e.currentTarget.value.toUpperCase())}
            maxLength={20} w={{ base: '100%', xs: 220 }} aria-label="Friend's referral code" />
          <Button variant="light" disabled={code.trim().length < 5} loading={claim.isPending}
            onClick={() => claim.mutate(code.trim())}>Apply code</Button>
        </Group>
      </Paper>
    );
  }
  return (
    <Paper withBorder p="md" radius="md">
      <Title order={5} mb="sm">Your welcome reward{d.referredBy ? ` (invited by ${d.referredBy})` : ''}</Title>
      {d.welcomeRewards.length === 0 ? (
        <Text size="sm" c="dimmed">Your welcome reward is unlocked when you buy your first membership.</Text>
      ) : (
        <Stack gap="xs">
          {d.welcomeRewards.map((w) => <WelcomeReward key={w.id} w={w} />)}
        </Stack>
      )}
    </Paper>
  );
}

function WelcomeReward({ w }: { w: RewardView }) {
  if (w.status === 'AVAILABLE' && w.discountCode) {
    return (
      <Alert color={w.codeUsed ? 'gray' : 'teal'} icon={<IconTicket size={18} />}
        title={w.codeUsed ? 'Code used - thanks for joining!' : capitalize(w.description)}>
        <Group gap="xs">
          <Code fz="md">{w.discountCode}</Code>
          {!w.codeUsed && (
            <CopyButton value={w.discountCode}>
              {({ copied, copy }) => (
                <Button size="compact-xs" variant="subtle" onClick={copy}>{copied ? 'Copied' : 'Copy'}</Button>
              )}
            </CopyButton>
          )}
        </Group>
        <Text size="xs" mt={4}>
          Single use, on your account only{w.codeValidUntil ? ` · valid until ${date(w.codeValidUntil)}` : ''}.
          Give it at the desk or enter it when you renew.
        </Text>
      </Alert>
    );
  }
  return (
    <Group gap="xs">
      <Text size="sm">{capitalize(w.description)}</Text>
      <StatusBadge value={w.status} color={REWARD_STATUS_COLOR[w.status]} />
      {w.status === 'HELD' && <Text size="sm" c="dimmed">ready on {date(w.availableAt)}</Text>}
    </Group>
  );
}

const capitalize = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);
