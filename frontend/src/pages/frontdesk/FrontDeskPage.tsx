import {
  Alert, Anchor, Badge, Button, Group, Paper, Progress, SimpleGrid, Stack, Table, Text, TextInput, Title,
} from '@mantine/core';
import { IconDoorEnter, IconDoorExit, IconSearch, IconUserPlus } from '@tabler/icons-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError, api } from '@/api/client';
import type { DailySummary, Member, Occupancy, Page, Visit } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { BranchSelect } from '@/components/BranchSelect';
import { MemberFormModal } from '@/components/members/MemberFormModal';
import { MemberSearch } from '@/components/MemberSearch';
import { PageHeader } from '@/components/PageHeader';
import { QueryState } from '@/components/QueryState';
import { StatCard } from '@/components/StatCard';
import { StatusBadge } from '@/components/StatusBadge';
import { date, time } from '@/lib/format';
import { errorMessage } from '@/lib/notify';
import { useApiMutation, useApiQuery } from '@/lib/useApi';
import { Pager } from '@/components/Pager';

export default function FrontDeskPage() {
  const { user } = useAuth();
  const [branchId, setBranchId] = useState<number | null>(user?.branchIds[0] ?? null);
  const [memberId, setMemberId] = useState<number | null>(null);
  const [code, setCode] = useState('');
  const [lookupError, setLookupError] = useState<string | null>(null);
  const [registerOpen, setRegisterOpen] = useState(false);
  const [visitsPage, setVisitsPage] = useState(0);

  // org-wide staff have no assigned branch: default to the first visible one
  const branches = useApiQuery<Page<{ id: number }>>(branchId ? null : '/api/v1/branches', { size: 1 });
  const effectiveBranch = branchId ?? branches.data?.content[0]?.id ?? null;

  const member = useApiQuery<Member>(memberId ? `/api/v1/members/${memberId}` : null);
  const occupancy = useApiQuery<Occupancy>(effectiveBranch ? `/api/v1/attendance/branches/${effectiveBranch}/occupancy` : null,
    undefined, { refetchInterval: 30_000 });
  const summary = useApiQuery<DailySummary>(effectiveBranch ? `/api/v1/attendance/branches/${effectiveBranch}/daily-summary` : null);
  const visits = useApiQuery<Page<Visit>>(effectiveBranch ? `/api/v1/attendance/branches/${effectiveBranch}` : null,
    { page: visitsPage, size: 15 });

  const invalidate = ['/api/v1/attendance', '/api/v1/members', '/api/v1/dashboard'];
  const checkIn = useApiMutation<number, Visit>({
    request: (id) => ({ path: '/api/v1/attendance/check-in', body: { memberId: id, branchId: effectiveBranch, method: 'FRONT_DESK' } }),
    invalidate,
    successMessage: (v) => `${v.memberName} checked in at ${time(v.checkInTime)}`,
    silentError: true,
  });
  const checkOut = useApiMutation<number, Visit>({
    request: (id) => ({ path: '/api/v1/attendance/check-out', body: { memberId: id } }),
    invalidate,
    successMessage: (v) => `${v.memberName} checked out after ${v.durationMinutes} min`,
  });

  async function lookupByCode() {
    setLookupError(null);
    checkIn.reset();
    try {
      const m = await api<Member>(`/api/v1/members/code/${encodeURIComponent(code.trim())}`);
      setMemberId(m.id);
    } catch (err) {
      setLookupError(err instanceof ApiError && err.status === 404 ? `No member with code ${code}` : errorMessage(err));
    }
  }

  const m = member.data;
  const insideNow = (visits.data?.content ?? []).find((v) => v.memberId === m?.id && !v.checkOutTime);
  const canEnter = m && ['ACTIVE', 'GRACE'].includes(m.membership.state) && m.status === 'ACTIVE';

  return (
    <>
      <PageHeader
        title="Front desk"
        subtitle={summary.data ? `${date(summary.data.date)} · branch local time` : undefined}
        actions={
          <>
            <BranchSelect value={effectiveBranch} onChange={setBranchId} w={260} aria-label="Branch" />
            <Button leftSection={<IconUserPlus size={16} />} onClick={() => setRegisterOpen(true)}>Register member</Button>
          </>
        }
      />
      <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} mb="md">
        <Paper withBorder p="md">
          <Text size="xs" c="dimmed" tt="uppercase" fw={600}>Inside now</Text>
          <Text fw={700} fz={26}>{occupancy.data?.currentlyInside ?? '—'} <Text span c="dimmed" fz="md">/ {occupancy.data?.capacity ?? '—'}</Text></Text>
          <Progress mt="xs" value={(occupancy.data?.utilisation ?? 0) * 100}
            color={(occupancy.data?.utilisation ?? 0) > 0.85 ? 'red' : 'teal'} />
        </Paper>
        <StatCard label="Check-ins today" value={summary.data?.checkIns ?? '—'} />
        <StatCard label="Unique members today" value={summary.data?.uniqueMembers ?? '—'} />
        <StatCard label="Average visit" value={summary.data ? `${summary.data.averageVisitMinutes} min` : '—'} />
      </SimpleGrid>

      <SimpleGrid cols={{ base: 1, lg: 2 }} mb="md">
        <Paper withBorder p="md">
          <Title order={4} mb="sm">Member lookup</Title>
          <Stack>
            <Group align="flex-end" wrap="nowrap">
              <TextInput label="Member code (card / QR scan)" placeholder="M001234" value={code} style={{ flex: 1 }}
                onChange={(e) => setCode(e.currentTarget.value.toUpperCase())}
                onKeyDown={(e) => e.key === 'Enter' && code.trim() && lookupByCode()} />
              <Button variant="light" leftSection={<IconSearch size={16} />} onClick={lookupByCode} disabled={!code.trim()}>Find</Button>
            </Group>
            <MemberSearch label="…or search by name, email or phone" value={memberId}
              onChange={(id) => { setMemberId(id); checkIn.reset(); setLookupError(null); }} />
            {lookupError && <Alert color="red">{lookupError}</Alert>}
          </Stack>
        </Paper>

        <Paper withBorder p="md">
          {!memberId ? (
            <Stack align="center" justify="center" h="100%" gap={4}>
              <IconDoorEnter size={40} color="var(--mantine-color-gray-5)" />
              <Text c="dimmed">Look up a member to check them in.</Text>
            </Stack>
          ) : (
            <QueryState isLoading={member.isLoading} error={member.error}>
              {m && (
                <Stack gap="xs">
                  <Group justify="space-between">
                    <div>
                      <Anchor component={Link} to={`/members/${m.id}`} fw={700} fz="lg">{m.firstName} {m.lastName}</Anchor>
                      <Text size="sm" c="dimmed">{m.memberCode} · {m.homeBranchName}</Text>
                    </div>
                    <Group gap={6}>
                      {m.status !== 'ACTIVE' && <StatusBadge value={m.status} />}
                      <StatusBadge value={m.membership.state} size="lg" />
                    </Group>
                  </Group>
                  <Text size="sm">
                    {m.membership.planName
                      ? <>{m.membership.planName} · until {date(m.membership.endDate)}{m.membership.daysRemaining != null && ` (${m.membership.daysRemaining} days left)`}</>
                      : 'No current membership'}
                  </Text>
                  {m.membership.state === 'GRACE' && <Badge color="orange" variant="light">In grace period - remind them to renew</Badge>}
                  {checkIn.error && <Alert color="red" title="Entry refused">{errorMessage(checkIn.error)}</Alert>}
                  <Group mt="xs">
                    {insideNow ? (
                      <Button color="orange" leftSection={<IconDoorExit size={16} />} loading={checkOut.isPending}
                        onClick={() => checkOut.mutate(m.id)}>Check out</Button>
                    ) : (
                      <Button size="md" leftSection={<IconDoorEnter size={18} />} loading={checkIn.isPending}
                        disabled={!effectiveBranch} color={canEnter ? 'teal' : 'gray'}
                        onClick={() => checkIn.mutate(m.id)}>Check in</Button>
                    )}
                    <Button variant="default" component={Link} to={`/members/${m.id}`}>
                      {m.membership.state === 'NONE' || m.membership.state === 'GRACE' ? 'Sell / renew membership' : 'Open profile'}
                    </Button>
                  </Group>
                </Stack>
              )}
            </QueryState>
          )}
        </Paper>
      </SimpleGrid>

      <Paper withBorder p="md">
        <Title order={4} mb="sm">Today's visits</Title>
        <QueryState isLoading={visits.isLoading} error={visits.error} empty={visits.data?.content.length === 0}
          emptyText="No check-ins yet today.">
          <Table.ScrollContainer minWidth={600}>
            <Table verticalSpacing="xs" highlightOnHover>
              <Table.Thead>
                <Table.Tr><Table.Th>Member</Table.Th><Table.Th>In</Table.Th><Table.Th>Out</Table.Th><Table.Th>Method</Table.Th><Table.Th /></Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {visits.data?.content.map((v) => (
                  <Table.Tr key={v.id}>
                    <Table.Td>
                      <Anchor component={Link} to={`/members/${v.memberId}`} size="sm">{v.memberName}</Anchor>
                      <Text size="xs" c="dimmed">{v.memberCode}</Text>
                    </Table.Td>
                    <Table.Td>{time(v.checkInTime)}</Table.Td>
                    <Table.Td>{v.checkOutTime ? `${time(v.checkOutTime)} (${v.durationMinutes} min)${v.autoCheckedOut ? ' · auto' : ''}` : <Badge color="teal" variant="dot">inside</Badge>}</Table.Td>
                    <Table.Td><Text size="sm">{v.method.replace('_', ' ').toLowerCase()}</Text></Table.Td>
                    <Table.Td>
                      {!v.checkOutTime && (
                        <Button size="xs" variant="light" color="orange" onClick={() => checkOut.mutate(v.memberId)}>Check out</Button>
                      )}
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
          <Pager page={visits.data} onChange={setVisitsPage} />
        </QueryState>
      </Paper>

      <MemberFormModal opened={registerOpen} onClose={() => setRegisterOpen(false)} defaultBranchId={effectiveBranch}
        onSaved={(saved) => setMemberId(saved.id)} />
    </>
  );
}
