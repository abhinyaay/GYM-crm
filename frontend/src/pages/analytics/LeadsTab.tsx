import { BarChart } from '@mantine/charts';
import { Group, Progress, SimpleGrid, Stack, Table, Text, Tooltip } from '@mantine/core';
import { IconClockHour4, IconGift, IconTargetArrow, IconTrophy } from '@tabler/icons-react';
import dayjs from 'dayjs';
import { LEAD_STATUS_COLOR, type LeadStatus } from '@/api/leads';
import { QueryState } from '@/components/QueryState';
import { StatCard } from '@/components/StatCard';
import { label, number, pct } from '@/lib/format';
import { ChartCard, Definition, DonutWithLegend, EmptyChart, MetaLine, PALETTE, ShareBar } from './components';
import type { LeadsAnalyticsResponse } from './leadsTypes';
import type { AnalyticsFilters } from './types';
import { filterQuery, useAnalytics } from './useAnalytics';

const statusColor = (s: string) => `${LEAD_STATUS_COLOR[s as LeadStatus] ?? 'gray'}.6`;

export function LeadsTab({ filters, branchNames }: { filters: AnalyticsFilters; branchNames: Record<number, string> }) {
  const q = useAnalytics<LeadsAnalyticsResponse>('/analytics/leads', filterQuery(filters));
  const d = q.data;
  const s = d?.summary;
  const sla = d?.followUpSla;
  const maxStage = Math.max(1, ...(d?.funnel.stages.map((st) => st.count) ?? [0]));

  return (
    <QueryState isLoading={q.isLoading} error={q.error}>
      {d && s && sla && (
        <Stack gap="md">
          <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }}>
            <StatCard label="Leads" value={number(s.totalLeads)} icon={<IconTargetArrow size={20} />} color="blue"
              hint={`${number(s.openLeads)} still open · ${number(s.unassignedLeads)} unassigned`} />
            <StatCard label="Conversion rate" value={pct(s.conversionRate)} icon={<IconTrophy size={20} />}
              hint={`${number(s.conversions)} converted · median ${s.medianDaysToConvert == null ? '—' : `${s.medianDaysToConvert} days`}`} />
            <StatCard label="Trial → paid" value={pct(s.trialToPaidRate)} icon={<IconGift size={20} />} color="orange"
              hint={`${number(s.trialConversions)} of ${number(s.trialLeads)} trials converted`} />
            <StatCard label="Contacted within 24h" value={pct(sla.rate)} icon={<IconClockHour4 size={20} />}
              color={(sla.rate ?? 0) >= 0.8 ? 'teal' : 'red'}
              hint={`median first contact ${sla.medianHoursToFirstContact == null ? '—' : `${sla.medianHoursToFirstContact} h`}`} />
          </SimpleGrid>

          {s.totalLeads === 0 ? (
            <ChartCard title="Pipeline"><EmptyChart h={140} text="No leads were created in this period." /></ChartCard>
          ) : (
            <>
              <SimpleGrid cols={{ base: 1, lg: 2 }}>
                <ChartCard title="Funnel" description="Where the leads created in this period stand today.">
                  <Stack gap={8}>
                    {d.funnel.stages.map((st) => (
                      <div key={st.status}>
                        <Group justify="space-between" mb={2}>
                          <Text size="sm">{label(st.status)}</Text>
                          <Text size="sm" fw={600}>{number(st.count)} <Text span size="xs" c="dimmed">{pct(st.share)}</Text></Text>
                        </Group>
                        <Progress value={(st.count / maxStage) * 100} color={statusColor(st.status)} size="lg" radius="sm" />
                      </div>
                    ))}
                  </Stack>
                </ChartCard>
                <ChartCard title="Leads per week" description="New leads by creation week; conversions by conversion week.">
                  <BarChart h={260} withLegend gridAxis="y"
                    data={d.weeklyTrend.map((w) => ({ week: dayjs(w.weekStart).format('DD MMM'), leads: w.leads, conversions: w.conversions }))}
                    dataKey="week"
                    series={[{ name: 'leads', color: 'blue.5', label: 'Leads' }, { name: 'conversions', color: 'teal.6', label: 'Conversions' }]} />
                </ChartCard>
              </SimpleGrid>

              <SimpleGrid cols={{ base: 1, lg: 2 }}>
                <ChartCard title="Conversion by source">
                  {d.bySource.length === 0 ? <EmptyChart h={120} /> : (
                    <Table verticalSpacing={6}>
                      <Table.Thead>
                        <Table.Tr>
                          <Table.Th>Source</Table.Th>
                          <Table.Th ta="right">Leads</Table.Th>
                          <Table.Th ta="right">Converted</Table.Th>
                          <Table.Th w={170}>Rate</Table.Th>
                        </Table.Tr>
                      </Table.Thead>
                      <Table.Tbody>
                        {d.bySource.map((r) => (
                          <Table.Tr key={r.source}>
                            <Table.Td>{label(r.source)}</Table.Td>
                            <Table.Td ta="right">{number(r.leads)}</Table.Td>
                            <Table.Td ta="right">{number(r.conversions)}</Table.Td>
                            <Table.Td><ShareBar value={r.conversionRate} /></Table.Td>
                          </Table.Tr>
                        ))}
                      </Table.Tbody>
                    </Table>
                  )}
                </ChartCard>
                <ChartCard title="Why leads were lost">
                  <DonutWithLegend chartLabel={`${number(s.lostLeads)} lost`}
                    data={d.lostReasons.map((r, i) => ({
                      name: label(r.reason), value: r.count, color: PALETTE[i % PALETTE.length], hint: pct(r.share),
                    }))} />
                </ChartCard>
              </SimpleGrid>

              <SimpleGrid cols={{ base: 1, lg: 2 }}>
                <ChartCard title="Owner leaderboard" description="Leads created in the period, by current owner.">
                  {d.ownerLeaderboard.length === 0 ? <EmptyChart h={120} text="No assigned leads." /> : (
                    <Table.ScrollContainer minWidth={420}>
                      <Table verticalSpacing={6} striped>
                        <Table.Thead>
                          <Table.Tr>
                            <Table.Th>#</Table.Th>
                            <Table.Th>Owner</Table.Th>
                            <Table.Th ta="right">Leads</Table.Th>
                            <Table.Th ta="right">Converted</Table.Th>
                            <Table.Th w={150}>Rate</Table.Th>
                          </Table.Tr>
                        </Table.Thead>
                        <Table.Tbody>
                          {d.ownerLeaderboard.map((o, i) => (
                            <Table.Tr key={o.ownerUserId}>
                              <Table.Td>{i + 1}</Table.Td>
                              <Table.Td>{o.ownerName ?? `User ${o.ownerUserId}`}</Table.Td>
                              <Table.Td ta="right">{number(o.leadsHandled)}</Table.Td>
                              <Table.Td ta="right">{number(o.conversions)}</Table.Td>
                              <Table.Td><ShareBar value={o.conversionRate} color="blue" /></Table.Td>
                            </Table.Tr>
                          ))}
                        </Table.Tbody>
                      </Table>
                    </Table.ScrollContainer>
                  )}
                </ChartCard>
                <ChartCard title="Follow-up SLA" description={`First real contact within ${sla.slaHours} hours of the enquiry.`}>
                  <Stack gap="sm">
                    <Tooltip label={`${sla.contactedWithin24h} of ${sla.totalLeads} leads`}>
                      <Progress.Root size={26} radius="sm">
                        <Progress.Section value={sla.totalLeads ? (sla.contactedWithin24h / sla.totalLeads) * 100 : 0} color="teal">
                          <Progress.Label>On time</Progress.Label>
                        </Progress.Section>
                        <Progress.Section
                          value={sla.totalLeads ? ((sla.contactedLeads - sla.contactedWithin24h) / sla.totalLeads) * 100 : 0} color="orange">
                          <Progress.Label>Late</Progress.Label>
                        </Progress.Section>
                        <Progress.Section
                          value={sla.totalLeads ? ((sla.totalLeads - sla.contactedLeads) / sla.totalLeads) * 100 : 0} color="gray.4">
                          <Progress.Label>Not yet</Progress.Label>
                        </Progress.Section>
                      </Progress.Root>
                    </Tooltip>
                    <SimpleGrid cols={3}>
                      <Stat k="Within 24h" v={number(sla.contactedWithin24h)} />
                      <Stat k="Contacted later" v={number(sla.contactedLeads - sla.contactedWithin24h)} />
                      <Stat k="Never contacted" v={number(sla.totalLeads - sla.contactedLeads)} />
                    </SimpleGrid>
                  </Stack>
                </ChartCard>
              </SimpleGrid>
            </>
          )}

          <Definition>
            Leads are those created in the date range (business timezone); statuses are current. Conversion rate = CONVERTED ÷
            all leads; trial → paid = converted ÷ leads that started a trial. Contact = the first call, email, WhatsApp, SMS
            or visit logged on the lead; leads still waiting count against the SLA.
          </Definition>
          <MetaLine meta={d.meta} branchNames={branchNames} />
        </Stack>
      )}
    </QueryState>
  );
}

function Stat({ k, v }: { k: string; v: string }) {
  return (
    <div>
      <Text size="xs" c="dimmed">{k}</Text>
      <Text fw={700}>{v}</Text>
    </div>
  );
}
