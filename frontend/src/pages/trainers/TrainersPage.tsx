import {
  Alert, Avatar, Badge, Button, Group, Modal, NumberInput, Select, SimpleGrid, Stack, Table, TagsInput, Text, Textarea,
  TextInput,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { useDebouncedValue } from '@mantine/hooks';
import { IconPlus, IconSearch } from '@tabler/icons-react';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import type { Page, StaffMember, Trainer, TrainerStatus, UserResponse } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { hasAnyRole, MANAGERS, ORG_WIDE } from '@/auth/roles';
import { BranchSelect, useBranchNames } from '@/components/BranchSelect';
import { PageHeader } from '@/components/PageHeader';
import { Pager } from '@/components/Pager';
import { QueryState } from '@/components/QueryState';
import { StatusBadge } from '@/components/StatusBadge';
import { label, money } from '@/lib/format';
import { errorMessage } from '@/lib/notify';
import { useApiMutation, useApiQuery } from '@/lib/useApi';

const STATUSES: TrainerStatus[] = ['ACTIVE', 'ON_LEAVE', 'INACTIVE'];
const SPECIALIZATION_SUGGESTIONS = [
  'STRENGTH', 'WEIGHT_LOSS', 'HIIT', 'YOGA', 'PILATES', 'CROSSFIT', 'BODYBUILDING', 'REHABILITATION', 'NUTRITION',
  'CARDIO', 'MOBILITY', 'SENIOR_FITNESS',
];

const initials = (name: string) => name.split(/\s+/).filter(Boolean).slice(0, 2).map((p) => p[0]).join('').toUpperCase();

export default function TrainersPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const branchNames = useBranchNames();
  const canCreate = hasAnyRole(user, MANAGERS);
  const [branchId, setBranchId] = useState<number | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [specialization, setSpecialization] = useState('');
  const [debouncedSpec] = useDebouncedValue(specialization.trim(), 300);
  const [page, setPage] = useState(0);
  const [creating, setCreating] = useState(false);

  const query = useApiQuery<Page<Trainer>>('/api/v1/trainers', {
    branchId: branchId ?? undefined, status: status ?? undefined, specialization: debouncedSpec || undefined, page, size: 20,
  });
  const rows = query.data?.content ?? [];

  return (
    <>
      <PageHeader
        title="Trainers"
        subtitle="Coaching staff, their specialisations and client capacity"
        actions={canCreate && (
          <Button leftSection={<IconPlus size={16} />} onClick={() => setCreating(true)}>Add trainer profile</Button>
        )}
      />

      <Group mb="md" align="flex-end" wrap="wrap">
        <BranchSelect
          label="Branch"
          value={branchId}
          onChange={(v) => { setBranchId(v); setPage(0); }}
          allowAll
          clearable={!hasAnyRole(user, ORG_WIDE)}
          placeholder="All my branches"
          w={220}
        />
        <Select
          label="Status"
          placeholder="Any status"
          clearable
          data={STATUSES.map((s) => ({ value: s, label: label(s) }))}
          value={status}
          onChange={(v) => { setStatus(v); setPage(0); }}
          w={160}
        />
        <TextInput
          label="Specialisation"
          placeholder="e.g. HIIT"
          leftSection={<IconSearch size={14} />}
          value={specialization}
          onChange={(e) => { setSpecialization(e.currentTarget.value); setPage(0); }}
          w={200}
        />
      </Group>

      <QueryState isLoading={query.isLoading} error={query.error} empty={rows.length === 0} emptyText="No trainers match these filters.">
        <Table.ScrollContainer minWidth={820}>
          <Table striped highlightOnHover verticalSpacing="sm">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Trainer</Table.Th>
                <Table.Th>Employee code</Table.Th>
                <Table.Th>Home branch</Table.Th>
                <Table.Th>Specialisations</Table.Th>
                <Table.Th ta="right">Experience</Table.Th>
                <Table.Th ta="right">Rate / hr</Table.Th>
                <Table.Th ta="right">Max clients</Table.Th>
                <Table.Th>Status</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((t) => (
                <Table.Tr key={t.id} style={{ cursor: 'pointer' }} onClick={() => navigate(`/trainers/${t.id}`)}>
                  <Table.Td>
                    <Group gap="sm" wrap="nowrap">
                      <Avatar color="teal" radius="xl" size="md">{initials(t.fullName)}</Avatar>
                      <div>
                        <Text size="sm" fw={600}>{t.fullName}</Text>
                        <Text size="xs" c="dimmed">{t.email}</Text>
                      </div>
                    </Group>
                  </Table.Td>
                  <Table.Td><Text size="sm" ff="monospace">{t.employeeCode}</Text></Table.Td>
                  <Table.Td><Text size="sm">{branchNames[t.homeBranchId] ?? `#${t.homeBranchId}`}</Text></Table.Td>
                  <Table.Td>
                    <Group gap={4}>
                      {t.specializations.length === 0 && <Text size="xs" c="dimmed">—</Text>}
                      {t.specializations.map((s) => <Badge key={s} size="xs" variant="light" color="indigo">{label(s)}</Badge>)}
                    </Group>
                  </Table.Td>
                  <Table.Td ta="right"><Text size="sm">{t.yearsOfExperience} yrs</Text></Table.Td>
                  <Table.Td ta="right"><Text size="sm">{money(t.hourlyRate)}</Text></Table.Td>
                  <Table.Td ta="right"><Text size="sm">{t.maxActiveClients}</Text></Table.Td>
                  <Table.Td><StatusBadge value={t.status} /></Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
        <Pager page={query.data} onChange={setPage} />
      </QueryState>

      <Modal opened={creating} onClose={() => setCreating(false)} title="Add trainer profile" size="lg">
        {creating && (
          <CreateTrainerForm
            onDone={(t) => {
              setCreating(false);
              if (t) navigate(`/trainers/${t.id}`);
            }}
          />
        )}
      </Modal>
    </>
  );
}

// ------------------------------------------------------------------ create

interface TrainerFormValues {
  homeBranchId: number | null;
  userId: string | null;
  employeeCode: string;
  bio: string;
  certifications: string;
  yearsOfExperience: number | string;
  hourlyRate: number | string;
  maxActiveClients: number | string;
  specializations: string[];
}

function CreateTrainerForm({ onDone }: { onDone: (created: Trainer | null) => void }) {
  const { user } = useAuth();
  const orgWide = hasAnyRole(user, ORG_WIDE);
  const form = useForm<TrainerFormValues>({
    initialValues: {
      homeBranchId: user?.branchIds.length === 1 ? user.branchIds[0] : null,
      userId: null,
      employeeCode: '',
      bio: '',
      certifications: '',
      yearsOfExperience: 0,
      hourlyRate: '',
      maxActiveClients: 25,
      specializations: [],
    },
    validate: {
      homeBranchId: (v) => (v ? null : 'Select a branch'),
      userId: (v) => (v ? null : 'Select a user with the TRAINER role'),
      employeeCode: (v) => (!v.trim() ? 'Required' : v.length > 20 ? 'Max 20 characters' : null),
      bio: (v) => (v.length > 1000 ? 'Max 1000 characters' : null),
      certifications: (v) => (v.length > 500 ? 'Max 500 characters' : null),
      yearsOfExperience: (v) => (Number.isInteger(Number(v)) && Number(v) >= 0 && Number(v) <= 60 ? null : '0-60'),
      hourlyRate: (v) => (v === '' || Number(v) >= 0 ? null : 'Must be ≥ 0'),
      maxActiveClients: (v) => (v === '' || (Number.isInteger(Number(v)) && Number(v) >= 1 && Number(v) <= 200) ? null : '1-200'),
      specializations: (v) => (v.length > 10 ? 'At most 10' : v.some((s) => s.length > 50) ? 'Max 50 characters each' : null),
    },
  });
  const branchId = form.values.homeBranchId;

  // Candidate users: admins can search users by role; managers only see their branch staff.
  const trainerUsers = useApiQuery<Page<UserResponse>>(orgWide ? '/api/v1/users' : null, { role: 'TRAINER', size: 100 });
  const branchStaff = useApiQuery<StaffMember[]>(!orgWide && branchId ? `/api/v1/branches/${branchId}/staff` : null);
  const existing = useApiQuery<Page<Trainer>>('/api/v1/trainers', { size: 100 });
  const taken = new Set((existing.data?.content ?? []).map((t) => t.userId));
  const candidates = orgWide
    ? (trainerUsers.data?.content ?? []).filter((u) => u.status === 'ACTIVE')
      .map((u) => ({ id: u.id, fullName: u.fullName, email: u.email }))
    : (branchStaff.data ?? []).filter((s) => s.roles.includes('TRAINER'))
      .map((s) => ({ id: s.userId, fullName: s.fullName, email: s.email }));
  const available = candidates.filter((c) => !taken.has(c.id));

  const create = useApiMutation<TrainerFormValues, Trainer>({
    request: (v) => ({
      path: '/api/v1/trainers',
      body: {
        userId: Number(v.userId),
        homeBranchId: v.homeBranchId,
        employeeCode: v.employeeCode.trim(),
        bio: v.bio.trim() || null,
        certifications: v.certifications.trim() || null,
        yearsOfExperience: Number(v.yearsOfExperience) || 0,
        hourlyRate: v.hourlyRate === '' ? null : Number(v.hourlyRate),
        maxActiveClients: v.maxActiveClients === '' ? null : Number(v.maxActiveClients),
        specializations: v.specializations.map((s) => s.trim().toUpperCase().replace(/\s+/g, '_')),
      },
    }),
    invalidate: ['/api/v1/trainers'],
    successMessage: (t) => `Trainer profile created for ${t.fullName}`,
    silentError: true,
    onSuccess: (t) => onDone(t),
  });

  return (
    <form onSubmit={form.onSubmit((v) => create.mutate(v))}>
      <Stack>
        <Text size="sm" c="dimmed">
          {orgWide
            ? 'The user must already hold the TRAINER role. Create the account on the Users page first if needed.'
            : 'The user must already hold the TRAINER role and be assigned to the selected branch. Ask an administrator to create the account if needed.'}
        </Text>
        <SimpleGrid cols={{ base: 1, sm: 2 }}>
          <BranchSelect
            label="Home branch"
            withAsterisk
            value={form.values.homeBranchId}
            onChange={(v) => { form.setFieldValue('homeBranchId', v); if (!orgWide) form.setFieldValue('userId', null); }}
            error={form.errors.homeBranchId}
          />
          <Select
            label="Trainer user"
            withAsterisk
            searchable
            placeholder={!orgWide && !branchId ? 'Pick a branch first' : 'Select user'}
            disabled={!orgWide && !branchId}
            data={available.map((c) => ({ value: String(c.id), label: `${c.fullName} · ${c.email}` }))}
            nothingFoundMessage="No TRAINER users without a profile"
            {...form.getInputProps('userId')}
          />
          <TextInput label="Employee code" placeholder="EMP-BLR-KOR-3" withAsterisk maxLength={20} {...form.getInputProps('employeeCode')} />
          <NumberInput label="Years of experience" min={0} max={60} allowDecimal={false} {...form.getInputProps('yearsOfExperience')} />
          <NumberInput label="Hourly rate (₹)" min={0} decimalScale={2} thousandSeparator="," {...form.getInputProps('hourlyRate')} />
          <NumberInput label="Max active clients" min={1} max={200} allowDecimal={false} {...form.getInputProps('maxActiveClients')} />
        </SimpleGrid>
        <TagsInput
          label="Specialisations"
          description="Up to 10; stored upper-case (e.g. STRENGTH, WEIGHT_LOSS)"
          data={SPECIALIZATION_SUGGESTIONS}
          maxTags={10}
          clearable
          {...form.getInputProps('specializations')}
        />
        <TextInput label="Certifications" placeholder="ACE-CPT, NASM-CES" {...form.getInputProps('certifications')} />
        <Textarea label="Bio" autosize minRows={3} {...form.getInputProps('bio')} />
        {create.error && <Alert color="red" style={{ whiteSpace: 'pre-line' }}>{errorMessage(create.error)}</Alert>}
        <Group justify="flex-end">
          <Button variant="default" onClick={() => onDone(null)}>Cancel</Button>
          <Button type="submit" loading={create.isPending}>Create profile</Button>
        </Group>
      </Stack>
    </form>
  );
}
