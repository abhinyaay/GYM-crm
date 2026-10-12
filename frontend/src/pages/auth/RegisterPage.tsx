import { Alert, Anchor, Box, Button, Center, Group, Paper, PasswordInput, SimpleGrid, Stack, Text, TextInput, Title } from '@mantine/core';
import { DatePickerInput } from '@mantine/dates';
import { useForm } from '@mantine/form';
import { IconBarbell } from '@tabler/icons-react';
import dayjs from 'dayjs';
import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { api } from '@/api/client';
import { useAuth } from '@/auth/AuthContext';
import { BranchSelect } from '@/components/BranchSelect';
import { errorMessage } from '@/lib/notify';

const STRONG = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{10,72}$/;

export default function RegisterPage() {
  const { register } = useAuth();
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const ref = (useSearchParams()[0].get('ref') ?? '').trim().slice(0, 20);   // /register?ref=CODE (referral)
  const form = useForm({
    initialValues: {
      firstName: '', lastName: '', email: '', phone: '', password: '', confirm: '',
      homeBranchId: null as number | null, dateOfBirth: null as Date | null,
    },
    validate: {
      firstName: (v) => (v.trim() ? null : 'Required'),
      lastName: (v) => (v.trim() ? null : 'Required'),
      email: (v) => (/^\S+@\S+\.\S+$/.test(v) ? null : 'Enter a valid email'),
      phone: (v) => (/^\+?[0-9 -]{7,20}$/.test(v) ? null : 'Enter a valid phone number'),
      password: (v) => (STRONG.test(v) ? null : '10+ characters with upper case, lower case and a digit'),
      confirm: (v, values) => (v === values.password ? null : 'Passwords do not match'),
      homeBranchId: (v) => (v ? null : 'Choose your home gym'),
    },
  });

  const submit = form.onSubmit(async (v) => {
    setLoading(true);
    setError(null);
    try {
      await register({
        firstName: v.firstName.trim(), lastName: v.lastName.trim(), email: v.email.trim(), phone: v.phone.trim(),
        password: v.password, homeBranchId: v.homeBranchId!,
        dateOfBirth: v.dateOfBirth ? dayjs(v.dateOfBirth).format('YYYY-MM-DD') : null,
      });
      if (ref) {
        // best effort: an unknown / ineligible code must not block the sign-up
        await api('/api/v1/referrals/me/claim', { method: 'POST', body: { code: ref } }).catch(() => undefined);
      }
      navigate('/me/membership', { replace: true });
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setLoading(false);
    }
  });

  return (
    <Center mih="100vh" p="md">
      <Box w="100%" maw={560}>
        <Group justify="center" gap="xs" mb="lg">
          <IconBarbell size={36} color="var(--mantine-color-teal-6)" />
          <Title order={1}>GymOps</Title>
        </Group>
        <Paper withBorder shadow="sm" p="xl" radius="lg">
          <Title order={3} mb={4}>Join the gym</Title>
          <Text c="dimmed" size="sm" mb="lg">Create your member account, then pick a membership plan.</Text>
          {ref && (
            <Alert color="teal" variant="light" mb="md">
              Invited with referral code <b>{ref}</b>: your welcome reward follows your first membership.
            </Alert>
          )}
          <form onSubmit={submit}>
            <Stack>
              <SimpleGrid cols={{ base: 1, sm: 2 }}>
                <TextInput label="First name" {...form.getInputProps('firstName')} />
                <TextInput label="Last name" {...form.getInputProps('lastName')} />
                <TextInput label="Email" autoComplete="email" {...form.getInputProps('email')} />
                <TextInput label="Phone" placeholder="+91 98765 43210" {...form.getInputProps('phone')} />
              </SimpleGrid>
              <BranchSelect publicList label="Home gym" value={form.values.homeBranchId}
                onChange={(id) => form.setFieldValue('homeBranchId', id)} error={form.errors.homeBranchId} />
              <DatePickerInput label="Date of birth (optional)" maxDate={new Date()} clearable
                {...form.getInputProps('dateOfBirth')} />
              <SimpleGrid cols={{ base: 1, sm: 2 }}>
                <PasswordInput label="Password" autoComplete="new-password" {...form.getInputProps('password')} />
                <PasswordInput label="Confirm password" autoComplete="new-password" {...form.getInputProps('confirm')} />
              </SimpleGrid>
              {error && <Alert color="red" style={{ whiteSpace: 'pre-line' }}>{error}</Alert>}
              <Button type="submit" loading={loading} size="md">Create account</Button>
            </Stack>
          </form>
          <Text size="sm" ta="center" mt="lg">
            Already a member? <Anchor component={Link} to="/login">Sign in</Anchor>
          </Text>
        </Paper>
      </Box>
    </Center>
  );
}
