import {
  Alert, Badge, Button, Card, Group, Modal, NumberInput, Progress, Select, SimpleGrid, Stack, Text, TextInput, Title,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { GST_STATES, isValidGstin, type BranchGst } from '@/api/pricing';
import { IconMapPin, IconPhone, IconPlus, IconUsers } from '@tabler/icons-react';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import type { Branch, Occupancy, Page } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { hasAnyRole, ORG_WIDE } from '@/auth/roles';
import { PageHeader } from '@/components/PageHeader';
import { Pager } from '@/components/Pager';
import { QueryState } from '@/components/QueryState';
import { StatusBadge } from '@/components/StatusBadge';
import { errorMessage } from '@/lib/notify';
import { useApiMutation, useApiQuery } from '@/lib/useApi';

export default function BranchesPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const isAdmin = hasAnyRole(user, ORG_WIDE);
  const [page, setPage] = useState(0);
  const [sort, setSort] = useState('name');
  const [creating, setCreating] = useState(false);
  const query = useApiQuery<Page<Branch>>('/api/v1/branches', { page, size: 12, sort });
  const rows = query.data?.content ?? [];

  return (
    <>
      <PageHeader
        title="Branches"
        subtitle={isAdmin ? 'All gym locations' : 'Branches you are assigned to'}
        actions={
          <>
            <Select
              aria-label="Sort"
              value={sort}
              onChange={(v) => { if (v) { setSort(v); setPage(0); } }}
              allowDeselect={false}
              w={150}
              data={[
                { value: 'name', label: 'Sort: name' },
                { value: 'city', label: 'Sort: city' },
                { value: 'code', label: 'Sort: code' },
                { value: 'createdAt,desc', label: 'Sort: newest' },
              ]}
            />
            {isAdmin && <Button leftSection={<IconPlus size={16} />} onClick={() => setCreating(true)}>New branch</Button>}
          </>
        }
      />

      <QueryState isLoading={query.isLoading} error={query.error} empty={rows.length === 0} emptyText="No branches.">
        <SimpleGrid cols={{ base: 1, sm: 2, xl: 3 }}>
          {rows.map((b) => (
            <Card
              key={b.id}
              withBorder
              padding="lg"
              style={{ cursor: 'pointer' }}
              onClick={() => navigate(`/branches/${b.id}`)}
              role="link"
              aria-label={`Open ${b.name}`}
            >
              <Group justify="space-between" align="flex-start" wrap="nowrap">
                <div>
                  <Title order={5}>{b.name}</Title>
                  <Text size="xs" c="dimmed" ff="monospace">{b.code}</Text>
                </div>
                <StatusBadge value={b.status} />
              </Group>
              <Stack gap={4} mt="sm">
                <Group gap={6} wrap="nowrap">
                  <IconMapPin size={14} color="var(--mantine-color-dimmed)" />
                  <Text size="sm" lineClamp={1}>{b.addressLine1}, {b.city}{b.state ? `, ${b.state}` : ''}</Text>
                </Group>
                {b.phone && (
                  <Group gap={6} wrap="nowrap">
                    <IconPhone size={14} color="var(--mantine-color-dimmed)" />
                    <Text size="sm">{b.phone}</Text>
                  </Group>
                )}
              </Stack>
              <Group gap={6} mt="sm">
                <Badge variant="light" color="gray">{b.facilities.length} facilities</Badge>
                <Badge variant="light" color="gray">{b.timezone}</Badge>
              </Group>
              <OccupancyBar branch={b} />
            </Card>
          ))}
        </SimpleGrid>
        <Pager page={query.data} onChange={setPage} />
      </QueryState>

      <Modal opened={creating} onClose={() => setCreating(false)} title="New branch" size="lg">
        {creating && (
          <BranchForm
            branch={null}
            onDone={(b) => {
              setCreating(false);
              if (b) navigate(`/branches/${b.id}`);
            }}
          />
        )}
      </Modal>
    </>
  );
}

function OccupancyBar({ branch }: { branch: Branch }) {
  const { data, error } = useApiQuery<Occupancy>(
    branch.status === 'ACTIVE' ? `/api/v1/attendance/branches/${branch.id}/occupancy` : null,
    undefined,
    { refetchInterval: 60_000 },
  );
  const util = data?.utilisation ?? 0;
  const color = util >= 0.9 ? 'red' : util >= 0.7 ? 'orange' : 'teal';
  return (
    <Stack gap={4} mt="md">
      <Group justify="space-between">
        <Group gap={4}>
          <IconUsers size={14} />
          <Text size="xs" fw={600}>Live occupancy</Text>
        </Group>
        <Text size="xs" c="dimmed">
          {error ? 'unavailable' : data ? `${data.currentlyInside} / ${data.capacity} inside` : `capacity ${branch.capacity}`}
        </Text>
      </Group>
      <Progress value={Math.min(util * 100, 100)} color={color} size="sm" radius="xl" />
    </Stack>
  );
}

// ------------------------------------------------------------------ form (also used by BranchDetailPage for edits)

interface BranchValues {
  code: string;
  name: string;
  phone: string;
  email: string;
  addressLine1: string;
  addressLine2: string;
  city: string;
  state: string;
  postalCode: string;
  country: string;
  timezone: string;
  capacity: number | string;
  gstin: string;
  gstStateCode: string;
}

const FALLBACK_ZONES = ['Asia/Kolkata', 'Asia/Dubai', 'Asia/Singapore', 'Europe/London', 'America/New_York', 'UTC'];
function timeZones(): string[] {
  try {
    return Intl.supportedValuesOf('timeZone');
  } catch {
    return FALLBACK_ZONES;
  }
}

export function BranchForm({ branch, onDone }: { branch: Branch | null; onDone: (b: Branch | null) => void }) {
  const [zones] = useState(timeZones);
  const form = useForm<BranchValues>({
    initialValues: {
      code: branch?.code ?? '',
      name: branch?.name ?? '',
      phone: branch?.phone ?? '',
      email: branch?.email ?? '',
      addressLine1: branch?.addressLine1 ?? '',
      addressLine2: branch?.addressLine2 ?? '',
      city: branch?.city ?? '',
      state: branch?.state ?? '',
      postalCode: branch?.postalCode ?? '',
      country: branch?.country ?? 'IN',
      timezone: branch?.timezone ?? 'Asia/Kolkata',
      capacity: branch?.capacity ?? 100,
      gstin: (branch as (Branch & BranchGst) | null)?.gstin ?? '',
      gstStateCode: (branch as (Branch & BranchGst) | null)?.gstStateCode ?? '',
    },
    validate: {
      code: (v) => (/^[A-Za-z0-9-]{2,20}$/.test(v) ? null : '2-20 letters, digits or hyphens'),
      name: (v) => (!v.trim() ? 'Required' : v.length > 120 ? 'Max 120 characters' : null),
      phone: (v) => (!v || /^\+?[0-9 -]{7,20}$/.test(v) ? null : 'Invalid phone number'),
      email: (v) => (!v || /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v) ? null : 'Invalid email'),
      addressLine1: (v) => (!v.trim() ? 'Required' : v.length > 200 ? 'Max 200 characters' : null),
      addressLine2: (v) => (v.length > 200 ? 'Max 200 characters' : null),
      city: (v) => (!v.trim() ? 'Required' : v.length > 80 ? 'Max 80 characters' : null),
      state: (v) => (v.length > 80 ? 'Max 80 characters' : null),
      postalCode: (v) => (v.length > 20 ? 'Max 20 characters' : null),
      country: (v) => (/^[A-Z]{2}$/.test(v) ? null : 'ISO-3166 alpha-2, e.g. IN'),
      timezone: (v) => (v ? null : 'Required'),
      capacity: (v) => (v !== '' && Number.isInteger(Number(v)) && Number(v) >= 1 && Number(v) <= 10000 ? null : '1-10000'),
      gstin: (v) => (!v.trim() || isValidGstin(v) ? null : 'Invalid GSTIN (15 characters, check digit must match)'),
      gstStateCode: (v, vals) => (vals.gstin.trim() && isValidGstin(vals.gstin) && v && v !== vals.gstin.trim().slice(0, 2)
        ? `The GSTIN is registered in state ${vals.gstin.trim().slice(0, 2)}` : null),
    },
  });
  const save = useApiMutation<BranchValues, Branch>({
    request: (v) => ({
      path: branch ? `/api/v1/branches/${branch.id}` : '/api/v1/branches',
      method: branch ? 'PUT' : 'POST',
      body: {
        code: v.code.trim(),
        name: v.name.trim(),
        phone: v.phone.trim() || null,
        email: v.email.trim() || null,
        addressLine1: v.addressLine1.trim(),
        addressLine2: v.addressLine2.trim() || null,
        city: v.city.trim(),
        state: v.state.trim() || null,
        postalCode: v.postalCode.trim() || null,
        country: v.country,
        timezone: v.timezone,
        capacity: Number(v.capacity),
        gstin: v.gstin.trim().toUpperCase() || null,
        gstStateCode: v.gstStateCode || null,
      },
    }),
    invalidate: ['/api/v1/branches'],
    successMessage: (b) => (branch ? `${b.name} updated` : `${b.name} created`),
    silentError: true,
    onSuccess: (b) => onDone(b),
  });
  return (
    <form onSubmit={form.onSubmit((v) => save.mutate(v))}>
      <Stack>
        <SimpleGrid cols={{ base: 1, sm: 2 }}>
          <TextInput label="Code" placeholder="BLR-KOR" withAsterisk {...form.getInputProps('code')} />
          <TextInput label="Name" placeholder="GymOps Koramangala" withAsterisk {...form.getInputProps('name')} />
          <TextInput label="Phone" placeholder="+91 80000 10000" {...form.getInputProps('phone')} />
          <TextInput label="Email" type="email" {...form.getInputProps('email')} />
        </SimpleGrid>
        <TextInput label="Address line 1" withAsterisk {...form.getInputProps('addressLine1')} />
        <TextInput label="Address line 2" {...form.getInputProps('addressLine2')} />
        <SimpleGrid cols={{ base: 1, sm: 3 }}>
          <TextInput label="City" withAsterisk {...form.getInputProps('city')} />
          <TextInput label="State" {...form.getInputProps('state')} />
          <TextInput label="Postal code" {...form.getInputProps('postalCode')} />
          <TextInput
            label="Country"
            description="2-letter code"
            maxLength={2}
            withAsterisk
            {...form.getInputProps('country')}
            onChange={(e) => form.setFieldValue('country', e.currentTarget.value.toUpperCase())}
          />
          <Select label="Timezone" description="IANA zone" searchable withAsterisk data={zones} allowDeselect={false}
            {...form.getInputProps('timezone')} />
          <NumberInput label="Capacity" description="Max members inside" min={1} max={10000} allowDecimal={false} withAsterisk
            {...form.getInputProps('capacity')} />
        </SimpleGrid>
        <SimpleGrid cols={{ base: 1, sm: 2 }}>
          <TextInput label="GSTIN" placeholder="27AAPFU0939F1ZV" maxLength={15}
            description="Printed on tax invoices; leave empty if the branch is not GST-registered"
            {...form.getInputProps('gstin')}
            onChange={(e) => {
              const g = e.currentTarget.value.toUpperCase();
              form.setFieldValue('gstin', g);
              if (isValidGstin(g)) form.setFieldValue('gstStateCode', g.slice(0, 2));
            }} />
          <Select label="GST state" description="Decides CGST + SGST vs IGST; taken from the GSTIN when set"
            data={GST_STATES} searchable clearable {...form.getInputProps('gstStateCode')} />
        </SimpleGrid>
        {save.error && <Alert color="red" style={{ whiteSpace: 'pre-line' }}>{errorMessage(save.error)}</Alert>}
        <Group justify="flex-end">
          <Button variant="default" onClick={() => onDone(null)}>Cancel</Button>
          <Button type="submit" loading={save.isPending}>{branch ? 'Save changes' : 'Create branch'}</Button>
        </Group>
      </Stack>
    </form>
  );
}
