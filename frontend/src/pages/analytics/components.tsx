import { alpha, Box, ColorSwatch, Group, Paper, Progress, ScrollArea, Stack, Text, Title, Tooltip } from '@mantine/core';
import { DonutChart } from '@mantine/charts';
import { IconInfoCircle } from '@tabler/icons-react';
import dayjs from 'dayjs';
import type { ReactNode } from 'react';
import { dateTime, label, WEEKDAYS } from '@/lib/format';
import type { HeatmapRow, QueryMeta } from './types';

/** Categorical palette used across the analytics charts (Mantine colour tokens). */
export const PALETTE = ['teal.6', 'blue.6', 'grape.6', 'orange.6', 'cyan.6', 'pink.6', 'lime.7', 'indigo.6', 'yellow.7', 'red.6'];

/** Paper with a title, an optional definition line and an optional action slot. */
export function ChartCard({ title, description, action, children, h }: {
  title: ReactNode;
  description?: ReactNode;
  action?: ReactNode;
  children: ReactNode;
  h?: number | string;
}) {
  return (
    <Paper withBorder p="md" h={h}>
      <Group justify="space-between" align="flex-start" mb="sm" wrap="nowrap" gap="xs">
        <Stack gap={2}>
          <Title order={5}>{title}</Title>
          {description && (
            <Text size="xs" c="dimmed">
              {description}
            </Text>
          )}
        </Stack>
        {action}
      </Group>
      {children}
    </Paper>
  );
}

/** Placeholder shown inside a chart card when there is nothing to plot. */
export function EmptyChart({ h = 220, text = 'No data for this period.' }: { h?: number; text?: string }) {
  return (
    <Box h={h} style={{ display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <Text c="dimmed" size="sm">
        {text}
      </Text>
    </Box>
  );
}

/** Small dimmed definition text with an info icon. */
export function Definition({ children }: { children: ReactNode }) {
  return (
    <Group gap={6} wrap="nowrap" align="flex-start" mt="xs">
      <IconInfoCircle size={14} style={{ flexShrink: 0, marginTop: 2, color: 'var(--mantine-color-dimmed)' }} />
      <Text size="xs" c="dimmed">
        {children}
      </Text>
    </Group>
  );
}

/** Footer describing the scope, range and freshness of an analytics response. */
export function MetaLine({ meta, branchNames, note }: { meta: QueryMeta | undefined; branchNames: Record<number, string>; note?: string }) {
  if (!meta) return null;
  const scope = meta.scopeBranchIds == null
    ? 'All branches'
    : meta.scopeBranchIds.map((id) => branchNames[id] ?? `Branch ${id}`).join(', ') || 'No branches';
  const range = meta.from && meta.to ? `${dayjs(meta.from).format('DD MMM YYYY')} – ${dayjs(meta.to).format('DD MMM YYYY')}` : null;
  return (
    <Text size="xs" c="dimmed" mt="lg" ta="right">
      Scope: {scope}
      {range && ` · ${range}`}
      {meta.granularity && ` · by ${meta.granularity}`} · {meta.timezone} · generated {dateTime(meta.generatedAt)} (cached up to 5 min)
      {note && ` · ${note}`}
    </Text>
  );
}

/** Formats an ISO period start for a chart axis according to the bucket size. */
export function periodLabel(period: string, granularity: string | null | undefined): string {
  const d = dayjs(period);
  if (granularity === 'month') return d.format('MMM YY');
  if (granularity === 'week') return `Wk ${d.format('DD MMM')}`;
  return d.format('DD MMM');
}

/** "HIGHLY_ENGAGED" -> "Highly engaged" */
export const segmentLabel = (s: string) => label(s);

/** Background for a heat cell; intensity in 0..1. */
export function heatColor(intensity: number, color = 'teal'): string {
  if (!Number.isFinite(intensity) || intensity <= 0) return 'var(--mantine-color-default-hover)';
  return alpha(`var(--mantine-color-${color}-6)`, 0.12 + Math.min(1, intensity) * 0.88);
}

/** Readable text colour on top of {@link heatColor}. */
export const heatText = (intensity: number) => (intensity > 0.55 ? 'white' : undefined);

const hourLabel = (h: number) => `${String(h).padStart(2, '0')}`;

/**
 * 7×24 heat map of average check-ins per weekday and local hour. Mantine has no matrix heat map,
 * so this is a CSS grid of cells whose background alpha scales with the value.
 */
export function WeekHourHeatmap({ rows }: { rows: HeatmapRow[] }) {
  const sorted = [...rows].sort((a, b) => a.isoDay - b.isoDay);
  const max = Math.max(0, ...sorted.flatMap((r) => r.avgCheckIns.map((v) => v ?? 0)));
  const hours = Array.from({ length: 24 }, (_, h) => h);
  const grid = { display: 'grid', gridTemplateColumns: '44px repeat(24, minmax(22px, 1fr))', gap: 3 } as const;
  return (
    <ScrollArea type="auto" offsetScrollbars>
      <Box miw={640}>
        <Box style={grid} mb={4}>
          <span />
          {hours.map((h) => (
            <Text key={h} size="10px" c="dimmed" ta="center">
              {h % 3 === 0 ? hourLabel(h) : ''}
            </Text>
          ))}
        </Box>
        {sorted.map((row) => (
          <Box key={row.isoDay} style={grid} mb={3}>
            <Text size="xs" c="dimmed" fw={500} style={{ alignSelf: 'center' }}>
              {WEEKDAYS[row.isoDay - 1] ?? row.weekday}
            </Text>
            {hours.map((h) => {
              const v = row.avgCheckIns[h] ?? 0;
              const intensity = max > 0 ? v / max : 0;
              return (
                <Tooltip
                  key={h}
                  label={`${label(row.weekday)} ${hourLabel(h)}:00–${hourLabel((h + 1) % 24)}:00 · avg ${v.toFixed(2)} check-ins (over ${row.occurrences} days)`}
                  withArrow
                  openDelay={80}
                >
                  <Box
                    h={26}
                    style={{
                      borderRadius: 4,
                      background: heatColor(intensity),
                      color: heatText(intensity),
                      fontSize: 10,
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      cursor: 'default',
                    }}
                  >
                    {v >= 1 ? Math.round(v) : ''}
                  </Box>
                </Tooltip>
              );
            })}
          </Box>
        ))}
        <Group gap={6} mt="sm" justify="flex-end">
          <Text size="xs" c="dimmed">
            0
          </Text>
          {[0.1, 0.3, 0.5, 0.7, 0.9, 1].map((i) => (
            <Box key={i} w={18} h={10} style={{ borderRadius: 2, background: heatColor(i) }} />
          ))}
          <Text size="xs" c="dimmed">
            {max.toFixed(1)} avg check-ins / hour
          </Text>
        </Group>
      </Box>
    </ScrollArea>
  );
}

export interface DonutItem {
  name: string;
  value: number;
  color: string;
  /** right-aligned text, defaults to the value */
  display?: ReactNode;
  /** secondary text such as a share */
  hint?: ReactNode;
}

/** Donut chart with a readable legend table next to it (stacks on small screens). */
export function DonutWithLegend({ data, chartLabel, valueFormatter }: {
  data: DonutItem[];
  chartLabel?: string;
  valueFormatter?: (v: number) => string;
}) {
  const nonZero = data.filter((d) => d.value > 0);
  if (nonZero.length === 0) return <EmptyChart />;
  return (
    <Group align="center" justify="center" gap="xl" wrap="wrap">
      <DonutChart
        data={nonZero.map(({ name, value, color }) => ({ name, value, color }))}
        size={180}
        thickness={26}
        chartLabel={chartLabel}
        valueFormatter={valueFormatter}
        tooltipDataSource="segment"
        paddingAngle={1}
      />
      <Stack gap={6} style={{ flex: 1, minWidth: 200 }}>
        {data.map((d) => (
          <Group key={d.name} justify="space-between" wrap="nowrap" gap="sm">
            <Group gap={8} wrap="nowrap">
              <ColorSwatch color={`var(--mantine-color-${d.color.replace('.', '-')})`} size={12} withShadow={false} />
              <Text size="sm">{d.name}</Text>
            </Group>
            <Group gap={8} wrap="nowrap">
              {d.hint && (
                <Text size="xs" c="dimmed">
                  {d.hint}
                </Text>
              )}
              <Text size="sm" fw={600}>
                {d.display ?? d.value}
              </Text>
            </Group>
          </Group>
        ))}
      </Stack>
    </Group>
  );
}

/** Inline share bar for tables. */
export function ShareBar({ value, color = 'teal' }: { value: number | null | undefined; color?: string }) {
  return (
    <Group gap={8} wrap="nowrap" miw={120}>
      <Progress value={(value ?? 0) * 100} color={color} size="sm" style={{ flex: 1 }} />
      <Text size="xs" c="dimmed" w={42} ta="right">
        {value == null ? '—' : `${(value * 100).toFixed(1)}%`}
      </Text>
    </Group>
  );
}
