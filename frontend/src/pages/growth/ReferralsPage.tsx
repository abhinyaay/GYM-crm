import { BarChart } from '@mantine/charts';
import {
  Alert, Anchor, Badge, Button, Group, List, Modal, NumberInput, Paper, SegmentedControl, SimpleGrid, Stack, Table, Text,
  TextInput, Title, Tooltip,
} from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { IconArrowBackUp, IconCoin, IconGift, IconHourglass, IconLink, IconPlus, IconUsers } from '@tabler/icons-react';
import dayjs from 'dayjs';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import {
  REFERRAL_STATUS_COLOR, REFERRAL_STATUS_LABEL, REJECTION_LABEL, REWARD_STATUS_COLOR, SKIP_LABEL,
  type AttachReferralRequest, type Referral, type ReferralSettings, type ReferralStats,
} from '@/api/growth';
import type { Page } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { hasAnyRole, ORG_WIDE } from '@/auth/roles';
import { BranchSelect } from '@/components/BranchSelect';
import { MemberSearch } from '@/components/MemberSearch';
import { PageHeader } from '@/components/PageHeader';
import { Pager } from '@/components/Pager';
import { QueryState } from '@/components/QueryState';
import { StatCard } from '@/components/StatCard';
import { StatusBadge } from '@/components/StatusBadge';
import { date, money, number, pct } from '@/lib/format';
import { useApiMutation, useApiQuery } from '@/lib/useApi';

const STATUS_FILTERS: { value: string; label: string }[] = [
  { value: '', label: 'All' },
  { value: 'PENDING', label: 'Signed up' },
  { value: 'CONVERTED', label: 'Joined' },
  { value: 'REVERSED', label: 'Reversed' },
  { value: 'REJECTED', label: 'Not eligible' },
];

/** Staff: referral programme statistics, top referrers and recent referrals; attach a code at the desk. */
export default function ReferralsPage() {
  const { user } = useAuth();
  const orgWide = hasAnyRole(user, ORG_WIDE);
  const [branchId, setBranchId] = useState<number | null>(null);
  const [attaching, setAttaching] = useState(false);
  const scope = { branchId: branchId ?? undefined };
  const stats = useApiQuery<ReferralStats>('/api/v1/referrals/stats', scope);
  const settings = useApiQuery<ReferralSettings>('/api/v1/referrals/settings');
  const s = stats.data;

  return (
    <>
      <PageHeader
        title="Referrals"
        subtitle="Member-get-member programme: who refers, who joins and the rewards earned"
        actions={
          <>
            <BranchSelect allowAll value={branchId} onChange={setBranchId} w={{ base: '100%', sm: 240 }}
              clearable={!orgWide} placeholder={orgWide ? 'Select branch' : 'All my branches'} />
            <Button leftSection={<IconPlus size={16} />} onClick={() => setAttaching(true)}>Add referral</Button>
          </>
        }
      />
      <QueryState isLoading={stats.isLoading} error={stats.error}>
        {s && (
          <Stack gap="lg">
            <SimpleGrid cols={{ base: 2, md: 5 }}>
              <StatCard label="Referrals" value={number(s.total)} icon={<IconUsers size={20} />} color="blue"
                hint={`${number(s.byStatus.PENDING)} signed up, not joined yet`} />
              <StatCard label="Joined" value={number(s.byStatus.CONVERTED)} icon={<IconGift size={20} />} color="teal"
                hint={`${pct(s.conversionRate)} conversion`} />
              <StatCard label="Credit issued" value={money(s.issued.creditAmount)} icon={<IconCoin size={20} />} color="teal"
                hint={`${number(s.issued.count)} rewards · ${number(s.issued.codes)} welcome codes`} />
              <StatCard label="Pending (on hold)" value={money(s.pending.creditAmount)} icon={<IconHourglass size={20} />}
                color="orange" hint={`${number(s.pending.count)} rewards waiting for the hold to end`} />
              <StatCard label="Reversed" value={number(s.byStatus.REVERSED)} icon={<IconArrowBackUp size={20} />}
                color="grape" hint={`${number(s.reversed.count)} rewards cancelled · ${number(s.cappedReferrals)} over cap`} />
            </SimpleGrid>

            <SimpleGrid cols={{ base: 1, lg: 2 }}>
              <Paper withBorder p="md" radius="md">
                <Title order={5} mb="sm">Referrals per month</Title>
                <BarChart h={240} withLegend gridAxis="y" dataKey="month" yAxisProps={{ allowDecimals: false }}
                  data={s.monthly.map((m) => ({ month: dayjs(`${m.month}-01`).format('MMM YY'), referrals: m.referrals,
                    joined: m.conversions }))}
                  series={[{ name: 'referrals', color: 'blue.5', label: 'Referrals' },
                    { name: 'joined', color: 'teal.6', label: 'Joined' }]} />
              </Paper>
              <TopReferrers s={s} />
            </SimpleGrid>

            {settings.data && <Rules s={settings.data} />}
            <RecentReferrals branchId={branchId} />
          </Stack>
        )}
      </QueryState>
      <AttachModal opened={attaching} onClose={() => setAttaching(false)} branchId={branchId} />
    </>
  );
}

function TopReferrers({ s }: { s: ReferralStats }) {
  return (
    <Paper withBorder p="md" radius="md">
      <Title order={5} mb="sm">Top referrers</Title>
      {s.topReferrers.length === 0 ? (
        <Text c="dimmed" ta="center" py="lg">No referrals yet.</Text>
      ) : (
        <Table.ScrollContainer minWidth={420}>
          <Table verticalSpacing={6}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Member</Table.Th>
                <Table.Th>Code</Table.Th>
                <Table.Th ta="right">Referred</Table.Th>
                <Table.Th ta="right">Joined</Table.Th>
                <Table.Th ta="right">Credit earned</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {s.topReferrers.map((t) => (
                <Table.Tr key={t.memberId}>
                  <Table.Td>
                    <Anchor component={Link} to={`/members/${t.memberId}`} size="sm" fw={500}>{t.name}</Anchor>
                    <Text size="xs" c="dimmed">{t.memberCode}</Text>
                  </Table.Td>
                  <Table.Td><Text ff="monospace" size="sm">{t.code ?? '—'}</Text></Table.Td>
                  <Table.Td ta="right">{number(t.referrals)}</Table.Td>
                  <Table.Td ta="right">{number(t.conversions)}</Table.Td>
                  <Table.Td ta="right">{money(t.creditEarned)}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
    </Paper>
  );
}

function Rules({ s }: { s: ReferralSettings }) {
  return (
    <Alert color="blue" variant="light" title="Programme rules" icon={<IconGift size={18} />}>
      <List size="sm" spacing={2}>
        <List.Item>{s.referrerRewardText} Friends get {s.refereeRewardText} after their first membership.</List.Item>
        <List.Item>
          Rewards are held for {s.holdDays} days after the friend’s first paid membership; a refund or cancellation in
          that window reverses them.
        </List.Item>
        <List.Item>
          At most {s.monthlyCap} rewarded referrals per member per month. No self-referrals; the friend must be new
          (codes can still be added at the desk up to {s.attachGraceDays} days after their first purchase).
        </List.Item>
        <List.Item>Share links look like <Text span ff="monospace">/enquire?ref=CODE</Text>.</List.Item>
      </List>
    </Alert>
  );
}

function rewardBadge(status: Referral['referrerRewardStatus'], amount: number | null) {
  if (!status) return <Text size="sm" c="dimmed">—</Text>;
  return (
    <Group gap={6} wrap="nowrap">
      {amount != null && <Text size="sm">{money(amount)}</Text>}
      <StatusBadge value={status} color={REWARD_STATUS_COLOR[status]} size="sm" />
    </Group>
  );
}

function RecentReferrals({ branchId }: { branchId: number | null }) {
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const q = useApiQuery<Page<Referral>>('/api/v1/referrals', {
    branchId: branchId ?? undefined, status: status || undefined, page, size: 15,
  });
  const rows = q.data?.content ?? [];
  return (
    <Paper withBorder p="md" radius="md">
      <Group justify="space-between" mb="sm" wrap="wrap">
        <Title order={5}>Recent referrals</Title>
        <SegmentedControl size="xs" value={status} data={STATUS_FILTERS}
          onChange={(v) => { setStatus(v); setPage(0); }} />
      </Group>
      <QueryState isLoading={q.isLoading} error={q.error} empty={rows.length === 0} emptyText="No referrals match.">
        <Table.ScrollContainer minWidth={860}>
          <Table verticalSpacing="sm" highlightOnHover>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Referrer</Table.Th>
                <Table.Th>Friend</Table.Th>
                <Table.Th>Via</Table.Th>
                <Table.Th>Status</Table.Th>
                <Table.Th>Signed up</Table.Th>
                <Table.Th>Joined / hold</Table.Th>
                <Table.Th>Referrer reward</Table.Th>
                <Table.Th>Welcome reward</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((r) => (
                <Table.Tr key={r.id}>
                  <Table.Td>
                    <Anchor component={Link} to={`/members/${r.referrerMemberId}`} size="sm" fw={500}>{r.referrerName}</Anchor>
                    <Text size="xs" c="dimmed" ff="monospace">{r.code}</Text>
                  </Table.Td>
                  <Table.Td>
                    {r.refereeMemberId ? (
                      <Anchor component={Link} to={`/members/${r.refereeMemberId}`} size="sm">{r.refereeName}</Anchor>
                    ) : r.refereeLeadId ? (
                      <Anchor component={Link} to={`/leads/${r.refereeLeadId}`} size="sm">{r.refereeName}</Anchor>
                    ) : <Text size="sm">{r.refereeName}</Text>}
                    <Text size="xs" c="dimmed">{r.refereeMemberCode ?? (r.refereeLeadId ? 'Lead' : '')} · {r.branchName}</Text>
                  </Table.Td>
                  <Table.Td><Badge variant="outline" color="gray" radius="sm" size="sm">{
                    r.channel === 'LINK' ? 'Link' : r.channel === 'DESK' ? 'Desk' : 'Self sign-up'}</Badge></Table.Td>
                  <Table.Td>
                    <Badge variant="light" color={REFERRAL_STATUS_COLOR[r.status]} radius="sm">
                      {REFERRAL_STATUS_LABEL[r.status]}
                    </Badge>
                    {r.rejectionReason && <Text size="xs" c="dimmed">{REJECTION_LABEL[r.rejectionReason]}</Text>}
                    {r.reversalReason && <Text size="xs" c="dimmed">{r.reversalReason}</Text>}
                    {r.referrerRewardSkipped && <Text size="xs" c="dimmed">{SKIP_LABEL[r.referrerRewardSkipped]}</Text>}
                  </Table.Td>
                  <Table.Td>{date(r.createdAt)}</Table.Td>
                  <Table.Td>
                    {date(r.convertedAt)}
                    {r.status === 'CONVERTED' && r.holdUntil && dayjs(r.holdUntil).isAfter(dayjs()) && (
                      <Tooltip label="Rewards are released when the hold ends">
                        <Text size="xs" c="orange">hold until {date(r.holdUntil)}</Text>
                      </Tooltip>
                    )}
                  </Table.Td>
                  <Table.Td>{rewardBadge(r.referrerRewardStatus, r.referrerRewardAmount)}</Table.Td>
                  <Table.Td>{rewardBadge(r.refereeRewardStatus, null)}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
        <Pager page={q.data} onChange={setPage} />
      </QueryState>
    </Paper>
  );
}

/** Front desk: the new member (or open lead) mentions a friend's code. */
function AttachModal({ opened, onClose, branchId }: { opened: boolean; onClose: () => void; branchId: number | null }) {
  const [code, setCode] = useState('');
  const [target, setTarget] = useState<'member' | 'lead'>('member');
  const [memberId, setMemberId] = useState<number | null>(null);
  const [leadId, setLeadId] = useState<number | string>('');
  const reset = () => { setCode(''); setMemberId(null); setLeadId(''); onClose(); };
  const attach = useApiMutation<AttachReferralRequest, Referral>({
    request: (body) => ({ path: '/api/v1/referrals', body }),
    invalidate: ['/api/v1/referrals'],
    successMessage: (r) => (r.status === 'CONVERTED'
      ? `${r.refereeName} already joined - referral converted, rewards on hold`
      : `Referral recorded: ${r.referrerName} referred ${r.refereeName}`),
    onSuccess: reset,
  });
  const [debouncedCode] = useDebouncedValue(code.trim(), 400);
  const lookup = useApiQuery<{ referrerFirstName: string; offer: string }>(
    debouncedCode.length >= 5 ? `/api/v1/public/referrals/${encodeURIComponent(debouncedCode)}` : null,
    undefined, { retry: false },
  );
  const ready = code.trim().length >= 5 && (target === 'member' ? memberId != null : Number(leadId) > 0);
  return (
    <Modal opened={opened} onClose={reset} title="Add a referral" size="md">
      <Stack>
        <Text size="sm" c="dimmed">
          Use this when a new member or prospect gives a friend’s referral code at the desk, before or just after
          their first purchase.
        </Text>
        <TextInput label="Referral code" placeholder="e.g. ASHA7K2" value={code} maxLength={20} leftSection={<IconLink size={16} />}
          onChange={(e) => setCode(e.currentTarget.value.toUpperCase())}
          description={lookup.data ? `Referred by ${lookup.data.referrerFirstName}` : lookup.error ? 'Unknown code' : undefined}
          error={lookup.error && code.trim().length >= 5 ? 'No active member has this code' : undefined} />
        <SegmentedControl value={target} onChange={(v) => setTarget(v as 'member' | 'lead')}
          data={[{ value: 'member', label: 'New member' }, { value: 'lead', label: 'Open lead' }]} />
        {target === 'member' ? (
          <MemberSearch label="Member" value={memberId} onChange={(id) => setMemberId(id)} branchId={branchId}
            placeholder="Search name, phone or member code" />
        ) : (
          <NumberInput label="Lead #" value={leadId} onChange={setLeadId} min={1} allowDecimal={false}
            description="The lead id shown on the lead's page" />
        )}
        <Group justify="flex-end">
          <Button variant="default" onClick={reset}>Cancel</Button>
          <Button disabled={!ready} loading={attach.isPending} onClick={() => attach.mutate({
            code: code.trim(),
            memberId: target === 'member' ? memberId : null,
            leadId: target === 'lead' ? Number(leadId) : null,
          })}>Add referral</Button>
        </Group>
      </Stack>
    </Modal>
  );
}
