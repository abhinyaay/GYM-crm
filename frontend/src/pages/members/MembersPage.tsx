import { Anchor, Button, Group, Paper, Select, Table, Text, TextInput } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { IconSearch, IconUserPlus } from '@tabler/icons-react';
import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import type { MemberListItem, Page } from '@/api/types';
import { BranchSelect, useBranchNames } from '@/components/BranchSelect';
import { MemberFormModal } from '@/components/members/MemberFormModal';
import { PageHeader } from '@/components/PageHeader';
import { Pager } from '@/components/Pager';
import { QueryState } from '@/components/QueryState';
import { StatusBadge } from '@/components/StatusBadge';
import { date } from '@/lib/format';
import { useApiQuery } from '@/lib/useApi';

const MEMBERSHIP = ['ACTIVE', 'GRACE', 'FROZEN', 'SCHEDULED', 'PENDING_PAYMENT', 'NONE'];
const SORTS = [
  { value: 'registeredAt,desc', label: 'Newest first' },
  { value: 'registeredAt,asc', label: 'Oldest first' },
  { value: 'lastName,asc', label: 'Last name A-Z' },
  { value: 'memberCode,asc', label: 'Member code' },
];

export default function MembersPage() {
  const navigate = useNavigate();
  const branchNames = useBranchNames();
  const [q, setQ] = useState('');
  const [debouncedQ] = useDebouncedValue(q, 300);
  const [branchId, setBranchId] = useState<number | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [membership, setMembership] = useState<string | null>(null);
  const [sort, setSort] = useState('registeredAt,desc');
  const [page, setPage] = useState(0);
  const [registerOpen, setRegisterOpen] = useState(false);

  const members = useApiQuery<Page<MemberListItem>>('/api/v1/members', {
    q: debouncedQ, branchId: branchId ?? undefined, status: status ?? undefined, membership: membership ?? undefined,
    sort, page, size: 20,
  }, { placeholderData: (prev) => prev });

  const resetPage = <T,>(setter: (v: T) => void) => (v: T) => { setter(v); setPage(0); };

  return (
    <>
      <PageHeader title="Members" subtitle="Search, filter and manage members"
        actions={<Button leftSection={<IconUserPlus size={16} />} onClick={() => setRegisterOpen(true)}>Register member</Button>} />
      <Paper withBorder p="md">
        <Group mb="md" grow preventGrowOverflow={false} wrap="wrap">
          <TextInput leftSection={<IconSearch size={16} />} placeholder="Name, email, phone or member code" value={q}
            onChange={(e) => { setQ(e.currentTarget.value); setPage(0); }} miw={240} aria-label="Search members" />
          <BranchSelect allowAll value={branchId} onChange={resetPage(setBranchId)} aria-label="Branch" clearable />
          <Select placeholder="Account status" clearable data={['ACTIVE', 'SUSPENDED', 'INACTIVE']} value={status}
            onChange={resetPage(setStatus)} aria-label="Account status" />
          <Select placeholder="Membership" clearable value={membership} onChange={resetPage(setMembership)} aria-label="Membership"
            data={MEMBERSHIP.map((s) => ({ value: s, label: s === 'NONE' ? 'No membership' : s.charAt(0) + s.slice(1).toLowerCase().replace('_', ' ') }))} />
          <Select data={SORTS} value={sort} onChange={(v) => v && setSort(v)} allowDeselect={false} aria-label="Sort" />
        </Group>
        <QueryState isLoading={members.isLoading} error={members.error} empty={members.data?.content.length === 0}
          emptyText="No members match these filters.">
          <Table.ScrollContainer minWidth={800}>
            <Table highlightOnHover verticalSpacing="sm">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Member</Table.Th><Table.Th>Contact</Table.Th><Table.Th>Branch</Table.Th>
                  <Table.Th>Membership</Table.Th><Table.Th>Ends</Table.Th><Table.Th>Account</Table.Th><Table.Th>Joined</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {members.data?.content.map((m) => (
                  <Table.Tr key={m.id} className="clickable-row" onClick={() => navigate(`/members/${m.id}`)}>
                    <Table.Td>
                      <Anchor component={Link} to={`/members/${m.id}`} fw={600} onClick={(e) => e.stopPropagation()}>{m.fullName}</Anchor>
                      <Text size="xs" c="dimmed">{m.memberCode}</Text>
                    </Table.Td>
                    <Table.Td><Text size="sm">{m.email}</Text><Text size="xs" c="dimmed">{m.phone}</Text></Table.Td>
                    <Table.Td><Text size="sm">{branchNames[m.homeBranchId] ?? m.homeBranchId}</Text></Table.Td>
                    <Table.Td><StatusBadge value={m.membershipState} /></Table.Td>
                    <Table.Td><Text size="sm">{date(m.membershipEndDate)}</Text></Table.Td>
                    <Table.Td><StatusBadge value={m.status} /></Table.Td>
                    <Table.Td><Text size="sm">{date(m.registeredAt)}</Text></Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
          <Pager page={members.data} onChange={setPage} />
        </QueryState>
      </Paper>
      <MemberFormModal opened={registerOpen} onClose={() => setRegisterOpen(false)}
        onSaved={(m) => navigate(`/members/${m.id}`)} />
    </>
  );
}
