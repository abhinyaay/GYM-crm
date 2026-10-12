import { Group, Paper, Text, ThemeIcon } from '@mantine/core';
import type { ReactNode } from 'react';

export function StatCard({ label, value, hint, icon, color = 'teal' }: {
  label: string;
  value: ReactNode;
  hint?: ReactNode;
  icon?: ReactNode;
  color?: string;
}) {
  return (
    <Paper withBorder p="md" radius="md">
      <Group justify="space-between" align="flex-start" wrap="nowrap">
        <div>
          <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
            {label}
          </Text>
          <Text fw={700} fz={26} lh={1.2} mt={4}>
            {value}
          </Text>
          {hint && (
            <Text size="xs" c="dimmed" mt={4}>
              {hint}
            </Text>
          )}
        </div>
        {icon && (
          <ThemeIcon variant="light" color={color} size={38} radius="md">
            {icon}
          </ThemeIcon>
        )}
      </Group>
    </Paper>
  );
}
