import { Button, Group, Paper, SegmentedControl, Tabs, Text } from '@mantine/core';
import { DatePickerInput, type DatesRangeValue } from '@mantine/dates';
import {
  IconBuildingStore, IconCalendar, IconCash, IconChartDots, IconDoorEnter, IconId, IconUserExclamation, IconUsers,
  IconUsersGroup, IconYoga, IconTargetArrow,
} from '@tabler/icons-react';
import dayjs from 'dayjs';
import { useState, type ReactNode } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useAuth } from '@/auth/AuthContext';
import { hasAnyRole, ORG_WIDE } from '@/auth/roles';
import { BranchSelect, useBranchNames } from '@/components/BranchSelect';
import { PageHeader } from '@/components/PageHeader';
import { AttendanceTab } from './AttendanceTab';
import { BranchesTab } from './BranchesTab';
import { ChurnRiskTab } from './ChurnRiskTab';
import { ClassesTab } from './ClassesTab';
import { LeadsTab } from './LeadsTab';
import { MembersTab } from './MembersTab';
import { MembershipsTab } from './MembershipsTab';
import { RevenueTab } from './RevenueTab';
import { TrainersTab } from './TrainersTab';
import type { AnalyticsFilters, Granularity } from './types';

type TabKey = 'revenue' | 'attendance' | 'members' | 'memberships' | 'churn' | 'trainers' | 'branches' | 'classes' | 'leads';

const TABS: { value: TabKey; label: string; icon: ReactNode; usesRange: boolean; usesGranularity: boolean }[] = [
  { value: 'revenue', label: 'Revenue', icon: <IconCash size={16} />, usesRange: true, usesGranularity: true },
  { value: 'attendance', label: 'Attendance', icon: <IconDoorEnter size={16} />, usesRange: true, usesGranularity: true },
  { value: 'members', label: 'Members', icon: <IconUsers size={16} />, usesRange: true, usesGranularity: true },
  { value: 'memberships', label: 'Memberships', icon: <IconId size={16} />, usesRange: true, usesGranularity: false },
  { value: 'churn', label: 'Churn risk', icon: <IconUserExclamation size={16} />, usesRange: false, usesGranularity: false },
  { value: 'trainers', label: 'Trainers', icon: <IconUsersGroup size={16} />, usesRange: true, usesGranularity: false },
  { value: 'branches', label: 'Branches', icon: <IconBuildingStore size={16} />, usesRange: true, usesGranularity: false },
  { value: 'classes', label: 'Classes', icon: <IconYoga size={16} />, usesRange: true, usesGranularity: false },
  { value: 'leads', label: 'Leads', icon: <IconTargetArrow size={16} />, usesRange: true, usesGranularity: false },
];

const PRESETS: { label: string; days: number }[] = [
  { label: '30d', days: 30 },
  { label: '90d', days: 90 },
  { label: '6m', days: 182 },
  { label: '12m', days: 365 },
];

const fmt = (d: Date) => dayjs(d).format('YYYY-MM-DD');
const MAX_RANGE_DAYS = 730;

export default function AnalyticsPage() {
  const { user } = useAuth();
  const orgWide = hasAnyRole(user, ORG_WIDE);
  const branchNames = useBranchNames();
  const [params, setParams] = useSearchParams();
  const tabParam = params.get('tab');
  const tab: TabKey = TABS.some((t) => t.value === tabParam) ? (tabParam as TabKey) : 'revenue';
  const tabInfo = TABS.find((t) => t.value === tab) ?? TABS[0];

  // managers with exactly one branch start on it (unlocks the occupancy view); everyone else starts on "all"
  const [branchId, setBranchId] = useState<number | null>(
    !orgWide && user && user.branchIds.length === 1 ? user.branchIds[0] : null,
  );
  const [range, setRange] = useState<DatesRangeValue>([
    dayjs().subtract(89, 'day').startOf('day').toDate(),
    dayjs().startOf('day').toDate(),
  ]);
  const [committed, setCommitted] = useState<{ from: string; to: string }>({ from: fmt(range[0] as Date), to: fmt(range[1] as Date) });
  const [granularity, setGranularity] = useState<Granularity>('day');

  const onRangeChange = (value: DatesRangeValue) => {
    setRange(value);
    // only query once both ends are picked
    if (value[0] && value[1]) setCommitted({ from: fmt(value[0]), to: fmt(value[1]) });
  };
  const applyPreset = (days: number) => {
    const to = dayjs().startOf('day');
    onRangeChange([to.subtract(days - 1, 'day').toDate(), to.toDate()]);
    if (days > 120 && granularity === 'day') setGranularity('week');
  };
  const rangeDays = dayjs(committed.to).diff(dayjs(committed.from), 'day') + 1;

  const filters: AnalyticsFilters = { branchId, from: committed.from, to: committed.to, granularity };

  return (
    <>
      <PageHeader
        title="Analytics"
        subtitle={orgWide ? 'Management insights across all branches' : 'Management insights for your branches'}
      />

      <Paper withBorder p="sm" mb="md">
        <Group gap="sm" wrap="wrap" align="flex-end">
          <BranchSelect
            allowAll
            value={branchId}
            onChange={setBranchId}
            label="Branch"
            w={{ base: '100%', sm: 240 }}
            clearable={!orgWide}
            placeholder={orgWide ? 'Select branch' : 'All my branches'}
          />
          <DatePickerInput
            type="range"
            label="Date range"
            value={range}
            onChange={onRangeChange}
            maxDate={new Date()}
            valueFormat="DD MMM YYYY"
            leftSection={<IconCalendar size={16} />}
            w={{ base: '100%', sm: 270 }}
            disabled={!tabInfo.usesRange}
            allowSingleDateInRange
          />
          <Group gap={4}>
            {PRESETS.map((p) => (
              <Button key={p.label} size="xs" variant={rangeDays === p.days ? 'light' : 'subtle'} color="gray"
                onClick={() => applyPreset(p.days)} disabled={!tabInfo.usesRange}>
                {p.label}
              </Button>
            ))}
          </Group>
          <div>
            <Text size="sm" fw={500} mb={4}>Group by</Text>
            <SegmentedControl
              value={granularity}
              onChange={(v) => setGranularity(v as Granularity)}
              disabled={!tabInfo.usesGranularity}
              data={[{ value: 'day', label: 'Day' }, { value: 'week', label: 'Week' }, { value: 'month', label: 'Month' }]}
            />
          </div>
        </Group>
        {rangeDays > MAX_RANGE_DAYS && (
          <Text size="xs" c="red" mt="xs">The analytics service accepts ranges of at most {MAX_RANGE_DAYS} days.</Text>
        )}
        {!tabInfo.usesRange && (
          <Text size="xs" c="dimmed" mt="xs">Churn risk is scored as of today, so the date range does not apply to this tab.</Text>
        )}
      </Paper>

      {/* keepMounted=false: only the active tab is rendered, so only its queries run (Nginx: 5 req/s on /analytics) */}
      <Tabs
        value={tab}
        onChange={(v) => v && setParams((p) => { p.set('tab', v); return p; }, { replace: true })}
        keepMounted={false}
        variant="outline"
      >
        <Tabs.List mb="md" style={{ flexWrap: 'nowrap', overflowX: 'auto', overflowY: 'hidden' }}>
          {TABS.map((t) => (
            <Tabs.Tab key={t.value} value={t.value} leftSection={t.icon} style={{ flexShrink: 0 }}>
              {t.label}
            </Tabs.Tab>
          ))}
        </Tabs.List>
        <Tabs.Panel value="revenue"><RevenueTab filters={filters} branchNames={branchNames} /></Tabs.Panel>
        <Tabs.Panel value="attendance"><AttendanceTab filters={filters} branchNames={branchNames} /></Tabs.Panel>
        <Tabs.Panel value="members"><MembersTab filters={filters} branchNames={branchNames} /></Tabs.Panel>
        <Tabs.Panel value="memberships"><MembershipsTab filters={filters} branchNames={branchNames} /></Tabs.Panel>
        <Tabs.Panel value="churn"><ChurnRiskTab filters={filters} branchNames={branchNames} /></Tabs.Panel>
        <Tabs.Panel value="trainers"><TrainersTab filters={filters} branchNames={branchNames} /></Tabs.Panel>
        <Tabs.Panel value="branches"><BranchesTab filters={filters} branchNames={branchNames} /></Tabs.Panel>
        <Tabs.Panel value="classes"><ClassesTab filters={filters} branchNames={branchNames} /></Tabs.Panel>
        <Tabs.Panel value="leads"><LeadsTab filters={filters} branchNames={branchNames} /></Tabs.Panel>
      </Tabs>
      <Group justify="center" mt="xl" gap={6}>
        <IconChartDots size={14} color="var(--mantine-color-dimmed)" />
        <Text size="xs" c="dimmed">Figures come from the read-only analytics service and may be up to 5 minutes old.</Text>
      </Group>
    </>
  );
}
