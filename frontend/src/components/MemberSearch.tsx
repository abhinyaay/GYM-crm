import { Select, type SelectProps } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { useState } from 'react';
import type { MemberListItem, Page } from '@/api/types';
import { useApiQuery } from '@/lib/useApi';

/** Searchable member picker (staff only): searches by name, email, phone or member code. */
export function MemberSearch({ value, onChange, branchId, ...props }: {
  value: number | null;
  onChange: (memberId: number | null, member?: MemberListItem) => void;
  branchId?: number | null;
} & Omit<SelectProps, 'value' | 'onChange' | 'data'>) {
  const [search, setSearch] = useState('');
  const [debounced] = useDebouncedValue(search, 250);
  const { data } = useApiQuery<Page<MemberListItem>>(
    '/api/v1/members',
    { q: debounced, branchId: branchId ?? undefined, size: 15 },
    { enabled: debounced.length >= 2 },
  );
  const items = data?.content ?? [];
  return (
    <Select
      searchable
      clearable
      value={value == null ? null : String(value)}
      searchValue={search}
      onSearchChange={setSearch}
      onChange={(v) => onChange(v ? Number(v) : null, items.find((m) => String(m.id) === v))}
      data={items.map((m) => ({ value: String(m.id), label: `${m.fullName} · ${m.memberCode}` }))}
      nothingFoundMessage={debounced.length < 2 ? 'Type at least 2 characters' : 'No members found'}
      filter={({ options }) => options}
      placeholder="Search name, email, phone or code"
      {...props}
    />
  );
}
