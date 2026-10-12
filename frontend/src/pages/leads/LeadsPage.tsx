import {
  ActionIcon, Anchor, Badge, Button, Card, Group, Menu, Paper, ScrollArea, SegmentedControl, Select, SimpleGrid, Stack,
  Switch, Table, Text, TextInput, Title, Tooltip,
} from '@mantine/core';
import { DatePickerInput } from '@mantine/dates';
import {
  IconAlarm, IconCheck, IconDots, IconLayoutKanban, IconPhone, IconPlus, IconSearch, IconTable, IconTargetArrow,
  IconTrophy, IconUserPlus, IconX,
} from '@tabler/icons-react';
import { keepPreviousData } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  LEAD_SOURCES, LEAD_STATUS_COLOR, LEAD_STATUSES, type FollowUpItem, type Lead, type LeadActivity, type LeadStatus,
  type PipelineSummary,
} from '@/api/leads';
import type { Page } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { hasAnyRole, ORG_WIDE } from '@/auth/roles';
import { BranchSelect } from '@/components/BranchSelect';
import { PageHeader } from '@/components/PageHeader';
import { Pager } from '@/components/Pager';
import { QueryState } from '@/components/QueryState';
import { StatCard } from '@/components/StatCard';
import { dateTime, fromNow, label, number } from '@/lib/format';
import { useApiMutation, useApiQuery } from '@/lib/useApi';
import {
  LeadFormModal, LeadStatusBadge, LostModal, MANUAL_MOVES, moveLabel, options, useLeadStatusChange,
} from './components';

const SORTS = [
  { value: 'createdAt,desc', label: 'Newest first' },
  { value: 'createdAt,asc', label: 'Oldest first' },
  { value: 'nextFollowUpAt,asc', label: 'Next follow-up' },
  { value: 'updatedAt,desc', label: 'Recently updated' },
  { value: 'firstName,asc', label: 'Name A–Z' },
];
const BOARD_PAGE = 15;

type View = 'board' | 'table';

export default function LeadsPage() {
  const { user } = useAuth();
  const orgWide = hasAnyRole(user, ORG_WIDE);
  const [branchId, setBranchId] = useState<number | null>(null);
  const [view, setView] = useState<View>('board');
  const [creating, setCreating] = useState(false);
  const [losing, setLosing] = useState<Lead | null>(null);
  const navigate = useNavigate();
  const scope = { branchId: branchId ?? undefined };
  const summary = useApiQuery<PipelineSummary>('/api/v1/leads/summary', scope);
  const s = summary.data;

  return (
    <>
      <PageHeader
        title="Leads & trials"
        subtitle="Sales pipeline: enquiries, follow-ups, free trials and conversions"
        actions={
          <>
            <BranchSelect allowAll value={branchId} onChange={setBranchId} w={{ base: '100%', sm: 240 }}
              clearable={!orgWide} placeholder={orgWide ? 'Select branch' : 'All my branches'} />
            <Button leftSection={<IconPlus size={16} />} onClick={() => setCreating(true)}>New lead</Button>
          </>
        }
      />

      <SimpleGrid cols={{ base: 2, md: 4 }} mb="md">
        <StatCard label="Open leads" value={s ? number(s.open) : '…'} icon={<IconTargetArrow size={20} />} color="blue"
          hint={s ? `${number(s.newThisMonth)} new this month` : undefined} />
        <StatCard label="Follow-ups due today" value={s ? number(s.followUpsDueToday + s.followUpsOverdue) : '…'}
          icon={<IconAlarm size={20} />} color={s && s.followUpsOverdue > 0 ? 'red' : 'orange'}
          hint={s ? `${number(s.followUpsOverdue)} overdue · ${number(s.myFollowUpsDue)} mine` : undefined} />
        <StatCard label="Active trials" value={s ? number(s.byStatus.TRIAL_ACTIVE) : '…'} icon={<IconUserPlus size={20} />}
          color="orange" hint={s ? `${number(s.trialsEndingSoon)} ending within 2 days` : undefined} />
        <StatCard label="Conversions this month" value={s ? number(s.conversionsThisMonth) : '…'}
          icon={<IconTrophy size={20} />} color="teal" />
      </SimpleGrid>

      <MyFollowUps branchId={branchId} />

      <Group justify="space-between" mb="sm" mt="lg">
        <Title order={4}>Pipeline</Title>
        <SegmentedControl value={view} onChange={(v) => setView(v as View)} data={[
          { value: 'board', label: <Group gap={6} wrap="nowrap"><IconLayoutKanban size={16} />Board</Group> },
          { value: 'table', label: <Group gap={6} wrap="nowrap"><IconTable size={16} />Table</Group> },
        ]} />
      </Group>

      {view === 'board'
        ? <Board branchId={branchId} counts={s?.byStatus} onLose={setLosing} />
        : <LeadTable branchId={branchId} />}

      <LeadFormModal opened={creating} onClose={() => setCreating(false)}
        defaultBranchId={branchId ?? (user?.branchIds.length === 1 ? user.branchIds[0] : null)}
        onSaved={(l) => navigate(`/leads/${l.id}`)} />
      <LostModal lead={losing} onClose={() => setLosing(null)} />
    </>
  );
}

// ------------------------------------------------------------------ follow-ups panel

function MyFollowUps({ branchId }: { branchId: number | null }) {
  const [mine, setMine] = useState(true);
  const q = useApiQuery<FollowUpItem[]>('/api/v1/leads/follow-ups', { mine, branchId: branchId ?? undefined });
  const complete = useApiMutation<FollowUpItem, LeadActivity>({
    request: (f) => ({ path: `/api/v1/leads/${f.leadId}/follow-ups/${f.activityId}/complete`, body: {} }),
    invalidate: ['/api/v1/leads'],
    successMessage: 'Follow-up done',
  });
  const items = q.data ?? [];
  const overdue = items.filter((f) => f.overdue).length;
  return (
    <Paper withBorder p="md">
      <Group justify="space-between" mb="sm">
        <Group gap="xs">
          <Title order={5}>{mine ? 'My follow-ups' : 'Branch follow-ups'}</Title>
          <Badge variant="light" color="orange">{items.length - overdue} due today</Badge>
          {overdue > 0 && <Badge variant="filled" color="red">{overdue} overdue</Badge>}
        </Group>
        <Switch label="Only mine" checked={mine} onChange={(e) => setMine(e.currentTarget.checked)} />
      </Group>
      <QueryState isLoading={q.isLoading} error={q.error} empty={items.length === 0}
        emptyText="Nothing due today. Nice work!">
        <ScrollArea.Autosize mah={260}>
          <Stack gap={6}>
            {items.map((f) => (
              <Group key={f.activityId} justify="space-between" wrap="nowrap" gap="sm"
                p={8} style={{ borderRadius: 6, background: f.overdue ? 'var(--mantine-color-red-light)' : 'var(--mantine-color-default-hover)' }}>
                <Stack gap={0} style={{ minWidth: 0 }}>
                  <Group gap={6} wrap="nowrap">
                    <Anchor component={Link} to={`/leads/${f.leadId}`} fw={600} size="sm">{f.leadName}</Anchor>
                    <LeadStatusBadge status={f.leadStatus} size="xs" />
                  </Group>
                  <Text size="sm" lineClamp={1}>{f.body}</Text>
                  <Text size="xs" c={f.overdue ? 'red' : 'dimmed'}>
                    {f.overdue ? 'Overdue · ' : 'Due '}{dateTime(f.dueAt)}{!mine && f.ownerName ? ` · ${f.ownerName}` : ''}
                  </Text>
                </Stack>
                <Group gap={4} wrap="nowrap">
                  <Tooltip label={`Call ${f.phone}`}>
                    <ActionIcon component="a" href={`tel:${f.phone}`} variant="light" aria-label="Call">
                      <IconPhone size={16} />
                    </ActionIcon>
                  </Tooltip>
                  <Tooltip label="Mark done">
                    <ActionIcon variant="light" color="teal" aria-label="Mark done" loading={complete.isPending && complete.variables?.activityId === f.activityId}
                      onClick={() => complete.mutate(f)}>
                      <IconCheck size={16} />
                    </ActionIcon>
                  </Tooltip>
                </Group>
              </Group>
            ))}
          </Stack>
        </ScrollArea.Autosize>
      </QueryState>
    </Paper>
  );
}

// ------------------------------------------------------------------ board

function Board({ branchId, counts, onLose }: {
  branchId: number | null;
  counts: Record<LeadStatus, number> | undefined;
  onLose: (lead: Lead) => void;
}) {
  return (
    <ScrollArea type="auto" offsetScrollbars>
      <Group align="flex-start" wrap="nowrap" gap="sm" pb="sm">
        {LEAD_STATUSES.map((status) => (
          <BoardColumn key={status} status={status} branchId={branchId} count={counts?.[status]} onLose={onLose} />
        ))}
      </Group>
    </ScrollArea>
  );
}

function BoardColumn({ status, branchId, count, onLose }: {
  status: LeadStatus;
  branchId: number | null;
  count: number | undefined;
  onLose: (lead: Lead) => void;
}) {
  const closed = status === 'CONVERTED' || status === 'LOST';
  const q = useApiQuery<Page<Lead>>('/api/v1/leads', {
    branchId: branchId ?? undefined, status, size: BOARD_PAGE,
    sort: closed ? 'updatedAt,desc' : 'createdAt,desc',
  });
  const leads = q.data?.content ?? [];
  const hidden = (q.data?.totalElements ?? 0) - leads.length;
  return (
    <Paper withBorder p="xs" w={260} miw={260} bg="var(--mantine-color-default-hover)">
      <Group justify="space-between" mb="xs" px={4}>
        <Group gap={6}>
          <span style={{ width: 10, height: 10, borderRadius: 5, background: `var(--mantine-color-${LEAD_STATUS_COLOR[status]}-6)` }} />
          <Text fw={600} size="sm">{label(status)}</Text>
        </Group>
        <Badge variant="light" color={LEAD_STATUS_COLOR[status]}>{count ?? q.data?.totalElements ?? '…'}</Badge>
      </Group>
      <QueryState isLoading={q.isLoading} error={q.error} empty={leads.length === 0} emptyText="No leads">
        <Stack gap={6}>
          {leads.map((l) => <LeadCard key={l.id} lead={l} onLose={onLose} />)}
          {hidden > 0 && <Text size="xs" c="dimmed" ta="center">+{hidden} more — use the table view</Text>}
        </Stack>
      </QueryState>
    </Paper>
  );
}

function LeadCard({ lead, onLose }: { lead: Lead; onLose: (lead: Lead) => void }) {
  const navigate = useNavigate();
  const change = useLeadStatusChange();
  const moves = MANUAL_MOVES[lead.status];
  const canTrial = ['NEW', 'CONTACTED', 'TOUR_SCHEDULED'].includes(lead.status);
  return (
    <Card withBorder padding="xs" radius="md" style={{ cursor: 'pointer' }} onClick={() => navigate(`/leads/${lead.id}`)}>
      <Group justify="space-between" wrap="nowrap" align="flex-start" gap={4}>
        <Stack gap={0} style={{ minWidth: 0 }}>
          <Text fw={600} size="sm" truncate>{lead.fullName}</Text>
          <Text size="xs" c="dimmed">{lead.phone}</Text>
        </Stack>
        {(moves.length > 0 || canTrial) && (
          <Menu position="bottom-end" withinPortal>
            <Menu.Target>
              <ActionIcon variant="subtle" color="gray" size="sm" aria-label="Lead actions" onClick={(e) => e.stopPropagation()}>
                <IconDots size={16} />
              </ActionIcon>
            </Menu.Target>
            <Menu.Dropdown onClick={(e) => e.stopPropagation()}>
              {moves.map((target) => (
                <Menu.Item key={target} color={target === 'LOST' ? 'red' : undefined}
                  onClick={() => (target === 'LOST' ? onLose(lead) : change.mutate({ id: lead.id, status: target }))}>
                  {moveLabel(target)}
                </Menu.Item>
              ))}
              {canTrial && <Menu.Item onClick={() => navigate(`/leads/${lead.id}?trial=1`)}>Start trial…</Menu.Item>}
            </Menu.Dropdown>
          </Menu>
        )}
      </Group>
      <Group gap={4} mt={6}>
        <Badge size="xs" variant="outline" color="gray">{label(lead.source)}</Badge>
        {lead.interest !== 'MEMBERSHIP' && <Badge size="xs" variant="outline" color="grape">{label(lead.interest)}</Badge>}
      </Group>
      <Text size="xs" c="dimmed" mt={4}>{lead.ownerName ?? 'Unassigned'} · {fromNow(lead.createdAt)}</Text>
      {lead.status === 'TRIAL_ACTIVE' && lead.trialEndsOn && (
        <Text size="xs" c="orange" mt={2}>Trial ends {dayjs(lead.trialEndsOn).format('DD MMM')}</Text>
      )}
      {lead.status === 'LOST' && lead.lostReason && <Text size="xs" c="dimmed" mt={2}>Lost: {label(lead.lostReason)}</Text>}
      {lead.nextFollowUpAt && (
        <Text size="xs" mt={2} c={lead.followUpOverdue ? 'red' : 'blue'}>
          <IconAlarm size={12} style={{ verticalAlign: -2 }} /> {lead.followUpOverdue ? 'Overdue ' : ''}{dateTime(lead.nextFollowUpAt)}
        </Text>
      )}
    </Card>
  );
}

// ------------------------------------------------------------------ table

function LeadTable({ branchId }: { branchId: number | null }) {
  const { user } = useAuth();
  const navigate = useNavigate();
  const [status, setStatus] = useState<string | null>(null);
  const [source, setSource] = useState<string | null>(null);
  const [owner, setOwner] = useState<string | null>(null);
  const [q, setQ] = useState('');
  const [followUpDue, setFollowUpDue] = useState(false);
  const [range, setRange] = useState<[Date | null, Date | null]>([null, null]);
  const [sort, setSort] = useState('createdAt,desc');
  const [page, setPage] = useState(0);
  const reset = <T,>(setter: (v: T) => void) => (v: T) => { setter(v); setPage(0); };

  const list = useApiQuery<Page<Lead>>('/api/v1/leads', {
    branchId: branchId ?? undefined,
    status: status ?? undefined,
    source: source ?? undefined,
    ownerUserId: owner === 'me' ? user?.id : undefined,
    unassigned: owner === 'none' || undefined,
    q: q.trim() || undefined,
    followUpDue: followUpDue || undefined,
    createdFrom: range[0] ? dayjs(range[0]).format('YYYY-MM-DD') : undefined,
    createdTo: range[1] ? dayjs(range[1]).format('YYYY-MM-DD') : undefined,
    sort, page, size: 20,
  }, { placeholderData: keepPreviousData });
  const rows = list.data?.content ?? [];
  const filtersActive = status || source || owner || q || followUpDue || range[0];

  return (
    <>
      <Paper withBorder p="sm" mb="md">
        <Group gap="sm" align="flex-end" wrap="wrap">
          <TextInput label="Search" placeholder="Name, phone or email" leftSection={<IconSearch size={14} />} value={q}
            onChange={(e) => reset(setQ)(e.currentTarget.value)} w={{ base: '100%', sm: 220 }} />
          <Select label="Status" placeholder="Any status" clearable data={options(LEAD_STATUSES)} value={status}
            onChange={reset(setStatus)} w={160} />
          <Select label="Source" placeholder="Any source" clearable data={options(LEAD_SOURCES)} value={source}
            onChange={reset(setSource)} w={150} />
          <Select label="Owner" placeholder="Anyone" clearable value={owner} onChange={reset(setOwner)} w={140}
            data={[{ value: 'me', label: 'Me' }, { value: 'none', label: 'Unassigned' }]} />
          <DatePickerInput type="range" label="Created" placeholder="Any time" clearable value={range}
            onChange={reset(setRange)} maxDate={new Date()} w={230} />
          <Select label="Sort" data={SORTS} value={sort} onChange={(v) => v && reset(setSort)(v)} allowDeselect={false} w={170} />
          <Switch label="Follow-up due" mb={8} checked={followUpDue} onChange={(e) => reset(setFollowUpDue)(e.currentTarget.checked)} />
          {filtersActive && (
            <Button variant="subtle" color="gray" size="xs" leftSection={<IconX size={14} />} mb={4}
              onClick={() => { setStatus(null); setSource(null); setOwner(null); setQ(''); setFollowUpDue(false); setRange([null, null]); setPage(0); }}>
              Clear filters
            </Button>
          )}
        </Group>
      </Paper>
      <Paper withBorder>
        <QueryState isLoading={list.isLoading} error={list.error} empty={rows.length === 0}
          emptyText={filtersActive ? 'No leads match these filters.' : 'No leads yet.'}>
          <Table.ScrollContainer minWidth={1000}>
            <Table verticalSpacing="sm" highlightOnHover style={{ opacity: list.isPlaceholderData ? 0.6 : 1 }}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Lead</Table.Th>
                  <Table.Th>Branch</Table.Th>
                  <Table.Th>Source</Table.Th>
                  <Table.Th miw={140}>Status</Table.Th>
                  <Table.Th>Owner</Table.Th>
                  <Table.Th>Next follow-up</Table.Th>
                  <Table.Th>Created</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {rows.map((l) => (
                  <Table.Tr key={l.id} style={{ cursor: 'pointer' }} onClick={() => navigate(`/leads/${l.id}`)}>
                    <Table.Td>
                      <Anchor component={Link} to={`/leads/${l.id}`} size="sm" fw={600} onClick={(e) => e.stopPropagation()}>
                        {l.fullName}
                      </Anchor>
                      <Text size="xs" c="dimmed">{l.phone}{l.email ? ` · ${l.email}` : ''}</Text>
                    </Table.Td>
                    <Table.Td><Text size="sm">{l.branchName}</Text></Table.Td>
                    <Table.Td><Text size="sm">{label(l.source)}</Text><Text size="xs" c="dimmed">{label(l.interest)}</Text></Table.Td>
                    <Table.Td>
                      <LeadStatusBadge status={l.status} />
                      {l.lostReason && <Text size="xs" c="dimmed">{label(l.lostReason)}</Text>}
                    </Table.Td>
                    <Table.Td><Text size="sm" c={l.ownerName ? undefined : 'dimmed'}>{l.ownerName ?? 'Unassigned'}</Text></Table.Td>
                    <Table.Td>
                      <Text size="sm" c={l.followUpOverdue ? 'red' : undefined}>{l.nextFollowUpAt ? dateTime(l.nextFollowUpAt) : '—'}</Text>
                    </Table.Td>
                    <Table.Td><Text size="sm" style={{ whiteSpace: 'nowrap' }}>{fromNow(l.createdAt)}</Text></Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
        </QueryState>
      </Paper>
      <Pager page={list.data} onChange={setPage} />
    </>
  );
}
