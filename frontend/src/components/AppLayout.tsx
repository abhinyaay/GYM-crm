import {
  ActionIcon, AppShell, Avatar, Badge, Burger, Group, Menu, NavLink, ScrollArea, Text, Tooltip, UnstyledButton,
  useComputedColorScheme, useMantineColorScheme,
} from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { IconBarbell, IconLogout, IconMoon, IconSun, IconUser } from '@tabler/icons-react';
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '@/auth/AuthContext';
import { hasAnyRole, ORG_WIDE, STAFF } from '@/auth/roles';
import { label } from '@/lib/format';
import { NAV } from './nav';
import { NotificationsBell } from './NotificationsDrawer';

export function AppLayout() {
  const [opened, { toggle, close }] = useDisclosure();
  const { user, logout } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const { setColorScheme } = useMantineColorScheme();
  const scheme = useComputedColorScheme('light');
  const items = NAV.filter((n) => hasAnyRole(user, n.roles));
  const active = (to: string) =>
    location.pathname === to || (to !== '/me' && to !== '/trainer' && location.pathname.startsWith(to + '/'));

  return (
    <AppShell header={{ height: 60 }} navbar={{ width: 240, breakpoint: 'sm', collapsed: { mobile: !opened } }} padding="lg">
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between" wrap="nowrap">
          <Group gap="xs" wrap="nowrap">
            <Burger opened={opened} onClick={toggle} hiddenFrom="sm" size="sm" />
            <IconBarbell size={26} color="var(--mantine-color-teal-6)" />
            <Text fw={800} fz="lg">GymOps</Text>
          </Group>
          <Group gap="xs" wrap="nowrap">
            <NotificationsBell />
            <Tooltip label={scheme === 'dark' ? 'Light mode' : 'Dark mode'}>
              <ActionIcon variant="subtle" size="lg" aria-label="Toggle colour scheme"
                onClick={() => setColorScheme(scheme === 'dark' ? 'light' : 'dark')}>
                {scheme === 'dark' ? <IconSun size={20} /> : <IconMoon size={20} />}
              </ActionIcon>
            </Tooltip>
            <Menu position="bottom-end" width={240}>
              <Menu.Target>
                <UnstyledButton aria-label="Account menu">
                  <Group gap={8} wrap="nowrap">
                    <Avatar color="teal" radius="xl" size={32}>
                      {user?.fullName?.split(' ').map((p) => p[0]).slice(0, 2).join('')}
                    </Avatar>
                    <div style={{ lineHeight: 1.1 }} className="hide-mobile">
                      <Text size="sm" fw={600}>{user?.fullName}</Text>
                      <Text size="xs" c="dimmed">{user?.roles.map(label).join(', ')}</Text>
                    </div>
                  </Group>
                </UnstyledButton>
              </Menu.Target>
              <Menu.Dropdown>
                <Menu.Label>{user?.email}</Menu.Label>
                <Menu.Item leftSection={<IconUser size={16} />} component={Link} to="/profile">Profile & password</Menu.Item>
                <Menu.Item leftSection={<IconLogout size={16} />} color="red"
                  onClick={async () => { await logout(); navigate('/login'); }}>
                  Sign out
                </Menu.Item>
              </Menu.Dropdown>
            </Menu>
          </Group>
        </Group>
      </AppShell.Header>
      <AppShell.Navbar p="xs">
        <ScrollArea>
          {items.map((item) => (
            <NavLink
              key={item.to}
              component={Link}
              to={item.to}
              label={item.label}
              leftSection={item.icon}
              active={active(item.to)}
              onClick={close}
              variant="light"
              style={{ borderRadius: 8 }}
            />
          ))}
        </ScrollArea>
        {hasAnyRole(user, STAFF) && (
          <AppShell.Section>
            <Badge variant="light" color="gray" fullWidth>
              {hasAnyRole(user, ORG_WIDE) ? 'All branches' : `${user?.branchIds.length ?? 0} branch(es)`}
            </Badge>
          </AppShell.Section>
        )}
      </AppShell.Navbar>
      <AppShell.Main>
        <Outlet />
      </AppShell.Main>
    </AppShell>
  );
}
