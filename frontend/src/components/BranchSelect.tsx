import { Select, type SelectProps } from '@mantine/core';
import type { Branch, BranchSummary, Page } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { hasAnyRole, ORG_WIDE } from '@/auth/roles';
import { useApiQuery } from '@/lib/useApi';

/**
 * Branch dropdown limited to the branches the caller may see. Org-wide roles get an
 * "All branches" option when `allowAll` is set (value null).
 */
export function BranchSelect({ value, onChange, allowAll = false, publicList = false, ...props }: {
  value: number | null;
  onChange: (id: number | null) => void;
  allowAll?: boolean;
  /** Use the unauthenticated public branch list (sign-up form). */
  publicList?: boolean;
} & Omit<SelectProps, 'value' | 'onChange' | 'data'>) {
  const { user } = useAuth();
  const staffBranches = useApiQuery<Page<Branch>>(publicList ? null : '/api/v1/branches', { size: 100 });
  const publicBranches = useApiQuery<BranchSummary[]>(publicList ? '/api/v1/branches/public' : null);
  const branches = publicList ? publicBranches.data ?? [] : staffBranches.data?.content ?? [];
  const data = [
    ...(allowAll && hasAnyRole(user, ORG_WIDE) ? [{ value: '', label: 'All branches' }] : []),
    ...branches.map((b) => ({ value: String(b.id), label: `${b.name} (${b.code})` })),
  ];
  return (
    <Select
      data={data}
      value={value == null ? (allowAll && hasAnyRole(user, ORG_WIDE) ? '' : null) : String(value)}
      onChange={(v) => onChange(v ? Number(v) : null)}
      allowDeselect={false}
      placeholder="Select branch"
      {...props}
    />
  );
}

/** Branch id -> name lookup for tables. */
export function useBranchNames(): Record<number, string> {
  const { data } = useApiQuery<Page<Branch>>('/api/v1/branches', { size: 100 });
  return Object.fromEntries((data?.content ?? []).map((b) => [b.id, b.name]));
}
