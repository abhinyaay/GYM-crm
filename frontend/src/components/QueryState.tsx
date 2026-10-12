import { Alert, Center, Loader, Text } from '@mantine/core';
import { IconAlertCircle } from '@tabler/icons-react';
import type { ReactNode } from 'react';
import { errorMessage } from '@/lib/notify';

/** Renders loading / error states for a query, then the children. */
export function QueryState({ isLoading, error, children, empty, emptyText = 'Nothing here yet.' }: {
  isLoading: boolean;
  error: unknown;
  children: ReactNode;
  empty?: boolean;
  emptyText?: string;
}) {
  if (isLoading) {
    return (
      <Center py="xl">
        <Loader size="sm" />
      </Center>
    );
  }
  if (error) {
    return (
      <Alert color="red" icon={<IconAlertCircle size={18} />} title="Could not load data">
        {errorMessage(error)}
      </Alert>
    );
  }
  if (empty) {
    return (
      <Text c="dimmed" ta="center" py="xl">
        {emptyText}
      </Text>
    );
  }
  return <>{children}</>;
}
