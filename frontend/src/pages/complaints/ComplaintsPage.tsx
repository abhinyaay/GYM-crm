import {
  Alert, Anchor, Button, Group, Modal, Paper, Select, SimpleGrid, Stack, Switch, Table, Text, Textarea, TextInput,
} from '@mantine/core';
import {
  IconAlertTriangle, IconArrowBigUpLines, IconInbox, IconLoader, IconPlus, IconX,
} from '@tabler/icons-react';
import { keepPreviousData } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import type { Complaint, ComplaintCategory, ComplaintPriority, Page } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { hasAnyRole, ORG_WIDE } from '@/auth/roles';
import { BranchSelect, useBranchNames } from '@/components/BranchSelect';
import { MemberSearch } from '@/components/MemberSearch';
import { PageHeader } from '@/components/PageHeader';
import { Pager } from '@/components/Pager';
import { QueryState } from '@/components/QueryState';
import { StatCard } from '@/components/StatCard';
import { StatusBadge } from '@/components/StatusBadge';
import { fromNow, label } from '@/lib/format';
import { errorMessage } from '@/lib/notify';
import { useApiMutation, useApiQuery } from '@/lib/useApi';
import { CATEGORIES, EscalationBadge, options, PRIORITIES, SlaBadge, STATUSES, useStaffDirectory } from './shared';

// Note: sorting by priority is not offered: the API sorts the enum alphabetically (CRITICAL, HIGH, LOW, MEDIUM).
const SORTS = [
  { value: 'slaDueAt,asc', label: 'SLA due (soonest)' },
  { value: 'createdAt,desc', label: 'Newest first' },
  { value: 'createdAt,asc', label: 'Oldest first' },
];

export default function ComplaintsPage() {
  const { user } = useAuth();
  const orgWide = hasAnyRole(user, ORG_WIDE);
  const navigate = useNavigate();
  const branchNames = useBranchNames();

  const [branchId, setBranchId] = useState<number | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [priority, setPriority] = useState<string | null>(null);
  const [category, setCategory] = useState<string | null>(null);
  const [mine, setMine] = useState(false);
  const [slaBreached, setSlaBreached] = useState(false);
  const [sort, setSort] = useState('slaDueAt,asc');
  const [page, setPage] = useState(0);
  const [creating, setCreating] = useState(false);

  // live SLA countdowns
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 30_000);
    return () => window.clearInterval(id);
  }, []);

  const scope = { branchId: branchId ?? undefined };
  const list = useApiQuery<Page<Complaint>>('/api/v1/complaints', {
    ...scope,
    status: status ?? undefined,
    priority: priority ?? undefined,
    category: category ?? undefined,
    assignedTo: mine ? user?.id : undefined,
    slaBreached: slaBreached || undefined,
    page,
    size: 20,
    sort,
  }, { placeholderData: keepPreviousData });
  // headline counters (size=1: only totalElements is used)
  const openCount = useApiQuery<Page<Complaint>>('/api/v1/complaints', { ...scope, status: 'OPEN', size: 1 });
  const progressCount = useApiQuery<Page<Complaint>>('/api/v1/complaints', { ...scope, status: 'IN_PROGRESS', size: 1 });
  const escalatedCount = useApiQuery<Page<Complaint>>('/api/v1/complaints', { ...scope, status: 'ESCALATED', size: 1 });
  const breachedCount = useApiQuery<Page<Complaint>>('/api/v1/complaints', { ...scope, slaBreached: true, size: 1 });

  const rows = list.data?.content ?? [];
  const staff = useStaffDirectory(user, rows.map((c) => c.branchId));
  const filtersActive = status || priority || category || mine || slaBreached;

  const reset = <T,>(setter: (v: T) => void) => (v: T) => { setter(v); setPage(0); };
  const count = (q: { data?: Page<Complaint> }) => (q.data ? q.data.totalElements : '…');

  return (
    <>
      <PageHeader
        title="Complaints"
        subtitle="Member support tickets with SLA tracking and escalation"
        actions={<Button leftSection={<IconPlus size={16} />} onClick={() => setCreating(true)}>New complaint</Button>}
      />

      <SimpleGrid cols={{ base: 2, md: 4 }} mb="md">
        <StatCard label="Open" value={count(openCount)} icon={<IconInbox size={20} />} color="blue" />
        <StatCard label="In progress" value={count(progressCount)} icon={<IconLoader size={20} />} color="indigo" />
        <StatCard label="Escalated" value={count(escalatedCount)} icon={<IconArrowBigUpLines size={20} />} color="orange" />
        <StatCard label="SLA breached" value={count(breachedCount)} icon={<IconAlertTriangle size={20} />} color="red"
          hint="unresolved and past due" />
      </SimpleGrid>

      <Paper withBorder p="sm" mb="md">
        <Group gap="sm" align="flex-end" wrap="wrap">
          <BranchSelect allowAll value={branchId} onChange={reset(setBranchId)} label="Branch" w={{ base: '100%', sm: 220 }}
            clearable={!orgWide} placeholder={orgWide ? 'Select branch' : 'All my branches'} />
          <Select label="Status" placeholder="Any status" clearable data={options(STATUSES)} value={status} onChange={reset(setStatus)} w={150} />
          <Select label="Priority" placeholder="Any priority" clearable data={options(PRIORITIES)} value={priority} onChange={reset(setPriority)} w={140} />
          <Select label="Category" placeholder="Any category" clearable data={options(CATEGORIES)} value={category} onChange={reset(setCategory)} w={150} />
          <Select label="Sort" data={SORTS} value={sort} onChange={(v) => v && reset(setSort)(v)} allowDeselect={false} w={180} />
          <Stack gap={6} pb={4}>
            <Switch label="Assigned to me" checked={mine} onChange={(e) => reset(setMine)(e.currentTarget.checked)} />
            <Switch label="SLA breached only" color="red" checked={slaBreached} onChange={(e) => reset(setSlaBreached)(e.currentTarget.checked)} />
          </Stack>
          {filtersActive && (
            <Button variant="subtle" color="gray" size="xs" leftSection={<IconX size={14} />} mb={4}
              onClick={() => { setStatus(null); setPriority(null); setCategory(null); setMine(false); setSlaBreached(false); setPage(0); }}>
              Clear filters
            </Button>
          )}
        </Group>
      </Paper>

      <Paper withBorder>
        <QueryState isLoading={list.isLoading} error={list.error} empty={rows.length === 0}
          emptyText={filtersActive ? 'No complaints match these filters.' : 'No complaints yet.'}>
          <Table.ScrollContainer minWidth={1100}>
            <Table verticalSpacing="sm" highlightOnHover style={{ opacity: list.isPlaceholderData ? 0.6 : 1 }}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Ticket</Table.Th>
                  <Table.Th>Branch</Table.Th>
                  <Table.Th>Category</Table.Th>
                  <Table.Th>Priority</Table.Th>
                  <Table.Th>Status</Table.Th>
                  <Table.Th>Esc.</Table.Th>
                  <Table.Th>SLA</Table.Th>
                  <Table.Th>Assignee</Table.Th>
                  <Table.Th>Raised</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {rows.map((c) => (
                  <Table.Tr key={c.id} style={{ cursor: 'pointer' }} onClick={() => navigate(`/complaints/${c.id}`)}>
                    <Table.Td maw={320}>
                      <Anchor component={Link} to={`/complaints/${c.id}`} size="sm" fw={600} onClick={(e) => e.stopPropagation()}>
                        {c.ticketNumber}
                      </Anchor>
                      <Text size="sm" lineClamp={1}>{c.subject}</Text>
                      {c.memberId != null && (
                        <Anchor component={Link} to={`/members/${c.memberId}`} size="xs" c="dimmed" onClick={(e) => e.stopPropagation()}>
                          Member #{c.memberId}
                        </Anchor>
                      )}
                    </Table.Td>
                    <Table.Td><Text size="sm">{branchNames[c.branchId] ?? `Branch ${c.branchId}`}</Text></Table.Td>
                    <Table.Td><Text size="sm">{label(c.category)}</Text></Table.Td>
                    <Table.Td><StatusBadge value={c.priority} /></Table.Td>
                    <Table.Td><StatusBadge value={c.status} /></Table.Td>
                    <Table.Td><EscalationBadge level={c.escalationLevel} /></Table.Td>
                    <Table.Td><SlaBadge complaint={c} now={now} /></Table.Td>
                    <Table.Td>
                      <Text size="sm" c={c.assignedTo == null ? 'dimmed' : undefined}>{c.assignedToName ?? staff.nameOf(c.assignedTo)}</Text>
                    </Table.Td>
                    <Table.Td><Text size="sm" style={{ whiteSpace: 'nowrap' }}>{fromNow(c.createdAt)}</Text></Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
        </QueryState>
      </Paper>
      <Pager page={list.data} onChange={setPage} />

      <NewComplaintModal opened={creating} onClose={() => setCreating(false)} defaultBranchId={branchId ?? (user?.branchIds.length === 1 ? user.branchIds[0] : null)}
        onCreated={(c) => navigate(`/complaints/${c.id}`)} />
    </>
  );
}

function NewComplaintModal({ opened, onClose, defaultBranchId, onCreated }: {
  opened: boolean;
  onClose: () => void;
  defaultBranchId: number | null;
  onCreated: (c: Complaint) => void;
}) {
  const [memberId, setMemberId] = useState<number | null>(null);
  const [branchId, setBranchId] = useState<number | null>(defaultBranchId);
  const [category, setCategory] = useState<ComplaintCategory | null>(null);
  const [priority, setPriority] = useState<ComplaintPriority>('MEDIUM');
  const [subject, setSubject] = useState('');
  const [description, setDescription] = useState('');

  useEffect(() => {
    if (opened) {
      setMemberId(null); setBranchId(defaultBranchId); setCategory(null); setPriority('MEDIUM'); setSubject(''); setDescription('');
    }
  }, [opened]); // eslint-disable-line react-hooks/exhaustive-deps

  const create = useApiMutation<void, Complaint>({
    request: () => ({
      path: '/api/v1/complaints',
      body: { memberId: memberId ?? undefined, branchId, category, priority, subject: subject.trim(), description: description.trim() },
    }),
    invalidate: ['/api/v1/complaints', '/api/v1/dashboard'],
    successMessage: (c) => `Complaint ${c.ticketNumber} created`,
    silentError: true,
    onSuccess: (c) => { onClose(); onCreated(c); },
  });

  const valid = branchId != null && category != null && subject.trim() !== '' && description.trim() !== '';
  return (
    <Modal opened={opened} onClose={onClose} title="New complaint" size="lg" centered>
      <Stack>
        <MemberSearch label="Member (optional)" description="Leave empty for an operational issue. Note: tickets filed by staff do not appear in the member's own Support page."
          value={memberId} branchId={null}
          onChange={(id, m) => { setMemberId(id); if (m) setBranchId(m.homeBranchId); }} />
        <BranchSelect label="Branch" required value={branchId} onChange={setBranchId} />
        <Group grow>
          <Select label="Category" required placeholder="Pick one" data={options(CATEGORIES)} value={category}
            onChange={(v) => setCategory(v as ComplaintCategory | null)} />
          <Select label="Priority" data={options(PRIORITIES)} value={priority} allowDeselect={false}
            onChange={(v) => v && setPriority(v as ComplaintPriority)}
            description="Sets the SLA window" />
        </Group>
        <TextInput label="Subject" required maxLength={200} value={subject} onChange={(e) => setSubject(e.currentTarget.value)} />
        <Textarea label="Description" required autosize minRows={4} maxRows={10} maxLength={4000}
          value={description} onChange={(e) => setDescription(e.currentTarget.value)} />
        {create.error && <Alert color="red">{errorMessage(create.error)}</Alert>}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>Cancel</Button>
          <Button onClick={() => create.mutate()} loading={create.isPending} disabled={!valid}>Create complaint</Button>
        </Group>
      </Stack>
    </Modal>
  );
}
