import { Badge, Group, Text, Tooltip } from '@mantine/core';
import { IconAlertTriangle, IconArrowBigUpLines, IconClockHour4 } from '@tabler/icons-react';
import { useQueries } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { api } from '@/api/client';
import type {
  Complaint, ComplaintCategory, ComplaintPriority, ComplaintStatus, StaffMember, UserInfo,
} from '@/api/types';
import { hasAnyRole, MANAGERS } from '@/auth/roles';
import { dateTime, label } from '@/lib/format';

export const CATEGORIES: ComplaintCategory[] = ['FACILITY', 'EQUIPMENT', 'STAFF', 'TRAINER', 'BILLING', 'HYGIENE', 'MEMBERSHIP', 'OTHER'];
export const PRIORITIES: ComplaintPriority[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
export const STATUSES: ComplaintStatus[] = ['OPEN', 'IN_PROGRESS', 'ESCALATED', 'RESOLVED', 'CLOSED', 'REOPENED'];
export const UNRESOLVED: ComplaintStatus[] = ['OPEN', 'IN_PROGRESS', 'ESCALATED', 'REOPENED'];
export const MAX_ESCALATION_LEVEL = 3;

/** Mirrors ComplaintStatus.TRANSITIONS in the core service. */
export const TRANSITIONS: Record<ComplaintStatus, ComplaintStatus[]> = {
  OPEN: ['IN_PROGRESS', 'ESCALATED', 'RESOLVED'],
  IN_PROGRESS: ['ESCALATED', 'RESOLVED'],
  ESCALATED: ['IN_PROGRESS', 'RESOLVED'],
  REOPENED: ['IN_PROGRESS', 'ESCALATED', 'RESOLVED'],
  RESOLVED: ['CLOSED', 'REOPENED'],
  CLOSED: [],
};

export const options = (values: string[]) => values.map((v) => ({ value: v, label: label(v) }));

/** Compact "2h 15m" / "3d 4h" duration. */
export function shortDuration(ms: number): string {
  const abs = Math.abs(ms);
  const mins = Math.floor(abs / 60_000);
  const d = Math.floor(mins / 1440);
  const h = Math.floor((mins % 1440) / 60);
  const m = mins % 60;
  if (d > 0) return `${d}d ${h}h`;
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}

/**
 * SLA state of a ticket. Unresolved tickets show the time left (or the breach); resolved/closed
 * tickets show whether they were resolved within the SLA.
 */
export function SlaBadge({ complaint, now = Date.now(), withDue = true }: { complaint: Complaint; now?: number; withDue?: boolean }) {
  const due = dayjs(complaint.slaDueAt).valueOf();
  if (!UNRESOLVED.includes(complaint.status)) {
    const resolvedAt = complaint.resolvedAt ?? complaint.closedAt;
    const met = resolvedAt ? dayjs(resolvedAt).valueOf() <= due : !complaint.slaBreached;
    return (
      <Tooltip label={`SLA due ${dateTime(complaint.slaDueAt)}${resolvedAt ? ` · resolved ${dateTime(resolvedAt)}` : ''}`} withArrow>
        <Badge variant="light" color={met ? 'teal' : 'red'} radius="sm">{met ? 'SLA met' : 'SLA missed'}</Badge>
      </Tooltip>
    );
  }
  const breached = complaint.slaBreached || due < now;
  const left = due - now;
  const color = breached ? 'red' : left < 4 * 3600_000 ? 'orange' : 'teal';
  return (
    <Group gap={4} wrap="nowrap">
      <Badge variant={breached ? 'filled' : 'light'} color={color} radius="sm"
        leftSection={breached ? <IconAlertTriangle size={12} /> : <IconClockHour4 size={12} />}>
        {breached ? `Breached ${shortDuration(left)} ago` : `${shortDuration(left)} left`}
      </Badge>
      {withDue && (
        <Text size="xs" c={breached ? 'red' : 'dimmed'} style={{ whiteSpace: 'nowrap' }}>
          due {dateTime(complaint.slaDueAt)}
        </Text>
      )}
    </Group>
  );
}

export function EscalationBadge({ level }: { level: number }) {
  if (level <= 0) return <Text size="xs" c="dimmed">—</Text>;
  return (
    <Tooltip label={`Escalation level ${level} of ${MAX_ESCALATION_LEVEL}`} withArrow>
      <Badge variant="light" color={level >= MAX_ESCALATION_LEVEL ? 'red' : level === 2 ? 'orange' : 'yellow'} radius="sm"
        leftSection={<IconArrowBigUpLines size={12} />}>
        L{level}
      </Badge>
    </Tooltip>
  );
}

/**
 * user id -> display name for the staff of the given branches. The staff list endpoint is
 * MANAGER+ only, so receptionists fall back to "User #id" (and "You" for themselves).
 */
export function useStaffDirectory(user: UserInfo | null, branchIds: number[]) {
  const canList = hasAnyRole(user, MANAGERS);
  const unique = [...new Set(branchIds)].sort((a, b) => a - b);
  const results = useQueries({
    queries: unique.map((id) => ({
      queryKey: [`/api/v1/branches/${id}/staff`, {}],
      queryFn: () => api<StaffMember[]>(`/api/v1/branches/${id}/staff`),
      enabled: canList,
      staleTime: 5 * 60_000,
    })),
  });
  const byBranch: Record<number, StaffMember[]> = {};
  const names: Record<number, string> = {};
  results.forEach((r, i) => {
    byBranch[unique[i]] = r.data ?? [];
    r.data?.forEach((s) => { names[s.userId] = s.fullName; });
  });
  if (user) names[user.id] = `${user.fullName} (you)`;
  const nameOf = (userId: number | null | undefined, fallback = 'Unassigned') =>
    userId == null ? fallback : names[userId] ?? `User #${userId}`;
  return { byBranch, names, nameOf, loading: results.some((r) => r.isLoading) };
}
