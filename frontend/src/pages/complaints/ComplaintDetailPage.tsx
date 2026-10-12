import {
  Alert, Anchor, Badge, Button, Checkbox, Divider, Grid, Group, Modal, Paper, Select, Stack, Tabs, Text, Textarea,
  ThemeIcon, Timeline, Title, Tooltip,
} from '@mantine/core';
import {
  IconArrowBackUp, IconArrowBigUpLines, IconCheck, IconChevronLeft, IconCircleCheck, IconHistory, IconLock,
  IconMessage, IconMessageCircle, IconPlayerPlay, IconRefresh, IconUserCheck,
} from '@tabler/icons-react';
import { useEffect, useState, type ReactNode } from 'react';
import { Link, useParams } from 'react-router-dom';
import type { AuditEntry, Complaint, ComplaintComment, ComplaintStatus, Member, Page } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { FRONT_DESK, hasAnyRole, isMemberOnly, MANAGERS, ORG_WIDE } from '@/auth/roles';
import { useBranchNames } from '@/components/BranchSelect';
import { PageHeader } from '@/components/PageHeader';
import { Pager } from '@/components/Pager';
import { QueryState } from '@/components/QueryState';
import { StatusBadge } from '@/components/StatusBadge';
import { dateTime, fromNow, label } from '@/lib/format';
import { errorMessage } from '@/lib/notify';
import { useApiMutation, useApiQuery } from '@/lib/useApi';
import { EscalationBadge, MAX_ESCALATION_LEVEL, SlaBadge, TRANSITIONS, UNRESOLVED, useStaffDirectory } from './shared';

const TRANSITION_UI: Record<ComplaintStatus, { verb: string; color: string; icon: ReactNode }> = {
  IN_PROGRESS: { verb: 'Start work', color: 'indigo', icon: <IconPlayerPlay size={16} /> },
  ESCALATED: { verb: 'Mark escalated', color: 'orange', icon: <IconArrowBigUpLines size={16} /> },
  RESOLVED: { verb: 'Resolve', color: 'teal', icon: <IconCircleCheck size={16} /> },
  CLOSED: { verb: 'Close', color: 'gray', icon: <IconCheck size={16} /> },
  REOPENED: { verb: 'Reopen', color: 'orange', icon: <IconRefresh size={16} /> },
  OPEN: { verb: 'Open', color: 'blue', icon: <IconMessage size={16} /> },
};

export default function ComplaintDetailPage() {
  const { id } = useParams();
  const { user } = useAuth();
  const member = isMemberOnly(user);
  const canManage = hasAnyRole(user, MANAGERS);
  const frontDesk = hasAnyRole(user, FRONT_DESK);
  const base = `/api/v1/complaints/${id}`;

  const q = useApiQuery<Complaint>(id ? base : null);
  const c = q.data;
  const comments = useApiQuery<ComplaintComment[]>(c ? `${base}/comments` : null);
  const reporter = useApiQuery<Member>(c && !member && c.memberId != null ? `/api/v1/members/${c.memberId}` : null);
  const staff = useStaffDirectory(user, c && !member ? [c.branchId] : []);
  const branchNames = useBranchNames();

  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = window.setInterval(() => setNow(Date.now()), 30_000);
    return () => window.clearInterval(t);
  }, []);

  const [target, setTarget] = useState<ComplaintStatus | null>(null);
  const [escalating, setEscalating] = useState(false);

  const backTo = member ? '/me/complaints' : '/complaints';

  /** Display name for a user id in the context of this ticket. */
  const nameOf = (userId: number | null | undefined, fallback = 'Unassigned'): string => {
    if (userId == null) return fallback;
    if (user && userId === user.id) return 'You';
    if (member) return 'Support team';
    if (c && userId === c.raisedBy && reporter.data?.userId === userId) {
      return `${reporter.data.firstName} ${reporter.data.lastName} (member)`;
    }
    // names resolved by the API (works for every staff role), then the branch staff directory
    if (c && userId === c.raisedBy && c.raisedByName) return c.raisedByName;
    if (c && userId === c.assignedTo && c.assignedToName) return c.assignedToName;
    const fromComment = (comments.data ?? []).find((cm) => cm.authorId === userId && cm.authorName);
    if (fromComment?.authorName) return fromComment.authorName;
    return staff.nameOf(userId);
  };

  return (
    <>
      <PageHeader
        title={c ? c.ticketNumber : 'Complaint'}
        subtitle={c ? `Raised ${fromNow(c.createdAt)} · ${branchNames[c.branchId] ?? `Branch ${c.branchId}`}` : undefined}
        actions={<Button component={Link} to={backTo} variant="default" leftSection={<IconChevronLeft size={16} />}>All complaints</Button>}
      />
      <QueryState isLoading={q.isLoading} error={q.error}>
        {c && (
          <Grid gutter="md">
            <Grid.Col span={{ base: 12, lg: 8 }}>
              <Stack gap="md">
                <Paper withBorder p="lg">
                  <Group gap="xs" mb="xs">
                    <StatusBadge value={c.status} size="lg" />
                    <StatusBadge value={c.priority} size="lg" />
                    <Badge variant="outline" color="gray" radius="sm" size="lg">{label(c.category)}</Badge>
                    {c.escalationLevel > 0 && <EscalationBadge level={c.escalationLevel} />}
                  </Group>
                  <Title order={3} mb="sm">{c.subject}</Title>
                  <Text style={{ whiteSpace: 'pre-wrap' }}>{c.description}</Text>
                  {c.resolution && (
                    <Alert mt="md" variant="light" icon={<IconCircleCheck size={18} />}
                      color={c.status === 'RESOLVED' || c.status === 'CLOSED' ? 'teal' : 'gray'}
                      title={c.status === 'RESOLVED' || c.status === 'CLOSED' ? 'Resolution' : 'Previous resolution (ticket was reopened)'}>
                      <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>{c.resolution}</Text>
                    </Alert>
                  )}
                  {member && c.status === 'RESOLVED' && <MemberResolutionActions complaint={c} base={base} />}
                </Paper>

                {member ? (
                  <Paper withBorder p="lg">
                    <Conversation comments={comments} nameOf={nameOf} complaint={c} />
                    <CommentForm base={base} staff={false} disabled={c.status === 'CLOSED'} />
                  </Paper>
                ) : (
                  <Paper withBorder p="lg">
                    <Tabs defaultValue="conversation" keepMounted={false}>
                      <Tabs.List mb="md">
                        <Tabs.Tab value="conversation" leftSection={<IconMessageCircle size={16} />}>
                          Conversation {comments.data ? `(${comments.data.length})` : ''}
                        </Tabs.Tab>
                        <Tabs.Tab value="history" leftSection={<IconHistory size={16} />}>Audit history</Tabs.Tab>
                      </Tabs.List>
                      <Tabs.Panel value="conversation">
                        <Conversation comments={comments} nameOf={nameOf} complaint={c} />
                        <CommentForm base={base} staff disabled={false} />
                      </Tabs.Panel>
                      <Tabs.Panel value="history">
                        <History base={base} nameOf={nameOf} />
                      </Tabs.Panel>
                    </Tabs>
                  </Paper>
                )}
              </Stack>
            </Grid.Col>

            <Grid.Col span={{ base: 12, lg: 4 }}>
              <Stack gap="md">
                <Paper withBorder p="md">
                  <Title order={5} mb="sm">SLA</Title>
                  <SlaBadge complaint={c} now={now} withDue={false} />
                  <Text size="sm" mt="xs">Due {dateTime(c.slaDueAt)}</Text>
                  <Text size="xs" c="dimmed" mt={4}>
                    The SLA window depends on priority. Escalation shortens it to half a window; the SLA job escalates breached
                    tickets automatically (up to level {MAX_ESCALATION_LEVEL}).
                  </Text>
                  <Divider my="sm" />
                  <Group justify="space-between">
                    <Text size="sm" c="dimmed">Escalation level</Text>
                    <Group gap={4}>
                      {Array.from({ length: MAX_ESCALATION_LEVEL }, (_, i) => (
                        <Tooltip key={i} label={`Level ${i + 1}`} withArrow>
                          <div style={{
                            width: 22, height: 8, borderRadius: 4,
                            background: i < c.escalationLevel ? 'var(--mantine-color-orange-6)' : 'var(--mantine-color-default-border)',
                          }} />
                        </Tooltip>
                      ))}
                      <Text size="sm" fw={600} ml={4}>{c.escalationLevel}/{MAX_ESCALATION_LEVEL}</Text>
                    </Group>
                  </Group>
                </Paper>

                <Paper withBorder p="md">
                  <Title order={5} mb="sm">Details</Title>
                  <Stack gap={8}>
                    <Detail label="Ticket">{c.ticketNumber}</Detail>
                    <Detail label="Branch">{branchNames[c.branchId] ?? `Branch ${c.branchId}`}</Detail>
                    {!member && (
                      <Detail label="Member">
                        {c.memberId == null ? <Text size="sm" c="dimmed">Not linked</Text> : (
                          <Anchor component={Link} to={`/members/${c.memberId}`} size="sm">
                            {reporter.data ? `${reporter.data.firstName} ${reporter.data.lastName} · ${reporter.data.memberCode}` : `Member #${c.memberId}`}
                          </Anchor>
                        )}
                      </Detail>
                    )}
                    <Detail label="Raised by">{nameOf(c.raisedBy)}</Detail>
                    <Detail label="Assignee">{member ? (c.assignedTo ? 'Support team' : 'Not yet assigned') : nameOf(c.assignedTo)}</Detail>
                    <Detail label="Created">{dateTime(c.createdAt)}</Detail>
                    <Detail label="First response">{c.firstResponseAt ? dateTime(c.firstResponseAt) : '—'}</Detail>
                    <Detail label="Resolved">{c.resolvedAt ? dateTime(c.resolvedAt) : '—'}</Detail>
                    <Detail label="Closed">{c.closedAt ? dateTime(c.closedAt) : '—'}</Detail>
                  </Stack>
                </Paper>

                {!member && (
                  <Paper withBorder p="md">
                    <Title order={5} mb="sm">Actions</Title>
                    <Stack gap="md">
                      <div>
                        <Text size="sm" fw={500} mb={6}>Move to</Text>
                        {TRANSITIONS[c.status].length === 0 ? (
                          <Text size="sm" c="dimmed">Closed tickets are final.</Text>
                        ) : (
                          <Group gap="xs">
                            {TRANSITIONS[c.status].map((s) => (
                              <Button key={s} size="xs" variant="light" color={TRANSITION_UI[s].color} leftSection={TRANSITION_UI[s].icon}
                                onClick={() => setTarget(s)}>
                                {TRANSITION_UI[s].verb}
                              </Button>
                            ))}
                          </Group>
                        )}
                      </div>

                      {frontDesk && UNRESOLVED.includes(c.status) && (
                        <div>
                          <Text size="sm" fw={500} mb={6}>Escalate</Text>
                          <Button size="xs" color="orange" leftSection={<IconArrowBigUpLines size={16} />}
                            disabled={c.escalationLevel >= MAX_ESCALATION_LEVEL} onClick={() => setEscalating(true)}>
                            Escalate to level {Math.min(c.escalationLevel + 1, MAX_ESCALATION_LEVEL)}
                          </Button>
                          <Text size="xs" c="dimmed" mt={4}>
                            {c.escalationLevel >= MAX_ESCALATION_LEVEL
                              ? 'Already at the highest escalation level.'
                              : 'Raises the escalation level and priority and restarts a shorter SLA window.'}
                          </Text>
                        </div>
                      )}

                      {canManage && UNRESOLVED.includes(c.status) && (
                        <AssignControl complaint={c} base={base} candidates={staff.byBranch[c.branchId] ?? []}
                          loading={staff.loading} />
                      )}
                    </Stack>
                  </Paper>
                )}
              </Stack>
            </Grid.Col>
          </Grid>
        )}
      </QueryState>

      {c && <StatusModal complaint={c} base={base} target={target} onClose={() => setTarget(null)} />}
      {c && <EscalateModal complaint={c} base={base} opened={escalating} onClose={() => setEscalating(false)} />}
    </>
  );
}

function Detail({ label: l, children }: { label: string; children: ReactNode }) {
  return (
    <Group justify="space-between" gap="sm" wrap="nowrap" align="flex-start">
      <Text size="sm" c="dimmed" style={{ flexShrink: 0 }}>{l}</Text>
      {typeof children === 'string' ? <Text size="sm" ta="right">{children}</Text> : children}
    </Group>
  );
}

function Conversation({ comments, nameOf, complaint }: {
  comments: { data?: ComplaintComment[]; isLoading: boolean; error: unknown };
  nameOf: (id: number | null | undefined) => string;
  complaint: Complaint;
}) {
  return (
    <QueryState isLoading={comments.isLoading} error={comments.error}>
      <Timeline bulletSize={28} lineWidth={2} mb="lg">
        <Timeline.Item bullet={<IconMessage size={14} />} title={<Text size="sm" fw={600}>{nameOf(complaint.raisedBy)} opened the ticket</Text>}>
          <Text size="xs" c="dimmed">{dateTime(complaint.createdAt)}</Text>
        </Timeline.Item>
        {(comments.data ?? []).map((cm) => (
          <Timeline.Item
            key={cm.id}
            color={cm.internal ? 'yellow' : undefined}
            bullet={cm.internal ? <IconLock size={14} /> : <IconMessageCircle size={14} />}
            title={
              <Group gap="xs">
                <Text size="sm" fw={600}>{nameOf(cm.authorId)}</Text>
                {cm.internal && <Badge size="xs" color="yellow" variant="light">Internal note</Badge>}
                <Tooltip label={dateTime(cm.createdAt)} withArrow>
                  <Text size="xs" c="dimmed">{fromNow(cm.createdAt)}</Text>
                </Tooltip>
              </Group>
            }
          >
            <Paper p="sm" mt={4} radius="md" withBorder={!cm.internal}
              bg={cm.internal ? 'var(--mantine-color-yellow-light)' : undefined}>
              <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>{cm.body}</Text>
            </Paper>
          </Timeline.Item>
        ))}
      </Timeline>
      {comments.data?.length === 0 && <Text size="sm" c="dimmed" mb="md">No replies yet.</Text>}
    </QueryState>
  );
}

function CommentForm({ base, staff, disabled }: { base: string; staff: boolean; disabled: boolean }) {
  const [body, setBody] = useState('');
  const [internal, setInternal] = useState(false);
  const post = useApiMutation<void, ComplaintComment>({
    request: () => ({ path: `${base}/comments`, body: { body: body.trim(), internal: staff && internal } }),
    invalidate: [base],
    successMessage: staff && internal ? 'Internal note added' : 'Reply posted',
    onSuccess: () => { setBody(''); setInternal(false); },
  });
  if (disabled) {
    return <Text size="sm" c="dimmed">This ticket is closed. Raise a new complaint if you need more help.</Text>;
  }
  return (
    <Stack gap="xs">
      <Textarea
        placeholder={staff ? (internal ? 'Internal note for staff only…' : 'Reply to the member…') : 'Add a reply…'}
        autosize minRows={3} maxRows={10} maxLength={4000}
        value={body} onChange={(e) => setBody(e.currentTarget.value)}
        styles={internal ? { input: { background: 'var(--mantine-color-yellow-light)' } } : undefined}
      />
      <Group justify="space-between">
        {staff ? (
          <Checkbox label="Internal note (hidden from the member)" checked={internal} color="yellow"
            onChange={(e) => setInternal(e.currentTarget.checked)} />
        ) : <span />}
        <Button onClick={() => post.mutate()} loading={post.isPending} disabled={body.trim() === ''}
          color={internal ? 'yellow' : undefined} leftSection={internal ? <IconLock size={16} /> : <IconMessageCircle size={16} />}>
          {internal ? 'Add note' : 'Post reply'}
        </Button>
      </Group>
    </Stack>
  );
}

function History({ base, nameOf }: { base: string; nameOf: (id: number | null | undefined) => string }) {
  const [page, setPage] = useState(0);
  const q = useApiQuery<Page<AuditEntry>>(`${base}/history`, { page, size: 20 });
  const describe = (e: AuditEntry): ReactNode => {
    const d = e.details ?? {};
    const parts: string[] = [];
    if (d.from != null || d.to != null) parts.push(`${label(String(d.from ?? '?'))} → ${label(String(d.to ?? '?'))}`);
    if (d.assignee != null) parts.push(`assigned to ${nameOf(Number(d.assignee))}`);
    if (d.level != null) parts.push(`level ${String(d.level)}`);
    if (d.priority != null && e.action !== 'CREATED') parts.push(`priority ${label(String(d.priority))}`);
    if (e.action === 'CREATED') {
      if (d.category != null) parts.push(label(String(d.category)));
      if (d.priority != null) parts.push(`${label(String(d.priority))} priority`);
    }
    if (d.internal != null) parts.push(d.internal ? 'internal note' : 'public reply');
    return (
      <>
        {parts.length > 0 && <Text size="sm">{parts.join(' · ')}</Text>}
        {d.reason != null && <Text size="sm" c="dimmed">Reason: {String(d.reason)}</Text>}
        {d.resolution != null && <Text size="sm" c="dimmed">Resolution: {String(d.resolution)}</Text>}
      </>
    );
  };
  return (
    <QueryState isLoading={q.isLoading} error={q.error} empty={q.data?.content.length === 0} emptyText="No audit entries.">
      <Timeline bulletSize={24} lineWidth={2}>
        {(q.data?.content ?? []).map((e) => (
          <Timeline.Item key={e.id} bullet={<IconHistory size={12} />}
            title={
              <Group gap="xs">
                <Text size="sm" fw={600}>{label(e.action)}</Text>
                <Text size="xs" c="dimmed">by {e.actorUserId != null ? nameOf(e.actorUserId) : e.actorEmail ?? 'system'}</Text>
              </Group>
            }>
            {describe(e)}
            <Text size="xs" c="dimmed" mt={2}>{dateTime(e.occurredAt)}{e.actorEmail && e.actorUserId != null ? ` · ${e.actorEmail}` : ''}</Text>
          </Timeline.Item>
        ))}
      </Timeline>
      <Pager page={q.data} onChange={setPage} />
    </QueryState>
  );
}

function AssignControl({ complaint, base, candidates, loading }: {
  complaint: Complaint;
  base: string;
  candidates: { userId: number; fullName: string; roles: string[] }[];
  loading: boolean;
}) {
  const { user } = useAuth();
  const [assignee, setAssignee] = useState<string | null>(complaint.assignedTo != null ? String(complaint.assignedTo) : null);
  const data = candidates.map((s) => ({ value: String(s.userId), label: `${s.fullName} · ${s.roles.map(label).join(', ')}` }));
  // org-wide admins are not branch staff but may take tickets themselves
  if (user && hasAnyRole(user, ORG_WIDE) && !data.some((d) => d.value === String(user.id))) {
    data.unshift({ value: String(user.id), label: `${user.fullName} (me)` });
  }
  if (complaint.assignedTo != null && !data.some((d) => d.value === String(complaint.assignedTo))) {
    data.push({ value: String(complaint.assignedTo), label: complaint.assignedToName ?? `User #${complaint.assignedTo}` });
  }
  const assign = useApiMutation<void, Complaint>({
    request: () => ({ path: `${base}/assignee`, method: 'PATCH', body: { assigneeUserId: Number(assignee) } }),
    invalidate: ['/api/v1/complaints'],
    successMessage: 'Assignee updated',
  });
  return (
    <div>
      <Text size="sm" fw={500} mb={6}>Assignee</Text>
      <Group gap="xs" wrap="nowrap" align="flex-start">
        <Select data={data} value={assignee} onChange={setAssignee} placeholder={loading ? 'Loading staff…' : 'Pick a staff member'}
          searchable nothingFoundMessage="No staff assigned to this branch" style={{ flex: 1 }} size="xs" />
        <Button size="xs" leftSection={<IconUserCheck size={16} />} loading={assign.isPending}
          disabled={assignee == null || assignee === String(complaint.assignedTo)} onClick={() => assign.mutate()}>
          Assign
        </Button>
      </Group>
      <Text size="xs" c="dimmed" mt={4}>Only staff of this branch (or org-wide admins) can be assigned.</Text>
    </div>
  );
}

function StatusModal({ complaint, base, target, onClose }: {
  complaint: Complaint;
  base: string;
  target: ComplaintStatus | null;
  onClose: () => void;
}) {
  const [resolution, setResolution] = useState('');
  useEffect(() => { if (target) setResolution(''); }, [target]);
  const change = useApiMutation<void, Complaint>({
    request: () => ({
      path: `${base}/status`,
      method: 'PATCH',
      body: { status: target, resolution: target === 'RESOLVED' ? resolution.trim() : undefined },
    }),
    invalidate: ['/api/v1/complaints', '/api/v1/dashboard'],
    successMessage: (c) => `Ticket ${c.ticketNumber} is now ${label(c.status).toLowerCase()}`,
    silentError: true,
    onSuccess: onClose,
  });
  const needsResolution = target === 'RESOLVED';
  return (
    <Modal opened={target != null} onClose={onClose} centered
      title={target ? `${TRANSITION_UI[target].verb} ${complaint.ticketNumber}` : ''}>
      {target && (
        <Stack>
          <Group gap="xs">
            <StatusBadge value={complaint.status} />
            <Text c="dimmed">→</Text>
            <StatusBadge value={target} />
          </Group>
          {needsResolution && (
            <Textarea label="Resolution" description="Shown to the member, who can accept (close) or reopen the ticket."
              required autosize minRows={3} maxLength={4000} value={resolution} onChange={(e) => setResolution(e.currentTarget.value)} />
          )}
          {target === 'CLOSED' && <Text size="sm" c="dimmed">Closed tickets cannot be reopened.</Text>}
          {target === 'REOPENED' && <Text size="sm" c="dimmed">Reopening starts a fresh SLA window.</Text>}
          {target === 'ESCALATED' && (
            <Text size="sm" c="dimmed">This only changes the status. Use “Escalate” to also raise the level and priority.</Text>
          )}
          {change.error && <Alert color="red">{errorMessage(change.error)}</Alert>}
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose}>Cancel</Button>
            <Button color={TRANSITION_UI[target].color} loading={change.isPending}
              disabled={needsResolution && resolution.trim() === ''} onClick={() => change.mutate()}>
              {TRANSITION_UI[target].verb}
            </Button>
          </Group>
        </Stack>
      )}
    </Modal>
  );
}

function EscalateModal({ complaint, base, opened, onClose }: { complaint: Complaint; base: string; opened: boolean; onClose: () => void }) {
  const [reason, setReason] = useState('');
  useEffect(() => { if (opened) setReason(''); }, [opened]);
  const escalate = useApiMutation<void, Complaint>({
    request: () => ({ path: `${base}/escalate`, body: { reason: reason.trim() } }),
    invalidate: ['/api/v1/complaints', '/api/v1/dashboard'],
    successMessage: (c) => `Escalated to level ${c.escalationLevel} (${label(c.priority).toLowerCase()} priority)`,
    silentError: true,
    onSuccess: onClose,
  });
  return (
    <Modal opened={opened} onClose={onClose} centered title={`Escalate ${complaint.ticketNumber}`}>
      <Stack>
        <Text size="sm">
          Level {complaint.escalationLevel} → {complaint.escalationLevel + 1}. Priority is raised one step (max critical) and the SLA
          window restarts at half its normal length.
        </Text>
        <Textarea label="Reason" required autosize minRows={3} maxLength={500} value={reason}
          onChange={(e) => setReason(e.currentTarget.value)} />
        {escalate.error && <Alert color="red">{errorMessage(escalate.error)}</Alert>}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>Cancel</Button>
          <Button color="orange" loading={escalate.isPending} disabled={reason.trim() === ''} onClick={() => escalate.mutate()}>
            Escalate
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}

/** A member may accept (close) or reopen a resolved ticket; an optional note is posted as a reply. */
function MemberResolutionActions({ complaint, base }: { complaint: Complaint; base: string }) {
  const [mode, setMode] = useState<'CLOSED' | 'REOPENED' | null>(null);
  const [note, setNote] = useState('');
  const comment = useApiMutation<string, ComplaintComment>({
    request: (body) => ({ path: `${base}/comments`, body: { body, internal: false } }),
    invalidate: [base],
  });
  const change = useApiMutation<void, Complaint>({
    request: () => ({ path: `${base}/status`, method: 'PATCH', body: { status: mode } }),
    invalidate: ['/api/v1/complaints'],
    successMessage: (c) => (c.status === 'CLOSED' ? 'Thanks! Your ticket is closed.' : 'Ticket reopened, our team will follow up.'),
    silentError: true,
    onSuccess: () => {
      if (note.trim()) comment.mutate(note.trim());
      setMode(null);
      setNote('');
    },
  });
  return (
    <>
      <Paper mt="md" p="md" withBorder bg="var(--mantine-color-teal-light)">
        <Group justify="space-between" wrap="wrap" gap="sm">
          <Group gap="sm" wrap="nowrap">
            <ThemeIcon color="teal" variant="light"><IconCircleCheck size={18} /></ThemeIcon>
            <Text size="sm">Is your issue fixed? Accept the resolution or reopen the ticket.</Text>
          </Group>
          <Group gap="xs">
            <Button size="xs" color="teal" leftSection={<IconCheck size={16} />} onClick={() => setMode('CLOSED')}>Accept &amp; close</Button>
            <Button size="xs" variant="default" leftSection={<IconArrowBackUp size={16} />} onClick={() => setMode('REOPENED')}>Reopen</Button>
          </Group>
        </Group>
      </Paper>
      <Modal opened={mode != null} onClose={() => setMode(null)} centered
        title={mode === 'CLOSED' ? `Close ${complaint.ticketNumber}` : `Reopen ${complaint.ticketNumber}`}>
        <Stack>
          <Textarea
            label={mode === 'CLOSED' ? 'Feedback (optional)' : 'What is still wrong? (optional)'}
            autosize minRows={3} maxLength={4000} value={note} onChange={(e) => setNote(e.currentTarget.value)} />
          {change.error && <Alert color="red">{errorMessage(change.error)}</Alert>}
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setMode(null)}>Cancel</Button>
            <Button color={mode === 'CLOSED' ? 'teal' : 'orange'} loading={change.isPending || comment.isPending} onClick={() => change.mutate()}>
              {mode === 'CLOSED' ? 'Close ticket' : 'Reopen ticket'}
            </Button>
          </Group>
        </Stack>
      </Modal>
    </>
  );
}
