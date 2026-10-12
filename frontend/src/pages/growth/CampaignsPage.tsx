import {
  ActionIcon, Alert, Badge, Button, Card, Divider, Drawer, Group, Loader, Menu, NumberInput, Paper, SegmentedControl,
  Select, SimpleGrid, Stack, Stepper, Switch, Table, Text, Textarea, TextInput, Title, Tooltip,
} from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import {
  IconAlertTriangle, IconArchive, IconChartBar, IconDots, IconEdit, IconPlayerPause, IconPlayerPlay, IconPlus,
  IconSend, IconSpeakerphone, IconTicket, IconUsers,
} from '@tabler/icons-react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { api, ApiError } from '@/api/client';
import {
  CAMPAIGN_STATUS_COLOR, PLACEHOLDERS, SEGMENTS, renderTemplate, unknownPlaceholders,
  type Campaign, type CampaignPreview, type CampaignRecipient, type CampaignRequest, type CampaignRun,
  type CampaignSchedule, type CampaignSegment, type ChurnBand, type DiscountType,
} from '@/api/growth';
import type { Page } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { hasAnyRole, ORG_WIDE } from '@/auth/roles';
import { BranchSelect } from '@/components/BranchSelect';
import { PageHeader } from '@/components/PageHeader';
import { Pager } from '@/components/Pager';
import { QueryState } from '@/components/QueryState';
import { StatCard } from '@/components/StatCard';
import { StatusBadge } from '@/components/StatusBadge';
import { date, dateTime, fromNow, money, number, pct, WEEKDAYS } from '@/lib/format';
import { errorMessage } from '@/lib/notify';
import { useApiMutation, useApiQuery } from '@/lib/useApi';

const STATUS_FILTERS = [
  { value: '', label: 'All' }, { value: 'ACTIVE', label: 'Scheduled' }, { value: 'DRAFT', label: 'One-off' },
  { value: 'PAUSED', label: 'Paused' }, { value: 'ARCHIVED', label: 'Archived' },
];

const scheduleText = (c: Campaign) => (c.schedule === 'NONE' ? 'One-off'
  : c.schedule === 'DAILY' ? 'Daily' : `Weekly on ${WEEKDAYS[(c.scheduleDayOfWeek ?? 1) - 1]}`);

/** Managers: retention / win-back campaigns with audience preview, scheduling and conversion stats. */
export default function CampaignsPage() {
  const { user } = useAuth();
  const orgWide = hasAnyRole(user, ORG_WIDE);
  const [branchId, setBranchId] = useState<number | null>(null);
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const [editing, setEditing] = useState<Campaign | 'new' | null>(null);
  const [viewing, setViewing] = useState<number | null>(null);
  const q = useApiQuery<Page<Campaign>>('/api/v1/campaigns', {
    branchId: branchId ?? undefined, status: status || undefined, page, size: 20,
  });
  const rows = useMemo(() => q.data?.content ?? [], [q.data]);
  const totals = useMemo(() => rows.reduce((t, c) => ({
    active: t.active + (c.status === 'ACTIVE' ? 1 : 0), sent: t.sent + c.stats.sent,
    converted: t.converted + c.stats.converted, revenue: t.revenue + c.stats.revenue,
  }), { active: 0, sent: 0, converted: 0, revenue: 0 }), [rows]);

  return (
    <>
      <PageHeader
        title="Retention campaigns"
        subtitle="Win back lapsed members, nudge renewals and re-engage at-risk members with personal offers"
        actions={
          <>
            <BranchSelect allowAll value={branchId} onChange={(b) => { setBranchId(b); setPage(0); }}
              w={{ base: '100%', sm: 240 }} clearable={!orgWide} placeholder={orgWide ? 'Select branch' : 'All my branches'} />
            <Button leftSection={<IconPlus size={16} />} onClick={() => setEditing('new')}>New campaign</Button>
          </>
        }
      />
      <SimpleGrid cols={{ base: 2, md: 4 }} mb="md">
        <StatCard label="Scheduled campaigns" value={number(totals.active)} icon={<IconSpeakerphone size={20} />} color="blue" />
        <StatCard label="Messages sent" value={number(totals.sent)} icon={<IconSend size={20} />} color="indigo" />
        <StatCard label="Conversions" value={number(totals.converted)} icon={<IconUsers size={20} />} color="teal"
          hint={totals.sent ? `${pct(totals.converted / totals.sent)} of messages` : undefined} />
        <StatCard label="Attributed revenue" value={money(totals.revenue)} icon={<IconChartBar size={20} />} color="teal" />
      </SimpleGrid>

      <Group justify="space-between" mb="sm" wrap="wrap">
        <Title order={4}>Campaigns</Title>
        <SegmentedControl size="xs" value={status} data={STATUS_FILTERS} onChange={(v) => { setStatus(v); setPage(0); }} />
      </Group>
      <QueryState isLoading={q.isLoading} error={q.error} empty={rows.length === 0}
        emptyText="No campaigns yet. Create one to reach members before they leave.">
        <Stack gap="sm">
          {rows.map((c) => (
            <CampaignCard key={c.id} c={c} onOpen={() => setViewing(c.id)} onEdit={() => setEditing(c)} />
          ))}
        </Stack>
        <Pager page={q.data} onChange={setPage} />
      </QueryState>

      {editing && (
        <CampaignWizard campaign={editing === 'new' ? null : editing} defaultBranchId={branchId
          ?? (user?.branchIds.length === 1 ? user.branchIds[0] : null)} onClose={() => setEditing(null)}
        onSaved={(c) => { setEditing(null); setViewing(c.id); }} />
      )}
      <CampaignDrawer id={viewing} onClose={() => setViewing(null)}
        onEdit={(c) => { setViewing(null); setEditing(c); }} />
    </>
  );
}

// ------------------------------------------------------------------ list card & actions

function useCampaignActions() {
  const invalidate = ['/api/v1/campaigns'];
  const run = useApiMutation<number, CampaignRun>({
    request: (id) => ({ path: `/api/v1/campaigns/${id}/run` }), invalidate,
    successMessage: (r) => (r.status === 'COMPLETED'
      ? `Sent ${r.sent} message${r.sent === 1 ? '' : 's'}${r.skipped ? ` · ${r.skipped} skipped (cooldown)` : ''}`
      : `Run failed: ${r.error ?? 'unknown error'}`),
  });
  const action = useApiMutation<{ id: number; action: 'activate' | 'pause' | 'archive' }, Campaign>({
    request: ({ id, action: a }) => ({ path: `/api/v1/campaigns/${id}/${a}` }), invalidate,
    successMessage: (c) => (c.status === 'ACTIVE' ? `Scheduled - next run ${dateTime(c.nextRunAt)}`
      : c.status === 'PAUSED' ? 'Campaign paused' : 'Campaign archived'),
  });
  return { run, action };
}

function CampaignCard({ c, onOpen, onEdit }: { c: Campaign; onOpen: () => void; onEdit: () => void }) {
  const { run, action } = useCampaignActions();
  const archived = c.status === 'ARCHIVED';
  return (
    <Card withBorder radius="md" p="md">
      <Group justify="space-between" align="flex-start" wrap="nowrap" gap="sm">
        <Stack gap={4} style={{ minWidth: 0, cursor: 'pointer' }} onClick={onOpen}>
          <Group gap="xs" wrap="wrap">
            <Text fw={600} size="lg">{c.name}</Text>
            <StatusBadge value={c.status} color={CAMPAIGN_STATUS_COLOR[c.status]} />
            {c.offerEnabled && (
              <Badge variant="light" color="pink" leftSection={<IconTicket size={12} />} radius="sm">
                {c.offerDiscountType === 'PERCENTAGE' ? `${c.offerValue}% offer` : `${money(c.offerValue)} offer`}
              </Badge>
            )}
          </Group>
          <Text size="sm" c="dimmed">
            {c.segmentDescription} · {c.branchName ?? 'All branches'} · {scheduleText(c)}
            {c.nextRunAt && c.status === 'ACTIVE' ? ` · next ${dateTime(c.nextRunAt)}` : ''}
            {c.lastRunAt ? ` · last sent ${fromNow(c.lastRunAt)}` : ' · never sent'}
          </Text>
        </Stack>
        <Group gap="xs" wrap="nowrap">
          {!archived && (
            <Button size="xs" variant="light" leftSection={<IconSend size={14} />} loading={run.isPending}
              onClick={() => run.mutate(c.id)}>Run now</Button>
          )}
          <Menu position="bottom-end" withinPortal>
            <Menu.Target>
              <ActionIcon variant="subtle" aria-label="Campaign actions"><IconDots size={18} /></ActionIcon>
            </Menu.Target>
            <Menu.Dropdown>
              <Menu.Item leftSection={<IconChartBar size={14} />} onClick={onOpen}>Results & recipients</Menu.Item>
              {!archived && <Menu.Item leftSection={<IconEdit size={14} />} onClick={onEdit}>Edit</Menu.Item>}
              {(c.status === 'DRAFT' || c.status === 'PAUSED') && c.schedule !== 'NONE' && (
                <Menu.Item leftSection={<IconPlayerPlay size={14} />}
                  onClick={() => action.mutate({ id: c.id, action: 'activate' })}>Start schedule</Menu.Item>
              )}
              {c.status === 'ACTIVE' && (
                <Menu.Item leftSection={<IconPlayerPause size={14} />}
                  onClick={() => action.mutate({ id: c.id, action: 'pause' })}>Pause</Menu.Item>
              )}
              {!archived && (
                <Menu.Item color="red" leftSection={<IconArchive size={14} />}
                  onClick={() => action.mutate({ id: c.id, action: 'archive' })}>Archive</Menu.Item>
              )}
            </Menu.Dropdown>
          </Menu>
        </Group>
      </Group>
      <SimpleGrid cols={{ base: 2, sm: 5 }} mt="sm" spacing="xs">
        <Metric label="Sent" value={number(c.stats.sent)} />
        <Metric label="Converted" value={`${number(c.stats.converted)} (${pct(c.stats.conversionRate)})`} />
        <Metric label="Revenue" value={money(c.stats.revenue)} />
        <Metric label="Offers used" value={c.offerEnabled ? `${number(c.stats.offersRedeemed)} / ${number(c.stats.offersIssued)}` : '—'} />
        <Metric label="Runs" value={number(c.stats.runs)} />
      </SimpleGrid>
    </Card>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <Text size="xs" c="dimmed" tt="uppercase" fw={600}>{label}</Text>
      <Text fw={600}>{value}</Text>
    </div>
  );
}

// ------------------------------------------------------------------ detail drawer

function CampaignDrawer({ id, onClose, onEdit }: { id: number | null; onClose: () => void; onEdit: (c: Campaign) => void }) {
  const c = useApiQuery<Campaign>(id ? `/api/v1/campaigns/${id}` : null);
  const runs = useApiQuery<CampaignRun[]>(id ? `/api/v1/campaigns/${id}/runs` : null);
  const [converted, setConverted] = useState('');
  const [page, setPage] = useState(0);
  const recipients = useApiQuery<Page<CampaignRecipient>>(id ? `/api/v1/campaigns/${id}/recipients` : null,
    { converted: converted || undefined, page, size: 15 });
  const { run } = useCampaignActions();
  const d = c.data;
  return (
    <Drawer opened={id != null} onClose={onClose} position="right" size="xl" title={d?.name ?? 'Campaign'}>
      <QueryState isLoading={c.isLoading} error={c.error}>
        {d && (
          <Stack>
            <Group justify="space-between" wrap="wrap">
              <Group gap="xs">
                <StatusBadge value={d.status} color={CAMPAIGN_STATUS_COLOR[d.status]} />
                <Text size="sm" c="dimmed">{d.segmentDescription} · {d.branchName ?? 'All branches'} · {scheduleText(d)}</Text>
              </Group>
              <Group gap="xs">
                {d.status !== 'ARCHIVED' && <Button size="xs" variant="default" leftSection={<IconEdit size={14} />}
                  onClick={() => onEdit(d)}>Edit</Button>}
                {d.status !== 'ARCHIVED' && <Button size="xs" leftSection={<IconSend size={14} />} loading={run.isPending}
                  onClick={() => run.mutate(d.id)}>Run now</Button>}
              </Group>
            </Group>
            {d.description && <Text size="sm">{d.description}</Text>}
            <SimpleGrid cols={{ base: 2, sm: 4 }}>
              <StatCard label="Messages sent" value={number(d.stats.sent)} hint={`${number(d.stats.uniqueMembers)} members`} />
              <StatCard label="Converted" value={number(d.stats.converted)} hint={`${pct(d.stats.conversionRate)} within ${d.attributionDays} days`}
                color="teal" />
              <StatCard label="Revenue" value={money(d.stats.revenue)} color="teal" />
              <StatCard label="Offer codes used" value={d.offerEnabled ? `${number(d.stats.offersRedeemed)}/${number(d.stats.offersIssued)}` : '—'}
                color="pink" />
            </SimpleGrid>
            <Paper withBorder p="sm" radius="md">
              <Text size="xs" c="dimmed" tt="uppercase" fw={600}>Message</Text>
              <Text fw={600}>{d.titleTemplate}</Text>
              <Text size="sm" style={{ whiteSpace: 'pre-line' }}>{d.messageTemplate}</Text>
              <Text size="xs" c="dimmed" mt={4}>
                Cooldown {d.cooldownDays} days · attribution window {d.attributionDays} days
                {d.offerEnabled ? ` · personal code ${d.offerDiscountType === 'PERCENTAGE' ? `${d.offerValue}%` : money(d.offerValue)} off, valid ${d.offerValidDays} days` : ''}
              </Text>
            </Paper>

            <Title order={5}>Runs</Title>
            <QueryState isLoading={runs.isLoading} error={runs.error} empty={(runs.data ?? []).length === 0} emptyText="Not sent yet.">
              <Table.ScrollContainer minWidth={560}>
                <Table verticalSpacing={6}>
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th>When</Table.Th><Table.Th>Trigger</Table.Th><Table.Th>Status</Table.Th>
                      <Table.Th ta="right">Audience</Table.Th><Table.Th ta="right">Sent</Table.Th>
                      <Table.Th ta="right">Skipped</Table.Th><Table.Th ta="right">Failed</Table.Th>
                    </Table.Tr>
                  </Table.Thead>
                  <Table.Tbody>
                    {(runs.data ?? []).map((r) => (
                      <Table.Tr key={r.id}>
                        <Table.Td>{dateTime(r.startedAt)}</Table.Td>
                        <Table.Td>{r.triggerType === 'MANUAL' ? 'Manual' : 'Scheduled'}</Table.Td>
                        <Table.Td>
                          <StatusBadge value={r.status} color={r.status === 'FAILED' ? 'red' : r.status === 'RUNNING' ? 'blue' : 'teal'} />
                          {r.error && <Text size="xs" c="red" lineClamp={2}>{r.error}</Text>}
                        </Table.Td>
                        <Table.Td ta="right">{number(r.audienceSize)}</Table.Td>
                        <Table.Td ta="right">{number(r.sent)}</Table.Td>
                        <Table.Td ta="right">{number(r.skipped)}</Table.Td>
                        <Table.Td ta="right">{number(r.failed)}</Table.Td>
                      </Table.Tr>
                    ))}
                  </Table.Tbody>
                </Table>
              </Table.ScrollContainer>
            </QueryState>

            <Group justify="space-between">
              <Title order={5}>Recipients</Title>
              <SegmentedControl size="xs" value={converted} onChange={(v) => { setConverted(v); setPage(0); }}
                data={[{ value: '', label: 'All' }, { value: 'true', label: 'Converted' }, { value: 'false', label: 'Not yet' }]} />
            </Group>
            <QueryState isLoading={recipients.isLoading} error={recipients.error}
              empty={(recipients.data?.content ?? []).length === 0} emptyText="No recipients.">
              <Table.ScrollContainer minWidth={640}>
                <Table verticalSpacing={6} highlightOnHover>
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th>Member</Table.Th><Table.Th>Sent</Table.Th><Table.Th>Offer code</Table.Th>
                      <Table.Th>Converted</Table.Th><Table.Th ta="right">Revenue</Table.Th>
                    </Table.Tr>
                  </Table.Thead>
                  <Table.Tbody>
                    {(recipients.data?.content ?? []).map((r) => (
                      <Table.Tr key={r.id}>
                        <Table.Td>
                          <Text size="sm" fw={500}>{r.memberName}</Text>
                          <Text size="xs" c="dimmed">{r.memberCode} · {r.branchName}</Text>
                        </Table.Td>
                        <Table.Td>{date(r.sentAt)}</Table.Td>
                        <Table.Td>
                          {r.offerCode ? (
                            <Group gap={4} wrap="nowrap">
                              <Text ff="monospace" size="sm">{r.offerCode}</Text>
                              {r.offerRedeemed && <Badge size="xs" color="pink" variant="light">used</Badge>}
                            </Group>
                          ) : '—'}
                        </Table.Td>
                        <Table.Td>
                          {r.convertedAt ? <Badge color="teal" variant="light">{date(r.convertedAt)}</Badge>
                            : <Text size="xs" c="dimmed">window ends {date(r.attributionEndsAt)}</Text>}
                        </Table.Td>
                        <Table.Td ta="right">{r.conversionAmount != null ? money(r.conversionAmount) : '—'}</Table.Td>
                      </Table.Tr>
                    ))}
                  </Table.Tbody>
                </Table>
              </Table.ScrollContainer>
              <Pager page={recipients.data} onChange={setPage} />
            </QueryState>
          </Stack>
        )}
      </QueryState>
    </Drawer>
  );
}

// ------------------------------------------------------------------ create / edit wizard

interface FormState {
  name: string;
  description: string;
  branchId: number | null;
  segment: CampaignSegment;
  churnBand: ChurnBand;
  segmentDays: number;
  segmentDaysMax: number;
  titleTemplate: string;
  messageTemplate: string;
  offerEnabled: boolean;
  offerDiscountType: DiscountType;
  offerValue: number;
  offerValidDays: number;
  schedule: CampaignSchedule;
  scheduleDayOfWeek: number;
  cooldownDays: number;
  attributionDays: number;
}

const TEMPLATES: Record<CampaignSegment, { title: string; body: string }> = {
  EXPIRING_NO_AUTOPAY: { title: 'Your {planName} ends on {endDate}',
    body: 'Hi {firstName}, your {planName} ends on {endDate}. Renew in the app in a minute, or switch on autopay so you never miss a session.' },
  LAPSED: { title: 'We miss you, {firstName}!',
    body: 'Hi {firstName}, it has been a while since your {planName} ended on {endDate}. Come back this month - we would love to see you again.' },
  NO_RECENT_VISIT: { title: 'Haven’t seen you in a while, {firstName}',
    body: 'Hi {firstName}, your {planName} is waiting for you. Drop in this week - your trainer can set up a fresh plan to get you going.' },
  TRIAL_NOT_CONVERTED: { title: 'How was your trial, {firstName}?',
    body: 'Hi {firstName}, thanks for trying us out! Keep the momentum going with a full membership.' },
  CHURN_RISK: { title: 'We’re here for you, {firstName}',
    body: 'Hi {firstName}, we noticed you have not been in much lately. Reply or visit the desk and we will help you get back on track.' },
};

function initialState(c: Campaign | null, branchId: number | null): FormState {
  if (c) {
    return {
      name: c.name, description: c.description ?? '', branchId: c.branchId, segment: c.segment,
      churnBand: c.churnBand ?? 'HIGH', segmentDays: c.segmentDays ?? 7, segmentDaysMax: c.segmentDaysMax ?? 90,
      titleTemplate: c.titleTemplate, messageTemplate: c.messageTemplate, offerEnabled: c.offerEnabled,
      offerDiscountType: c.offerDiscountType ?? 'PERCENTAGE', offerValue: c.offerValue ?? 15,
      offerValidDays: c.offerValidDays ?? 14, schedule: c.schedule, scheduleDayOfWeek: c.scheduleDayOfWeek ?? 1,
      cooldownDays: c.cooldownDays, attributionDays: c.attributionDays,
    };
  }
  return {
    name: '', description: '', branchId, segment: 'EXPIRING_NO_AUTOPAY', churnBand: 'HIGH', segmentDays: 7,
    segmentDaysMax: 90, titleTemplate: TEMPLATES.EXPIRING_NO_AUTOPAY.title, messageTemplate: TEMPLATES.EXPIRING_NO_AUTOPAY.body,
    offerEnabled: false, offerDiscountType: 'PERCENTAGE', offerValue: 15, offerValidDays: 14, schedule: 'NONE',
    scheduleDayOfWeek: 1, cooldownDays: 14, attributionDays: 30,
  };
}

function toRequest(f: FormState): CampaignRequest {
  const seg = SEGMENTS.find((s) => s.value === f.segment)!;
  return {
    name: f.name.trim(), description: f.description.trim() || null, branchId: f.branchId, segment: f.segment,
    churnBand: seg.churnBand ? f.churnBand : null, segmentDays: seg.days ? f.segmentDays : null,
    segmentDaysMax: seg.daysMax ? f.segmentDaysMax : null, titleTemplate: f.titleTemplate, messageTemplate: f.messageTemplate,
    offerEnabled: f.offerEnabled, offerDiscountType: f.offerEnabled ? f.offerDiscountType : null,
    offerValue: f.offerEnabled ? f.offerValue : null, offerValidDays: f.offerEnabled ? f.offerValidDays : null,
    schedule: f.schedule, scheduleDayOfWeek: f.schedule === 'WEEKLY' ? f.scheduleDayOfWeek : null,
    cooldownDays: f.cooldownDays, attributionDays: f.attributionDays,
  };
}

function CampaignWizard({ campaign, defaultBranchId, onClose, onSaved }: {
  campaign: Campaign | null; defaultBranchId: number | null; onClose: () => void; onSaved: (c: Campaign) => void;
}) {
  const { user } = useAuth();
  const orgWide = hasAnyRole(user, ORG_WIDE);
  const [step, setStep] = useState(0);
  const [f, setF] = useState<FormState>(() => initialState(campaign, defaultBranchId));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const set = <K extends keyof FormState>(k: K, v: FormState[K]) => setF((s) => ({ ...s, [k]: v }));
  const seg = SEGMENTS.find((s) => s.value === f.segment)!;
  const request = useMemo(() => toRequest(f), [f]);
  const [debounced] = useDebouncedValue(request, 500);
  const preview = usePreview(debounced, f.branchId != null || orgWide);
  const unknown = [...unknownPlaceholders(f.titleTemplate), ...unknownPlaceholders(f.messageTemplate)];
  const offerWithoutCode = !f.offerEnabled && (f.messageTemplate.includes('{offerCode}') || f.titleTemplate.includes('{offerCode}'));

  const pickSegment = (value: CampaignSegment) => {
    const info = SEGMENTS.find((s) => s.value === value)!;
    setF((s) => ({
      ...s, segment: value, segmentDays: info.days?.default ?? s.segmentDays, segmentDaysMax: info.daysMax?.default ?? s.segmentDaysMax,
      // swap in the suggested copy only while the message is still a suggestion
      ...(Object.values(TEMPLATES).some((t) => t.body === s.messageTemplate)
        ? { titleTemplate: TEMPLATES[value].title, messageTemplate: TEMPLATES[value].body } : {}),
    }));
  };

  const canNext = [
    f.name.trim().length > 0 && (f.branchId != null || orgWide),
    f.titleTemplate.trim().length > 0 && f.messageTemplate.trim().length > 0 && unknown.length === 0 && !offerWithoutCode,
    !f.offerEnabled || (f.offerValue > 0 && (f.offerDiscountType !== 'PERCENTAGE' || f.offerValue <= 100) && f.offerValidDays > 0),
    true,
  ];

  const save = async (then: 'none' | 'run' | 'activate') => {
    setSaving(true);
    setError(null);
    try {
      let saved = campaign
        ? await api<Campaign>(`/api/v1/campaigns/${campaign.id}`, { method: 'PUT', body: request })
        : await api<Campaign>('/api/v1/campaigns', { method: 'POST', body: request });
      if (then === 'activate') saved = await api<Campaign>(`/api/v1/campaigns/${saved.id}/activate`, { method: 'POST' });
      if (then === 'run') await api<CampaignRun>(`/api/v1/campaigns/${saved.id}/run`, { method: 'POST' });
      onSaved(saved);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Drawer opened onClose={onClose} position="right" size="xl" title={campaign ? `Edit ${campaign.name}` : 'New campaign'}>
      <Stepper active={step} onStepClick={setStep} size="sm" allowNextStepsSelect={false}>
        <Stepper.Step label="Audience" description="Who gets it">
          <Stack mt="md">
            <TextInput label="Name" withAsterisk value={f.name} maxLength={120} onChange={(e) => set('name', e.currentTarget.value)}
              placeholder="e.g. Win back lapsed members" />
            <Textarea label="Description (optional)" value={f.description} maxLength={500} autosize minRows={1}
              onChange={(e) => set('description', e.currentTarget.value)} />
            <BranchSelect label="Branch" value={f.branchId} allowAll={orgWide} disabled={campaign != null}
              onChange={(b) => set('branchId', b)} description={orgWide ? 'Leave on "All branches" to target every branch' : undefined} />
            <Select label="Segment" allowDeselect={false} value={f.segment}
              data={SEGMENTS.map((s) => ({ value: s.value, label: s.label }))}
              onChange={(v) => v && pickSegment(v as CampaignSegment)} description={seg.description} />
            {seg.churnBand && (
              <SegmentedControl value={f.churnBand} onChange={(v) => set('churnBand', v as ChurnBand)}
                data={[{ value: 'HIGH', label: 'High risk' }, { value: 'MEDIUM', label: 'Medium or high' }]} />
            )}
            <Group grow>
              {seg.days && <NumberInput label={seg.days.label} min={1} max={365} value={f.segmentDays}
                onChange={(v) => set('segmentDays', Number(v) || 1)} />}
              {seg.daysMax && <NumberInput label={seg.daysMax.label} min={1} max={365} value={f.segmentDaysMax}
                onChange={(v) => set('segmentDaysMax', Number(v) || 1)}
                error={f.segmentDaysMax < f.segmentDays ? 'Must be at least the first value' : undefined} />}
            </Group>
            <AudienceSummary preview={preview} />
          </Stack>
        </Stepper.Step>

        <Stepper.Step label="Message" description="What they read">
          <Stack mt="md">
            <Group gap={6}>
              <Text size="sm" c="dimmed">Placeholders:</Text>
              {PLACEHOLDERS.map((p) => (
                <Tooltip key={p} label="Click to add to the message">
                  <Badge variant="light" style={{ cursor: 'pointer', textTransform: 'none' }}
                    color={p === '{offerCode}' && !f.offerEnabled ? 'gray' : 'blue'}
                    onClick={() => set('messageTemplate', `${f.messageTemplate}${f.messageTemplate.endsWith(' ') ? '' : ' '}${p}`)}>
                    {p}
                  </Badge>
                </Tooltip>
              ))}
            </Group>
            <TextInput label="Title" withAsterisk maxLength={200} value={f.titleTemplate}
              onChange={(e) => set('titleTemplate', e.currentTarget.value)} />
            <Textarea label="Message" withAsterisk autosize minRows={4} maxLength={1500} value={f.messageTemplate}
              onChange={(e) => set('messageTemplate', e.currentTarget.value)}
              description={`${f.messageTemplate.length}/1500 · use {{ and }} for literal braces`} />
            {unknown.length > 0 && (
              <Alert color="red" icon={<IconAlertTriangle size={16} />}>
                Unknown placeholder{unknown.length > 1 ? 's' : ''}: {unknown.map((u) => `{${u}}`).join(', ')}
              </Alert>
            )}
            {offerWithoutCode && (
              <Alert color="orange" icon={<IconAlertTriangle size={16} />}>
                {'{offerCode}'} needs a personal offer - enable it in the next step or remove the placeholder.
              </Alert>
            )}
            <LivePreview f={f} preview={preview} />
          </Stack>
        </Stepper.Step>

        <Stepper.Step label="Offer" description="Optional code">
          <Stack mt="md">
            <Switch label="Give each recipient a personal, single-use discount code" checked={f.offerEnabled}
              onChange={(e) => set('offerEnabled', e.currentTarget.checked)} />
            {f.offerEnabled && (
              <>
                <Group grow align="flex-start">
                  <Select label="Discount" allowDeselect={false} value={f.offerDiscountType}
                    data={[{ value: 'PERCENTAGE', label: 'Percentage off' }, { value: 'FIXED_AMOUNT', label: 'Rupees off' }]}
                    onChange={(v) => v && set('offerDiscountType', v as DiscountType)} />
                  <NumberInput label={f.offerDiscountType === 'PERCENTAGE' ? 'Percent' : 'Amount (₹)'} min={1}
                    max={f.offerDiscountType === 'PERCENTAGE' ? 100 : 100000} value={f.offerValue}
                    onChange={(v) => set('offerValue', Number(v) || 0)} />
                  <NumberInput label="Valid for (days)" min={1} max={365} value={f.offerValidDays}
                    onChange={(v) => set('offerValidDays', Number(v) || 1)} />
                </Group>
                <Text size="sm" c="dimmed">
                  Codes are generated per member at send time, work once, and only on that member’s invoice. If the message
                  has no {'{offerCode}'} the code is appended at the end.
                </Text>
              </>
            )}
          </Stack>
        </Stepper.Step>

        <Stepper.Step label="Schedule" description="When it goes out">
          <Stack mt="md">
            <SegmentedControl value={f.schedule} onChange={(v) => set('schedule', v as CampaignSchedule)}
              data={[{ value: 'NONE', label: 'One-off (run manually)' }, { value: 'DAILY', label: 'Daily' }, { value: 'WEEKLY', label: 'Weekly' }]} />
            {f.schedule === 'WEEKLY' && (
              <Select label="Day of the week" allowDeselect={false} value={String(f.scheduleDayOfWeek)}
                data={WEEKDAYS.map((d, i) => ({ value: String(i + 1), label: d }))}
                onChange={(v) => v && set('scheduleDayOfWeek', Number(v))} />
            )}
            <Group grow>
              <NumberInput label="Cooldown (days)" min={0} max={365} value={f.cooldownDays}
                onChange={(v) => set('cooldownDays', Number(v) || 0)}
                description="Don't message the same member again within" />
              <NumberInput label="Attribution window (days)" min={1} max={180} value={f.attributionDays}
                onChange={(v) => set('attributionDays', Number(v) || 1)}
                description="A purchase within this counts as converted" />
            </Group>
            <Divider label="Review" />
            <AudienceSummary preview={preview} />
            <LivePreview f={f} preview={preview} />
            {error && <Alert color="red" style={{ whiteSpace: 'pre-line' }}>{error}</Alert>}
          </Stack>
        </Stepper.Step>
      </Stepper>

      <Group justify="space-between" mt="xl">
        <Button variant="default" onClick={() => (step === 0 ? onClose() : setStep(step - 1))}>{step === 0 ? 'Cancel' : 'Back'}</Button>
        {step < 3 ? (
          <Button disabled={!canNext[step]} onClick={() => setStep(step + 1)}>Next</Button>
        ) : (
          <Group gap="xs">
            <Button variant="default" loading={saving} disabled={!canNext.every(Boolean)} onClick={() => save('none')}>
              {campaign ? 'Save' : 'Save as draft'}
            </Button>
            {f.schedule === 'NONE' ? (
              <Button loading={saving} disabled={!canNext.every(Boolean)} leftSection={<IconSend size={16} />}
                onClick={() => save('run')}>Save & send now</Button>
            ) : (
              <Button loading={saving} disabled={!canNext.every(Boolean)} leftSection={<IconPlayerPlay size={16} />}
                onClick={() => save('activate')}>Save & schedule</Button>
            )}
          </Group>
        )}
      </Group>
    </Drawer>
  );
}

interface PreviewState {
  data?: CampaignPreview;
  loading: boolean;
  error: unknown;
}

/** Debounced server preview of an unsaved definition (audience count + server-rendered samples). */
function usePreview(request: CampaignRequest, enabled: boolean): PreviewState {
  const valid = enabled && request.titleTemplate.trim() !== '' && request.messageTemplate.trim() !== ''
    && unknownPlaceholders(request.titleTemplate + request.messageTemplate).length === 0
    && (request.offerEnabled || !(request.titleTemplate + request.messageTemplate).includes('{offerCode}'))
    && (request.segmentDaysMax == null || request.segmentDays == null || request.segmentDaysMax >= request.segmentDays);
  const body = { ...request, name: 'Preview' };
  const q = useQuery<CampaignPreview, Error>({
    queryKey: ['campaign-preview', body],
    queryFn: () => api<CampaignPreview>('/api/v1/campaigns/preview', { method: 'POST', body }),
    enabled: valid,
    retry: false,
    placeholderData: keepPreviousData,
    staleTime: 30_000,
  });
  return { data: q.data, loading: q.isFetching, error: q.error };
}

function AudienceSummary({ preview }: { preview: PreviewState }) {
  if (preview.error) {
    const unavailable = preview.error instanceof ApiError && preview.error.code === 'DEPENDENCY_UNAVAILABLE';
    return (
      <Alert color={unavailable ? 'orange' : 'red'} icon={<IconAlertTriangle size={16} />}
        title={unavailable ? 'Churn-risk scores unavailable' : 'Preview failed'}>
        {errorMessage(preview.error)}
      </Alert>
    );
  }
  return (
    <Paper withBorder p="sm" radius="md">
      <Group gap="sm">
        <IconUsers size={20} />
        {preview.loading && !preview.data ? <Loader size="xs" /> : (
          <Text>
            <Text span fw={700} fz="lg">{number(preview.data?.audienceSize ?? 0)}</Text> member
            {preview.data?.audienceSize === 1 ? '' : 's'} match right now
            {preview.data?.truncated ? ' (capped)' : ''}
          </Text>
        )}
        {preview.loading && preview.data && <Loader size="xs" />}
      </Group>
      {preview.data?.warnings.map((w) => <Text key={w} size="xs" c="orange">{w}</Text>)}
    </Paper>
  );
}

/** Live message preview: the first matching member if there is one, otherwise example values. */
function LivePreview({ f, preview }: { f: FormState; preview: PreviewState }) {
  const sample = preview.data?.sample[0];
  const values = {
    firstName: sample?.name.split(' ')[0] ?? 'Asha',
    planName: sample?.planName ?? 'Gold Monthly',
    endDate: sample?.endDate ? date(sample.endDate) : date(new Date(Date.now() + 5 * 86400000).toISOString()),
    offerCode: f.offerEnabled ? 'WB7K2M9QXA' : '',
  };
  const body = renderTemplate(f.messageTemplate, values);
  const offerLine = f.offerEnabled && !f.messageTemplate.includes('{offerCode}')
    ? `\n\nYour personal code ${values.offerCode}: ${f.offerDiscountType === 'PERCENTAGE' ? `${f.offerValue}%` : money(f.offerValue)} off your next membership, valid until ${date(new Date(Date.now() + (f.offerValidDays - 1) * 86400000).toISOString())}.`
    : '';
  return (
    <Paper withBorder p="md" radius="md" bg="var(--mantine-color-default-hover)">
      <Text size="xs" c="dimmed" tt="uppercase" fw={600} mb={4}>
        Preview {sample ? `for ${sample.name}` : 'with example values'}
      </Text>
      <Text fw={600}>{renderTemplate(f.titleTemplate, values)}</Text>
      <Text size="sm" style={{ whiteSpace: 'pre-line' }}>{body}{offerLine}</Text>
    </Paper>
  );
}

