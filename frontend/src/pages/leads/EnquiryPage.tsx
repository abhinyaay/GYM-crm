import {
  Alert, Anchor, Box, Button, Center, Checkbox, Group, Paper, Select, SimpleGrid, Stack, Text, Textarea, TextInput,
  ThemeIcon, Title,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { IconBarbell, IconCircleCheck } from '@tabler/icons-react';
import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api } from '@/api/client';
import type { PublicReferral } from '@/api/growth';
import { LEAD_INTERESTS, type EnquiryRequest, type LeadInterest } from '@/api/leads';
import { BranchSelect } from '@/components/BranchSelect';
import { label } from '@/lib/format';
import { errorMessage } from '@/lib/notify';
import { useApiQuery } from '@/lib/useApi';

const NAME = /^[\p{L}\p{M} .'-]{1,80}$/u;

/**
 * Public "book a free trial" form (no login). Posts to the rate-limited enquiry endpoint, which
 * always answers with the same generic message. The `website` field is a honeypot hidden from
 * people; bots that fill it are silently discarded by the API.
 */
export default function EnquiryPage() {
  const [submitted, setSubmitted] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  // shared referral link: /enquire?ref=CODE (the API ignores unknown codes)
  const ref = (useSearchParams()[0].get('ref') ?? '').trim().slice(0, 20);
  const referral = useApiQuery<PublicReferral>(ref ? `/api/v1/public/referrals/${encodeURIComponent(ref)}` : null,
    undefined, { retry: false });
  const form = useForm({
    initialValues: {
      branchId: null as number | null, firstName: '', lastName: '', email: '', phone: '',
      interest: 'MEMBERSHIP' as LeadInterest, message: '', consent: false, website: '',
    },
    validate: {
      branchId: (v) => (v ? null : 'Choose the gym you want to visit'),
      firstName: (v) => (NAME.test(v.trim()) ? null : 'Enter your first name'),
      lastName: (v) => (NAME.test(v.trim()) ? null : 'Enter your last name'),
      email: (v) => (/^\S+@\S+\.\S+$/.test(v.trim()) ? null : 'Enter a valid email'),
      phone: (v) => (/^\+?[0-9 -]{7,20}$/.test(v.trim()) ? null : 'Enter a valid phone number'),
      message: (v) => (v.length <= 1000 ? null : 'Keep it under 1000 characters'),
      consent: (v) => (v ? null : 'Please agree so we can contact you'),
    },
  });

  const submit = form.onSubmit(async (v) => {
    setLoading(true);
    setError(null);
    const body: EnquiryRequest & { referralCode?: string } = {
      branchId: v.branchId!, firstName: v.firstName.trim(), lastName: v.lastName.trim(), email: v.email.trim(),
      phone: v.phone.trim(), interest: v.interest, message: v.message.trim(), consent: v.consent, website: v.website,
      ...(ref ? { referralCode: ref } : {}),
    };
    try {
      const res = await api<{ message: string }>('/api/v1/leads/enquiry', { method: 'POST', body, auth: false });
      setSubmitted(res.message);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setLoading(false);
    }
  });

  return (
    <Center mih="100vh" p="md">
      <Box w="100%" maw={600}>
        <Group justify="center" gap="xs" mb="lg">
          <IconBarbell size={36} color="var(--mantine-color-teal-6)" />
          <Title order={1}>GymOps</Title>
        </Group>
        <Paper withBorder shadow="sm" p="xl" radius="lg">
          {submitted ? (
            <Stack align="center" gap="sm" py="md">
              <ThemeIcon size={56} radius="xl" color="teal" variant="light">
                <IconCircleCheck size={34} />
              </ThemeIcon>
              <Title order={3} ta="center">Request received</Title>
              <Text ta="center" c="dimmed">{submitted}</Text>
              <Group mt="md">
                <Button variant="default" onClick={() => { form.reset(); setSubmitted(null); }}>Send another request</Button>
                <Button component={Link} to="/login" variant="subtle">Member sign in</Button>
              </Group>
            </Stack>
          ) : (
            <>
              {referral.data && (
                <Alert color="teal" variant="light" mb="md" title={referral.data.headline}>{referral.data.offer}</Alert>
              )}
              <Title order={3} mb={4}>Book a free trial</Title>
              <Text c="dimmed" size="sm" mb="lg">
                Tell us a little about yourself and our team will call you to set up your visit.
              </Text>
              <form onSubmit={submit} noValidate>
                <Stack>
                  <BranchSelect publicList label="Gym" placeholder="Choose a branch" value={form.values.branchId}
                    onChange={(id) => form.setFieldValue('branchId', id)} error={form.errors.branchId} withAsterisk />
                  <SimpleGrid cols={{ base: 1, sm: 2 }}>
                    <TextInput label="First name" autoComplete="given-name" withAsterisk {...form.getInputProps('firstName')} />
                    <TextInput label="Last name" autoComplete="family-name" withAsterisk {...form.getInputProps('lastName')} />
                    <TextInput label="Phone" type="tel" autoComplete="tel" placeholder="+91 98765 43210" withAsterisk
                      {...form.getInputProps('phone')} />
                    <TextInput label="Email" type="email" autoComplete="email" withAsterisk {...form.getInputProps('email')} />
                  </SimpleGrid>
                  <Select label="I'm interested in" allowDeselect={false}
                    data={LEAD_INTERESTS.map((i) => ({ value: i, label: label(i) }))}
                    {...form.getInputProps('interest')} />
                  <Textarea label="Anything we should know? (optional)" autosize minRows={2} maxRows={5}
                    placeholder="Preferred timings, goals, questions…" {...form.getInputProps('message')} />
                  {/* Honeypot: invisible to people and assistive tech; bots tend to fill every field. */}
                  <div aria-hidden="true" style={{ position: 'absolute', left: '-10000px', width: 1, height: 1, overflow: 'hidden' }}>
                    <label>
                      Website
                      <input type="text" name="website" tabIndex={-1} autoComplete="off" value={form.values.website}
                        onChange={(e) => form.setFieldValue('website', e.currentTarget.value)} />
                    </label>
                  </div>
                  <Checkbox label="I agree to be contacted by GymOps about my trial by phone, WhatsApp or email."
                    {...form.getInputProps('consent', { type: 'checkbox' })} />
                  {error && <Alert color="red" style={{ whiteSpace: 'pre-line' }}>{error}</Alert>}
                  <Button type="submit" loading={loading} size="md">Request my free trial</Button>
                </Stack>
              </form>
            </>
          )}
          <Text size="sm" ta="center" mt="lg">
            Already a member? <Anchor component={Link} to="/login">Sign in</Anchor>
          </Text>
        </Paper>
      </Box>
    </Center>
  );
}
