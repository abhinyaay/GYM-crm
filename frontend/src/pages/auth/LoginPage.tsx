import { Alert, Anchor, Box, Button, Center, Group, Paper, PasswordInput, Stack, Text, TextInput, Title } from '@mantine/core';
import { useForm } from '@mantine/form';
import { IconBarbell } from '@tabler/icons-react';
import { useState } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '@/auth/AuthContext';
import { homePath } from '@/auth/roles';
import { errorMessage } from '@/lib/notify';

export default function LoginPage() {
  const { user, login, initializing } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const form = useForm({
    initialValues: { email: '', password: '' },
    validate: {
      email: (v) => (/^\S+@\S+\.\S+$/.test(v) ? null : 'Enter a valid email'),
      password: (v) => (v ? null : 'Enter your password'),
    },
  });

  if (!initializing && user) return <Navigate to={homePath(user)} replace />;

  const submit = form.onSubmit(async ({ email, password }) => {
    setLoading(true);
    setError(null);
    try {
      const u = await login(email.trim(), password);
      const from = (location.state as { from?: string } | null)?.from;
      navigate(from && from !== '/' ? from : homePath(u), { replace: true });
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setLoading(false);
    }
  });

  return (
    <Center mih="100vh" p="md">
      <Box w="100%" maw={420}>
        <Group justify="center" gap="xs" mb="lg">
          <IconBarbell size={36} color="var(--mantine-color-teal-6)" />
          <Title order={1}>GymOps</Title>
        </Group>
        <Paper withBorder shadow="sm" p="xl" radius="lg">
          <Title order={3} mb="lg">Sign in</Title>
          <form onSubmit={submit}>
            <Stack>
              <TextInput label="Email" placeholder="you@example.com" autoComplete="username" {...form.getInputProps('email')} />
              <PasswordInput label="Password" autoComplete="current-password" {...form.getInputProps('password')} />
              {error && <Alert color="red">{error}</Alert>}
              <Button type="submit" loading={loading} size="md">Sign in</Button>
            </Stack>
          </form>
          <Text size="sm" ta="center" mt="lg">
            New member? <Anchor component={Link} to="/register">Create an account</Anchor>
          </Text>
          <Text size="sm" ta="center" mt={4}>
            Just looking? <Anchor component={Link} to="/enquire">Book a free trial</Anchor>
          </Text>
        </Paper>
      </Box>
    </Center>
  );
}
