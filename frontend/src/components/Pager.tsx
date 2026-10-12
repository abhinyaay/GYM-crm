import { Group, Pagination, Text } from '@mantine/core';
import type { Page } from '@/api/types';

/** Pagination footer for a Spring-style page (0-based). */
export function Pager({ page, onChange }: { page: Page<unknown> | undefined; onChange: (page: number) => void }) {
  if (!page || page.totalPages <= 1) {
    return page ? (
      <Text size="sm" c="dimmed" mt="sm">
        {page.totalElements} result{page.totalElements === 1 ? '' : 's'}
      </Text>
    ) : null;
  }
  return (
    <Group justify="space-between" mt="md">
      <Text size="sm" c="dimmed">
        {page.totalElements} results
      </Text>
      <Pagination size="sm" total={page.totalPages} value={page.page + 1} onChange={(p) => onChange(p - 1)} />
    </Group>
  );
}
