import { ActionIcon, Alert, Button, Checkbox, Group, Modal, Select, SimpleGrid, Stack, Text, TextInput, Textarea } from '@mantine/core';
import { DatePickerInput } from '@mantine/dates';
import { useForm } from '@mantine/form';
import { IconPlus, IconTrash } from '@tabler/icons-react';
import dayjs from 'dayjs';
import { useEffect } from 'react';
import type { EmergencyContact, Gender, Member, MemberRequest } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { isMemberOnly } from '@/auth/roles';
import { errorMessage } from '@/lib/notify';
import { useApiMutation } from '@/lib/useApi';
import { BranchSelect } from '../BranchSelect';

interface Values {
  homeBranchId: number | null;
  firstName: string;
  lastName: string;
  email: string;
  phone: string;
  dateOfBirth: Date | null;
  gender: Gender | null;
  addressLine1: string;
  city: string;
  state: string;
  postalCode: string;
  notes: string;
  emergencyContacts: EmergencyContact[];
}

const PHONE = /^\+?[0-9 -]{7,20}$/;

/** Register a walk-in member (no `member`) or edit an existing one. */
export function MemberFormModal({ opened, onClose, member, defaultBranchId, onSaved }: {
  opened: boolean;
  onClose: () => void;
  member?: Member | null;
  defaultBranchId?: number | null;
  onSaved?: (m: Member) => void;
}) {
  const { user } = useAuth();
  const self = isMemberOnly(user);
  const form = useForm<Values>({
    initialValues: {
      homeBranchId: null, firstName: '', lastName: '', email: '', phone: '', dateOfBirth: null, gender: null,
      addressLine1: '', city: '', state: '', postalCode: '', notes: '', emergencyContacts: [],
    },
    validate: {
      homeBranchId: (v) => (v ? null : 'Required'),
      firstName: (v) => (v.trim() ? null : 'Required'),
      lastName: (v) => (v.trim() ? null : 'Required'),
      email: (v) => (/^\S+@\S+\.\S+$/.test(v) ? null : 'Invalid email'),
      phone: (v) => (PHONE.test(v) ? null : 'Invalid phone'),
      emergencyContacts: {
        name: (v) => (v.trim() ? null : 'Required'),
        relationship: (v) => (v.trim() ? null : 'Required'),
        phone: (v) => (PHONE.test(v) ? null : 'Invalid phone'),
      },
    },
  });
  const setValues = form.setValues;
  const reset = form.reset;

  useEffect(() => {
    if (!opened) return;
    if (member) {
      setValues({
        homeBranchId: member.homeBranchId, firstName: member.firstName, lastName: member.lastName, email: member.email,
        phone: member.phone, dateOfBirth: member.dateOfBirth ? new Date(member.dateOfBirth) : null, gender: member.gender,
        addressLine1: member.addressLine1 ?? '', city: member.city ?? '', state: member.state ?? '',
        postalCode: member.postalCode ?? '', notes: member.notes ?? '',
        emergencyContacts: member.emergencyContacts.map((c) => ({ ...c, email: c.email ?? '' })),
      });
    } else {
      reset();
      setValues({ homeBranchId: defaultBranchId ?? null });
    }
  }, [opened, member, defaultBranchId, setValues, reset]);

  const save = useApiMutation<MemberRequest, Member>({
    request: (body) => member
      ? { path: `/api/v1/members/${member.id}`, method: 'PUT', body }
      : { path: '/api/v1/members', body },
    invalidate: ['/api/v1/members', '/api/v1/dashboard'],
    successMessage: (m) => (member ? 'Member updated' : `Registered ${m.firstName} (${m.memberCode})`),
    silentError: true,
    onSuccess: (m) => {
      onSaved?.(m);
      onClose();
    },
  });

  const submit = form.onSubmit((v) => save.mutate({
    homeBranchId: v.homeBranchId!, firstName: v.firstName.trim(), lastName: v.lastName.trim(), email: v.email.trim(),
    phone: v.phone.trim(), dateOfBirth: v.dateOfBirth ? dayjs(v.dateOfBirth).format('YYYY-MM-DD') : null,
    gender: v.gender, addressLine1: v.addressLine1 || null, city: v.city || null, state: v.state || null,
    postalCode: v.postalCode || null, notes: self ? null : v.notes || null,
    emergencyContacts: v.emergencyContacts.map((c) => ({ ...c, email: c.email || null })),
  }));

  return (
    <Modal opened={opened} onClose={onClose} title={member ? 'Edit member' : 'Register member'} size="lg" centered>
      <form onSubmit={submit}>
        <Stack>
          <SimpleGrid cols={{ base: 1, sm: 2 }}>
            <TextInput label="First name" required {...form.getInputProps('firstName')} />
            <TextInput label="Last name" required {...form.getInputProps('lastName')} />
            <TextInput label="Email" required {...form.getInputProps('email')} />
            <TextInput label="Phone" required placeholder="+91 98765 43210" {...form.getInputProps('phone')} />
            <BranchSelect label="Home branch" value={form.values.homeBranchId} disabled={self}
              onChange={(id) => form.setFieldValue('homeBranchId', id)} error={form.errors.homeBranchId} />
            <DatePickerInput label="Date of birth" maxDate={new Date()} clearable {...form.getInputProps('dateOfBirth')} />
            <Select label="Gender" clearable data={['MALE', 'FEMALE', 'NON_BINARY', 'UNDISCLOSED'].map((g) => ({ value: g, label: g.charAt(0) + g.slice(1).toLowerCase().replace('_', '-') }))}
              {...form.getInputProps('gender')} />
            <TextInput label="City" {...form.getInputProps('city')} />
          </SimpleGrid>
          <TextInput label="Address" {...form.getInputProps('addressLine1')} />
          {!self && <Textarea label="Staff notes" description="Never shown to the member" {...form.getInputProps('notes')} />}
          <Group justify="space-between">
            <Text fw={600} size="sm">Emergency contacts</Text>
            <Button size="xs" variant="light" leftSection={<IconPlus size={14} />}
              disabled={form.values.emergencyContacts.length >= 3}
              onClick={() => form.insertListItem('emergencyContacts', {
                name: '', relationship: '', phone: '', email: '', primary: form.values.emergencyContacts.length === 0,
              })}>
              Add contact
            </Button>
          </Group>
          {form.values.emergencyContacts.map((_, i) => (
            <Group key={i} align="flex-end" wrap="nowrap">
              <TextInput label="Name" style={{ flex: 2 }} {...form.getInputProps(`emergencyContacts.${i}.name`)} />
              <TextInput label="Relationship" style={{ flex: 1.5 }} {...form.getInputProps(`emergencyContacts.${i}.relationship`)} />
              <TextInput label="Phone" style={{ flex: 2 }} {...form.getInputProps(`emergencyContacts.${i}.phone`)} />
              <Checkbox label="Primary" mb={8}
                checked={form.values.emergencyContacts[i].primary}
                onChange={(e) => form.setFieldValue('emergencyContacts', form.values.emergencyContacts.map((c, j) => ({
                  ...c, primary: j === i ? e.currentTarget.checked : e.currentTarget.checked ? false : c.primary,
                })))} />
              <ActionIcon color="red" variant="subtle" mb={4} aria-label="Remove contact"
                onClick={() => form.removeListItem('emergencyContacts', i)}>
                <IconTrash size={16} />
              </ActionIcon>
            </Group>
          ))}
          {save.error && <Alert color="red" style={{ whiteSpace: 'pre-line' }}>{errorMessage(save.error)}</Alert>}
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose}>Cancel</Button>
            <Button type="submit" loading={save.isPending}>{member ? 'Save changes' : 'Register'}</Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
