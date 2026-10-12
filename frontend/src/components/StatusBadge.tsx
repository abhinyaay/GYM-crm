import { Badge, type BadgeProps } from '@mantine/core';
import { label } from '@/lib/format';

const COLORS: Record<string, string> = {
  // membership / subscription
  ACTIVE: 'teal', SCHEDULED: 'blue', PENDING_PAYMENT: 'yellow', FROZEN: 'cyan', GRACE: 'orange',
  EXPIRED: 'gray', CANCELLED: 'red', SUPERSEDED: 'gray', NONE: 'gray',
  // members / users / branches / trainers
  SUSPENDED: 'orange', INACTIVE: 'gray', LOCKED: 'orange', DISABLED: 'red', UNDER_MAINTENANCE: 'yellow',
  ON_LEAVE: 'yellow', DRAFT: 'gray', ARCHIVED: 'gray',
  // billing
  ISSUED: 'yellow', PAID: 'teal', VOID: 'gray', PARTIALLY_REFUNDED: 'grape', REFUNDED: 'grape',
  SUCCEEDED: 'teal', FAILED: 'red',
  // autopay / saved methods
  PROCESSING: 'blue', SKIPPED: 'gray', PAUSED: 'yellow', REVOKED: 'red', PENDING: 'yellow',
  // sessions
  COMPLETED: 'teal', NO_SHOW: 'red',
  // complaints
  OPEN: 'blue', IN_PROGRESS: 'indigo', ESCALATED: 'red', RESOLVED: 'teal', CLOSED: 'gray', REOPENED: 'orange',
  LOW: 'gray', MEDIUM: 'blue', HIGH: 'orange', CRITICAL: 'red',
  // churn risk
  // (HIGH/MEDIUM/LOW reuse the priority colours)
};

export function StatusBadge({ value, ...props }: { value: string | null | undefined } & Omit<BadgeProps, 'children'>) {
  if (!value) return null;
  return (
    <Badge variant="light" color={COLORS[value] ?? 'gray'} radius="sm" {...props}>
      {label(value)}
    </Badge>
  );
}
