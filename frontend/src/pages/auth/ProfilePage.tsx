import { Alert, Badge, Button, Group, Paper, PasswordInput, SimpleGrid, Stack, Text, TextInput, Title } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import type { UserResponse } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { PageHeader } from '@/components/PageHeader';
import { dateTime, label } from '@/lib/format';
import { errorMessage } from '@/lib/notify';
import { useApiMutation, useApiQuery } from '@/lib/useApi';

const STRONG = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{10,72}$/;

export default function ProfilePage() {
  const { logout } = useAuth();
  const navigate = useNavigate();
  const { data: me } = useApiQuery<UserResponse>('/api/v1/users/me');
  const profile = useForm({ initialValues: { fullName: '', phone: '' } });
  const setProfileValues = profile.setValues;
  useEffect(() => {
    if (me) setProfileValues({ fullName: me.fullName, phone: me.phone ?? '' });
  }, [me, setProfileValues]);

  const save = useApiMutation<{ fullName: string; phone: string }>({
    request: (body) => ({ path: '/api/v1/users/me', method: 'PUT', body: { fullName: body.fullName, phone: body.phone || null } }),
    invalidate: ['/api/v1/users/me'],
    successMessage: 'Profile saved',
  });

  const pw = useForm({
    initialValues: { currentPassword: '', newPassword: '', confirm: '' },
    validate: {
      currentPassword: (v) => (v ? null : 'Required'),
      newPassword: (v) => (STRONG.test(v) ? null : '10+ characters with upper case, lower case and a digit'),
      confirm: (v, values) => (v === values.newPassword ? null : 'Passwords do not match'),
    },
  });
  const changePassword = useApiMutation<{ currentPassword: string; newPassword: string }>({
    request: (body) => ({ path: '/api/v1/auth/change-password', body }),
    silentError: true,
    onSuccess: async () => {
      // all sessions are revoked server-side on password change
      await logout();
      navigate('/login');
    },
  });
  const logoutAll = useApiMutation({
    request: () => ({ path: '/api/v1/auth/logout-all' }),
    onSuccess: async () => {
      await logout();
      navigate('/login');
    },
  });

  return (
    <>
      <PageHeader title="Profile" subtitle={me?.email} />
      <SimpleGrid cols={{ base: 1, md: 2 }}>
        <Paper withBorder p="lg">
          <Title order={4} mb="sm">Account</Title>
          <Group gap="xs" mb="md">
            {me?.roles.map((r) => <Badge key={r} variant="light">{label(r)}</Badge>)}
          </Group>
          <Text size="sm" c="dimmed" mb="md">Last sign-in: {dateTime(me?.lastLoginAt)}</Text>
          <form onSubmit={profile.onSubmit((v) => save.mutate(v))}>
            <Stack>
              <TextInput label="Full name" required {...profile.getInputProps('fullName')} />
              <TextInput label="Phone" {...profile.getInputProps('phone')} />
              <Button type="submit" loading={save.isPending} w="fit-content">Save</Button>
            </Stack>
          </form>
        </Paper>
        <Paper withBorder p="lg">
          <Title order={4} mb="sm">Security</Title>
          <form onSubmit={pw.onSubmit(({ currentPassword, newPassword }) => changePassword.mutate({ currentPassword, newPassword }))}>
            <Stack>
              <PasswordInput label="Current password" autoComplete="current-password" {...pw.getInputProps('currentPassword')} />
              <PasswordInput label="New password" autoComplete="new-password" {...pw.getInputProps('newPassword')} />
              <PasswordInput label="Confirm new password" autoComplete="new-password" {...pw.getInputProps('confirm')} />
              {changePassword.error && <Alert color="red">{errorMessage(changePassword.error)}</Alert>}
              <Text size="xs" c="dimmed">Changing your password signs you out on every device.</Text>
              <Group>
                <Button type="submit" loading={changePassword.isPending}>Change password</Button>
                <Button variant="light" color="red" onClick={() => logoutAll.mutate()} loading={logoutAll.isPending}>
                  Sign out everywhere
                </Button>
              </Group>
            </Stack>
          </form>
        </Paper>
      </SimpleGrid>
    </>
  );
}
