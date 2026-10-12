import {
  Accordion, Anchor, Badge, Group, List, Progress, SegmentedControl, Select, SimpleGrid, Stack, Table, Text, Tooltip,
} from '@mantine/core';
import { IconAlertTriangle, IconShieldCheck, IconUsers, IconUserExclamation } from '@tabler/icons-react';
import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Pager } from '@/components/Pager';
import { QueryState } from '@/components/QueryState';
import { StatCard } from '@/components/StatCard';
import { StatusBadge } from '@/components/StatusBadge';
import { date, fromNow, number, pct } from '@/lib/format';
import { MetaLine } from './components';
import type { AnalyticsFilters, ChurnBand, ChurnRiskResponse } from './types';
import { useAnalytics } from './useAnalytics';

const BAND_COLOR: Record<ChurnBand, string> = { HIGH: 'red', MEDIUM: 'orange', LOW: 'teal' };

export function ChurnRiskTab({ filters, branchNames }: { filters: AnalyticsFilters; branchNames: Record<number, string> }) {
  const navigate = useNavigate();
  const [minBand, setMinBand] = useState<ChurnBand>('MEDIUM');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState('25');
  const [lastBranch, setLastBranch] = useState(filters.branchId);
  // reset paging when the branch filter changes (adjusting state during render, no effect needed)
  if (lastBranch !== filters.branchId) {
    setLastBranch(filters.branchId);
    setPage(0);
  }
  // churn risk is scored as of today: the date range and granularity do not apply
  const q = useAnalytics<ChurnRiskResponse>('/analytics/members/churn-risk', {
    branchId: filters.branchId ?? undefined, minBand, page, size,
  });
  const d = q.data;
  const counts = d?.bandCounts ?? {};
  const scored = d?.scoredMembers ?? 0;

  return (
    <Stack gap="md">
      <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }}>
        <StatCard label="Scored members" value={d ? number(scored) : '…'} icon={<IconUsers size={20} />} color="blue"
          hint="ACTIVE or GRACE subscription today" />
        <StatCard label="High risk" value={d ? number(counts.HIGH ?? 0) : '…'} icon={<IconUserExclamation size={20} />} color="red"
          hint={`score ≥ 60 · ${pct(scored ? (counts.HIGH ?? 0) / scored : null)}`} />
        <StatCard label="Medium risk" value={d ? number(counts.MEDIUM ?? 0) : '…'} icon={<IconAlertTriangle size={20} />} color="orange"
          hint={`score 30–59 · ${pct(scored ? (counts.MEDIUM ?? 0) / scored : null)}`} />
        <StatCard label="Low risk" value={d ? number(counts.LOW ?? 0) : '…'} icon={<IconShieldCheck size={20} />} color="teal"
          hint={`score < 30 · ${pct(scored ? (counts.LOW ?? 0) / scored : null)}`} />
      </SimpleGrid>

      <Group justify="space-between" wrap="wrap">
        <Group gap="sm">
          <Text size="sm" fw={500}>Minimum band</Text>
          <SegmentedControl
            size="xs"
            value={minBand}
            onChange={(v) => { setMinBand(v as ChurnBand); setPage(0); }}
            data={[
              { value: 'HIGH', label: 'High' },
              { value: 'MEDIUM', label: 'Medium +' },
              { value: 'LOW', label: 'All' },
            ]}
          />
        </Group>
        <Group gap="sm">
          <Text size="xs" c="dimmed">Scored as of today · date range does not apply</Text>
          <Select size="xs" w={110} value={size} allowDeselect={false} aria-label="Page size"
            onChange={(v) => { if (v) { setSize(v); setPage(0); } }}
            data={['10', '25', '50', '100'].map((n) => ({ value: n, label: `${n} / page` }))} />
        </Group>
      </Group>

      <QueryState isLoading={q.isLoading} error={q.error} empty={d?.items.length === 0}
        emptyText="No members at or above this risk band.">
        {d && (
          <Table.ScrollContainer minWidth={1050}>
            <Table verticalSpacing="sm" highlightOnHover style={{ opacity: q.isPlaceholderData ? 0.6 : 1 }}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Member</Table.Th>
                  <Table.Th w={170}>Risk score</Table.Th>
                  <Table.Th>Why</Table.Th>
                  <Table.Th>Last visit</Table.Th>
                  <Table.Th ta="right">Visits (30d)</Table.Th>
                  <Table.Th>Subscription</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {d.items.map((m) => (
                  <Table.Tr key={m.memberId} style={{ cursor: 'pointer' }} onClick={() => navigate(`/members/${m.memberId}`)}>
                    <Table.Td>
                      <Anchor component={Link} to={`/members/${m.memberId}`} fw={600} size="sm" onClick={(e) => e.stopPropagation()}>
                        {m.name ?? `Member ${m.memberId}`}
                      </Anchor>
                      <Text size="xs" c="dimmed">{m.memberCode ?? '—'} · {m.branchName ?? (m.branchId != null ? branchNames[m.branchId] : null) ?? '—'}</Text>
                    </Table.Td>
                    <Table.Td>
                      <Group gap={8} wrap="nowrap">
                        <Progress value={m.score} color={BAND_COLOR[m.band] ?? 'gray'} size="lg" style={{ flex: 1 }} />
                        <Text fw={700} size="sm" w={26} ta="right">{m.score}</Text>
                      </Group>
                      <StatusBadge value={m.band} mt={6} size="sm" />
                    </Table.Td>
                    <Table.Td maw={380}>
                      <List size="xs" spacing={2} listStyleType="none">
                        {m.reasons.map((r) => (
                          <List.Item key={r.code}>
                            <Group gap={6} wrap="nowrap" align="flex-start">
                              <Badge size="xs" variant="outline" color={BAND_COLOR[m.band] ?? 'gray'} style={{ flexShrink: 0 }}>+{r.points}</Badge>
                              <Tooltip label={r.code} withArrow>
                                <Text size="xs">{r.description}</Text>
                              </Tooltip>
                            </Group>
                          </List.Item>
                        ))}
                        {m.reasons.length === 0 && <Text size="xs" c="dimmed">No rules triggered</Text>}
                      </List>
                    </Table.Td>
                    <Table.Td>
                      <Text size="sm">{m.lastVisit ? date(m.lastVisit) : 'Never'}</Text>
                      <Text size="xs" c={m.daysSinceLastVisit >= 30 ? 'red' : m.daysSinceLastVisit >= 14 ? 'orange' : 'dimmed'}>
                        {m.lastVisit ? fromNow(m.lastVisit) : `${m.daysSinceLastVisit} days since start`}
                      </Text>
                    </Table.Td>
                    <Table.Td ta="right">{number(m.visitsLast30Days)}</Table.Td>
                    <Table.Td>
                      <Group gap={6} wrap="nowrap">
                        <Text size="sm">{m.planName ?? '—'}</Text>
                        <StatusBadge value={m.subscriptionStatus} size="xs" />
                      </Group>
                      <Text size="xs" c="dimmed">ends {date(m.subscriptionEndDate)} ({fromNow(m.subscriptionEndDate)})</Text>
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
        )}
      </QueryState>
      {d && (
        <Pager
          page={{ content: d.items, page: d.page, size: d.size, totalElements: d.totalItems, totalPages: d.totalPages, last: d.page >= d.totalPages - 1 }}
          onChange={setPage}
        />
      )}

      {d && (
        <Accordion variant="contained">
          <Accordion.Item value="rules">
            <Accordion.Control>How the risk score is calculated</Accordion.Control>
            <Accordion.Panel>
              <Text size="sm" c="dimmed" mb="sm">
                Rule-based, no machine learning. Score = sum of the triggered rules' points, capped at 100. HIGH ≥ 60, MEDIUM ≥ 30,
                otherwise LOW. Members who never visited are measured from their current subscription's start date.
              </Text>
              <Table verticalSpacing={4}>
                <Table.Tbody>
                  {d.rules.map((r) => (
                    <Table.Tr key={r.code}>
                      <Table.Td w={80}><Badge variant="light" color="gray">+{r.weight}</Badge></Table.Td>
                      <Table.Td><Text size="sm">{r.description}</Text></Table.Td>
                      <Table.Td><Text size="xs" c="dimmed" ff="monospace">{r.code}</Text></Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </Accordion.Panel>
          </Accordion.Item>
        </Accordion>
      )}
      <MetaLine meta={d?.meta} branchNames={branchNames} note="scores as of today" />
    </Stack>
  );
}
