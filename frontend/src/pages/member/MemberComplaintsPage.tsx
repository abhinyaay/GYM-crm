import {
  Alert, Badge, Button, Divider, Group, Modal, Paper, ScrollArea, Select, Stack, Table, Text, Textarea, TextInput, Title,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { IconCheck, IconMessageCircle, IconPlus, IconRotate } from '@tabler/icons-react';
import { useState } from 'react';
import type { Complaint, ComplaintCategory, ComplaintComment, ComplaintStatus, Page } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { PageHeader } from '@/components/PageHeader';
import { Pager } from '@/components/Pager';
import { QueryState } from '@/components/QueryState';
import { StatusBadge } from '@/components/StatusBadge';
import { dateTime, fromNow, label } from '@/lib/format';
import { errorMessage } from '@/lib/notify';
import { useApiMutation, useApiQuery } from '@/lib/useApi';

const CATEGORIES: ComplaintCategory[] = ['FACILITY', 'EQUIPMENT', 'STAFF', 'TRAINER', 'BILLING', 'HYGIENE', 'MEMBERSHIP', 'OTHER'];
const STATUS_FILTERS: (ComplaintStatus | 'ALL')[] = ['ALL', 'OPEN', 'IN_PROGRESS', 'ESCALATED', 'RESOLVED', 'REOPENED', 'CLOSED'];

// ------------------------------------------------------------------------------------ create

interface NewComplaint {
  category: ComplaintCategory | null;
  subject: string;
  description: string;
}

function NewComplaintModal({ opened, onClose, onCreated }: {
  opened: boolean;
  onClose: () => void;
  onCreated: (c: Complaint) => void;
}) {
  const form = useForm<NewComplaint>({
    initialValues: { category: null, subject: '', description: '' },
    validate: {
      category: (v) => (v ? null : 'Choose a category'),
      subject: (v) => (!v.trim() ? 'Subject is required' : v.length > 200 ? 'At most 200 characters' : null),
      description: (v) => (!v.trim() ? 'Please describe the issue' : v.length > 4000 ? 'At most 4000 characters' : null),
    },
  });
  const create = useApiMutation<NewComplaint, Complaint>({
    request: (v) => ({
      path: '/api/v1/complaints',
      body: { category: v.category, subject: v.subject.trim(), description: v.description.trim() },
    }),
    invalidate: ['/api/v1/complaints'],
    successMessage: (c) => `Ticket ${c.ticketNumber} created`,
    silentError: true,
    onSuccess: (c) => {
      form.reset();
      onCreated(c);
    },
  });
  const close = () => {
    create.reset();
    onClose();
  };
  return (
    <Modal opened={opened} onClose={close} title="Raise a support request" centered size="lg">
      <form onSubmit={form.onSubmit((v) => create.mutate(v))}>
        <Stack>
          <Select
            label="Category"
            placeholder="What is this about?"
            data={CATEGORIES.map((c) => ({ value: c, label: label(c) }))}
            withAsterisk
            {...form.getInputProps('category')}
          />
          <TextInput label="Subject" placeholder="Short summary" maxLength={200} withAsterisk {...form.getInputProps('subject')} />
          <Textarea
            label="Description"
            placeholder="Tell us what happened, where and when"
            autosize
            minRows={4}
            maxRows={10}
            maxLength={4000}
            withAsterisk
            {...form.getInputProps('description')}
          />
          {create.error && <Alert color="red">{errorMessage(create.error)}</Alert>}
          <Group justify="flex-end">
            <Button variant="default" onClick={close}>Cancel</Button>
            <Button type="submit" loading={create.isPending}>Submit</Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}

// ------------------------------------------------------------------------------------ detail

function ComplaintDetail({ id }: { id: number }) {
  const { user } = useAuth();
  const complaint = useApiQuery<Complaint>(`/api/v1/complaints/${id}`);
  const comments = useApiQuery<ComplaintComment[]>(`/api/v1/complaints/${id}/comments`);
  const [body, setBody] = useState('');
  const [reopening, setReopening] = useState(false);
  const [reopenReason, setReopenReason] = useState('');
  const [confirmClose, setConfirmClose] = useState(false);

  const addComment = useApiMutation<string, ComplaintComment>({
    request: (text) => ({ path: `/api/v1/complaints/${id}/comments`, body: { body: text, internal: false } }),
    invalidate: [`/api/v1/complaints/${id}/comments`],
    silentError: true,
    onSuccess: () => setBody(''),
  });
  const changeStatus = useApiMutation<ComplaintStatus, Complaint>({
    request: (status) => ({ path: `/api/v1/complaints/${id}/status`, method: 'PATCH', body: { status } }),
    invalidate: ['/api/v1/complaints'],
    successMessage: (c) => (c.status === 'CLOSED' ? 'Thanks — ticket closed' : 'Ticket reopened'),
    silentError: true,
    onSuccess: () => {
      setReopening(false);
      setConfirmClose(false);
      setReopenReason('');
    },
  });
  // reopen = explain why (comment), then change status
  const reopenComment = useApiMutation<string, ComplaintComment>({
    request: (text) => ({ path: `/api/v1/complaints/${id}/comments`, body: { body: text, internal: false } }),
    invalidate: [`/api/v1/complaints/${id}/comments`],
    silentError: true,
    onSuccess: () => changeStatus.mutate('REOPENED'),
  });

  const c = complaint.data;
  const list = comments.data ?? [];
  const actionError = changeStatus.error ?? reopenComment.error;

  return (
    <QueryState isLoading={complaint.isLoading} error={complaint.error}>
      {c && (
        <Stack gap="md">
          <Group justify="space-between" align="flex-start" wrap="wrap">
            <div>
              <Text size="xs" c="dimmed">{c.ticketNumber} · {label(c.category)}</Text>
              <Title order={4}>{c.subject}</Title>
            </div>
            <Group gap="xs">
              <StatusBadge value={c.priority} />
              <StatusBadge value={c.status} />
            </Group>
          </Group>
          <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>{c.description}</Text>
          <Group gap="lg">
            <Text size="xs" c="dimmed">Raised {dateTime(c.createdAt)}</Text>
            {c.resolvedAt && <Text size="xs" c="dimmed">Resolved {dateTime(c.resolvedAt)}</Text>}
            {c.closedAt && <Text size="xs" c="dimmed">Closed {dateTime(c.closedAt)}</Text>}
            {!c.resolvedAt && !c.closedAt && (
              <Text size="xs" c={c.slaBreached ? 'red' : 'dimmed'}>
                Target response by {dateTime(c.slaDueAt)}
              </Text>
            )}
          </Group>

          {c.resolution && (
            <Alert color="teal" title="Resolution" icon={<IconCheck size={18} />}>
              <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>{c.resolution}</Text>
            </Alert>
          )}

          {c.status === 'RESOLVED' && !reopening && !confirmClose && (
            <Paper withBorder p="sm" radius="md">
              <Text size="sm" mb="xs">Has this resolved your issue?</Text>
              <Group gap="xs">
                <Button size="xs" leftSection={<IconCheck size={14} />} onClick={() => setConfirmClose(true)}>
                  Accept resolution
                </Button>
                <Button size="xs" variant="light" color="orange" leftSection={<IconRotate size={14} />}
                  onClick={() => setReopening(true)}>
                  Reopen
                </Button>
              </Group>
            </Paper>
          )}
          {confirmClose && (
            <Paper withBorder p="sm" radius="md">
              <Text size="sm" mb="xs">Close this ticket? It cannot be reopened after it is closed.</Text>
              <Group gap="xs" justify="flex-end">
                <Button size="xs" variant="default" onClick={() => setConfirmClose(false)}>Back</Button>
                <Button size="xs" loading={changeStatus.isPending} onClick={() => changeStatus.mutate('CLOSED')}>
                  Yes, close ticket
                </Button>
              </Group>
            </Paper>
          )}
          {reopening && (
            <Paper withBorder p="sm" radius="md">
              <Textarea
                label="Why are you reopening this ticket?"
                autosize
                minRows={2}
                maxLength={4000}
                value={reopenReason}
                onChange={(e) => setReopenReason(e.currentTarget.value)}
              />
              <Group gap="xs" justify="flex-end" mt="xs">
                <Button size="xs" variant="default" onClick={() => setReopening(false)}>Back</Button>
                <Button size="xs" color="orange" disabled={!reopenReason.trim()}
                  loading={reopenComment.isPending || changeStatus.isPending}
                  onClick={() => reopenComment.mutate(reopenReason.trim())}>
                  Reopen ticket
                </Button>
              </Group>
            </Paper>
          )}
          {actionError && <Alert color="red">{errorMessage(actionError)}</Alert>}

          <Divider label="Conversation" labelPosition="left" />
          <QueryState isLoading={comments.isLoading} error={comments.error} empty={list.length === 0}
            emptyText="No messages yet. Our team will reply here.">
            <ScrollArea.Autosize mah={320}>
              <Stack gap="xs">
                {list.map((m) => {
                  const mine = m.authorId === user?.id;
                  return (
                    <Paper key={m.id} withBorder p="sm" radius="md" bg={mine ? undefined : 'var(--mantine-color-teal-light)'}
                      ml={mine ? 'xl' : 0} mr={mine ? 0 : 'xl'}>
                      <Group justify="space-between" mb={4}>
                        <Text size="xs" fw={600}>{mine ? 'You' : 'GymOps support'}</Text>
                        <Text size="xs" c="dimmed">{fromNow(m.createdAt)}</Text>
                      </Group>
                      <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>{m.body}</Text>
                    </Paper>
                  );
                })}
              </Stack>
            </ScrollArea.Autosize>
          </QueryState>
          {c.status !== 'CLOSED' && (
            <Stack gap="xs">
              <Textarea
                label="Add a message"
                placeholder="Write a reply…"
                autosize
                minRows={2}
                maxLength={4000}
                value={body}
                onChange={(e) => setBody(e.currentTarget.value)}
              />
              {addComment.error && <Alert color="red">{errorMessage(addComment.error)}</Alert>}
              <Group justify="flex-end">
                <Button size="xs" leftSection={<IconMessageCircle size={14} />} disabled={!body.trim()}
                  loading={addComment.isPending} onClick={() => addComment.mutate(body.trim())}>
                  Send
                </Button>
              </Group>
            </Stack>
          )}
        </Stack>
      )}
    </QueryState>
  );
}

// ------------------------------------------------------------------------------------ page

export default function MemberComplaintsPage() {
  const [status, setStatus] = useState<ComplaintStatus | 'ALL'>('ALL');
  const [page, setPage] = useState(0);
  const [creating, setCreating] = useState(false);
  const [openId, setOpenId] = useState<number | null>(null);
  const complaints = useApiQuery<Page<Complaint>>('/api/v1/complaints', {
    status: status === 'ALL' ? undefined : status, page, size: 20, sort: 'createdAt,desc',
  });
  const rows = complaints.data?.content ?? [];

  return (
    <>
      <PageHeader
        title="Support"
        subtitle="Raise an issue and follow up with our team"
        actions={<Button leftSection={<IconPlus size={16} />} onClick={() => setCreating(true)}>New request</Button>}
      />
      <Group mb="sm">
        <Select
          aria-label="Filter by status"
          size="sm"
          w={200}
          value={status}
          onChange={(v) => {
            setStatus((v as ComplaintStatus | 'ALL') ?? 'ALL');
            setPage(0);
          }}
          data={STATUS_FILTERS.map((s) => ({ value: s, label: s === 'ALL' ? 'All statuses' : label(s) }))}
          allowDeselect={false}
        />
      </Group>
      <Paper withBorder radius="md">
        <QueryState isLoading={complaints.isLoading} error={complaints.error} empty={rows.length === 0}
          emptyText={status === 'ALL' ? "You haven't raised any requests. We're here if you need us." : 'No requests with this status.'}>
          <Table.ScrollContainer minWidth={680}>
            <Table verticalSpacing="sm" highlightOnHover>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Ticket</Table.Th>
                  <Table.Th>Subject</Table.Th>
                  <Table.Th>Category</Table.Th>
                  <Table.Th>Raised</Table.Th>
                  <Table.Th>Status</Table.Th>
                  <Table.Th />
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {rows.map((c) => (
                  <Table.Tr key={c.id} style={{ cursor: 'pointer' }} onClick={() => setOpenId(c.id)}>
                    <Table.Td><Text size="sm" ff="monospace">{c.ticketNumber}</Text></Table.Td>
                    <Table.Td maw={320}><Text size="sm" fw={500} truncate="end">{c.subject}</Text></Table.Td>
                    <Table.Td>{label(c.category)}</Table.Td>
                    <Table.Td>{fromNow(c.createdAt)}</Table.Td>
                    <Table.Td>
                      <Group gap={4}>
                        <StatusBadge value={c.status} />
                        {c.status === 'RESOLVED' && <Badge size="xs" color="teal" variant="dot">Awaiting your reply</Badge>}
                      </Group>
                    </Table.Td>
                    <Table.Td ta="right">
                      <Button size="xs" variant="subtle" aria-label={`Open ticket ${c.ticketNumber}`}
                        onClick={(e) => {
                          e.stopPropagation();
                          setOpenId(c.id);
                        }}>
                        Open
                      </Button>
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
        </QueryState>
      </Paper>
      <Pager page={complaints.data} onChange={setPage} />

      <NewComplaintModal opened={creating} onClose={() => setCreating(false)} onCreated={(c) => {
        setCreating(false);
        setOpenId(c.id);
      }} />
      <Modal opened={openId != null} onClose={() => setOpenId(null)} title="Support request" size="lg" centered>
        {openId != null && <ComplaintDetail key={openId} id={openId} />}
      </Modal>
    </>
  );
}
