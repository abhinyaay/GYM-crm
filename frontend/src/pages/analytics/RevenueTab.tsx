import { AreaChart, BarChart } from '@mantine/charts';
import { SegmentedControl, SimpleGrid, Stack, Table } from '@mantine/core';
import { IconAlertTriangle, IconCash, IconReceiptRefund, IconUserDollar } from '@tabler/icons-react';
import { useState } from 'react';
import { QueryState } from '@/components/QueryState';
import { StatCard } from '@/components/StatCard';
import { label, money, moneyShort, number, pct } from '@/lib/format';
import { ChartCard, Definition, DonutWithLegend, EmptyChart, MetaLine, PALETTE, periodLabel, ShareBar } from './components';
import type { AnalyticsFilters, RevenueResponse } from './types';
import { filterQuery, useAnalytics } from './useAnalytics';

export function RevenueTab({ filters, branchNames }: { filters: AnalyticsFilters; branchNames: Record<number, string> }) {
  const q = useAnalytics<RevenueResponse>('/analytics/revenue', filterQuery(filters, true));
  const [chartType, setChartType] = useState<'area' | 'bar'>('area');
  const d = q.data;
  const s = d?.summary;

  const series = (d?.series ?? []).map((p) => ({
    label: periodLabel(p.period, d?.meta.granularity),
    gross: p.gross ?? 0,
    net: p.net ?? 0,
    refunds: p.refunds ?? 0,
    successful: p.successfulPayments,
    failed: p.failedPayments,
  }));
  const hasRevenue = series.some((p) => p.gross > 0 || p.refunds > 0);
  const revenueSeries = [
    { name: 'gross', color: 'blue.5', label: 'Gross' },
    { name: 'net', color: 'teal.6', label: 'Net' },
    { name: 'refunds', color: 'red.5', label: 'Refunds' },
  ];

  return (
    <QueryState isLoading={q.isLoading} error={q.error}>
      {d && s && (
        <Stack gap="md">
          <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }}>
            <StatCard label="Net revenue" value={moneyShort(s.net)} icon={<IconCash size={20} />}
              hint={`${money(s.gross)} gross · ${number(s.successfulPayments)} payments`} />
            <StatCard label="Refunds" value={moneyShort(s.refunds)} icon={<IconReceiptRefund size={20} />} color="red"
              hint={`${number(s.refundCount)} refund${s.refundCount === 1 ? '' : 's'} · ${pct(s.gross ? (s.refunds ?? 0) / s.gross : null)} of gross`} />
            <StatCard label="Failed-payment rate" value={pct(s.failedPaymentRate)} icon={<IconAlertTriangle size={20} />}
              color={s.failedPaymentRate != null && s.failedPaymentRate > 0.1 ? 'red' : 'orange'}
              hint={`${number(s.failedPayments)} failed of ${number(s.paymentAttempts)} attempts`} />
            <StatCard label="ARPU (paying member)" value={money(s.avgRevenuePerPayingMember)} icon={<IconUserDollar size={20} />} color="grape"
              hint={`${number(s.payingMembers)} paying members`} />
          </SimpleGrid>

          <ChartCard
            title="Revenue over time"
            description="Cash basis: gross = successful payments by processed date; refunds by their own processed date; net = gross − refunds."
            action={
              <SegmentedControl size="xs" value={chartType} onChange={(v) => setChartType(v as 'area' | 'bar')}
                data={[{ value: 'area', label: 'Area' }, { value: 'bar', label: 'Bars' }]} />
            }
          >
            {!hasRevenue ? (
              <EmptyChart h={300} text="No payments in this period." />
            ) : chartType === 'area' ? (
              <AreaChart h={300} data={series} dataKey="label" series={revenueSeries} curveType="monotone"
                valueFormatter={(v) => moneyShort(v)} withLegend gridAxis="xy" fillOpacity={0.15} withDots={series.length <= 31} />
            ) : (
              <BarChart h={300} data={series} dataKey="label" series={revenueSeries.slice(1)}
                valueFormatter={(v) => moneyShort(v)} withLegend gridAxis="y" />
            )}
          </ChartCard>

          <SimpleGrid cols={{ base: 1, lg: 2 }}>
            <ChartCard title="Payment-method mix" description="Share of collected amount by payment method.">
              <DonutWithLegend
                valueFormatter={(v) => moneyShort(v)}
                data={d.paymentMethods.map((m, i) => ({
                  name: label(m.method),
                  value: m.amount ?? 0,
                  color: PALETTE[i % PALETTE.length],
                  display: moneyShort(m.amount),
                  hint: `${number(m.payments)} payments · ${pct(m.shareOfAmount)}`,
                }))}
              />
            </ChartCard>
            <ChartCard title="Payment outcomes" description="Successful vs failed payment attempts per period.">
              {series.every((p) => p.successful === 0 && p.failed === 0) ? (
                <EmptyChart />
              ) : (
                <BarChart h={220} data={series} dataKey="label" type="stacked" gridAxis="y"
                  series={[{ name: 'successful', color: 'teal.5', label: 'Successful' }, { name: 'failed', color: 'red.5', label: 'Failed' }]}
                  withLegend />
              )}
            </ChartCard>
          </SimpleGrid>

          <ChartCard title="Revenue by plan" description='Plan comes from invoice → subscription → plan. Payments without a subscription are reported as "Unassigned".'>
            {d.byPlan.length === 0 ? <EmptyChart h={120} /> : (
              <Table.ScrollContainer minWidth={640}>
                <Table verticalSpacing="xs" striped highlightOnHover>
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th>Plan</Table.Th>
                      <Table.Th ta="right">Payments</Table.Th>
                      <Table.Th ta="right">Gross</Table.Th>
                      <Table.Th ta="right">Refunds</Table.Th>
                      <Table.Th ta="right">Net</Table.Th>
                      <Table.Th>Share of net</Table.Th>
                    </Table.Tr>
                  </Table.Thead>
                  <Table.Tbody>
                    {d.byPlan.map((p) => (
                      <Table.Tr key={p.planId ?? 'none'}>
                        <Table.Td fw={500}>{p.planName ?? 'Unassigned'}</Table.Td>
                        <Table.Td ta="right">{number(p.successfulPayments)}</Table.Td>
                        <Table.Td ta="right">{money(p.gross)}</Table.Td>
                        <Table.Td ta="right" c={p.refunds ? 'red' : undefined}>{money(p.refunds)}</Table.Td>
                        <Table.Td ta="right" fw={600}>{money(p.net)}</Table.Td>
                        <Table.Td><ShareBar value={p.shareOfNet} /></Table.Td>
                      </Table.Tr>
                    ))}
                  </Table.Tbody>
                </Table>
              </Table.ScrollContainer>
            )}
          </ChartCard>

          <ChartCard title="Revenue by branch" description="Attributed to the invoice's branch.">
            {d.byBranch.length === 0 ? <EmptyChart h={120} /> : (
              <SimpleGrid cols={{ base: 1, lg: d.byBranch.length > 1 ? 2 : 1 }}>
                {d.byBranch.length > 1 && (
                  <BarChart h={Math.max(160, d.byBranch.length * 48)} orientation="vertical" gridAxis="x"
                    data={d.byBranch.map((b) => ({ branch: b.branchName ?? `Branch ${b.branchId}`, net: b.net ?? 0 }))}
                    dataKey="branch" series={[{ name: 'net', color: 'teal.6', label: 'Net revenue' }]}
                    valueFormatter={(v) => moneyShort(v)} yAxisProps={{ width: 140 }} />
                )}
                <Table.ScrollContainer minWidth={420}>
                  <Table verticalSpacing="xs">
                    <Table.Thead>
                      <Table.Tr>
                        <Table.Th>Branch</Table.Th>
                        <Table.Th ta="right">Gross</Table.Th>
                        <Table.Th ta="right">Refunds</Table.Th>
                        <Table.Th ta="right">Net</Table.Th>
                        <Table.Th>Share</Table.Th>
                      </Table.Tr>
                    </Table.Thead>
                    <Table.Tbody>
                      {d.byBranch.map((b) => (
                        <Table.Tr key={b.branchId ?? 'none'}>
                          <Table.Td fw={500}>{b.branchName ?? '—'}</Table.Td>
                          <Table.Td ta="right">{moneyShort(b.gross)}</Table.Td>
                          <Table.Td ta="right">{moneyShort(b.refunds)}</Table.Td>
                          <Table.Td ta="right" fw={600}>{moneyShort(b.net)}</Table.Td>
                          <Table.Td><ShareBar value={b.shareOfNet} /></Table.Td>
                        </Table.Tr>
                      ))}
                    </Table.Tbody>
                  </Table>
                </Table.ScrollContainer>
              </SimpleGrid>
            )}
          </ChartCard>

          <Definition>
              Failed-payment rate = FAILED attempts ÷ all payment attempts. ARPU = net revenue ÷ distinct members with at
              least one successful payment in the range.
          </Definition>
          <MetaLine meta={d.meta} branchNames={branchNames} />
        </Stack>
      )}
    </QueryState>
  );
}
