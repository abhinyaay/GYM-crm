import {
  ActionIcon, Alert, Anchor, Badge, Button, Card, Center, Group, Loader, Menu, Modal, Paper, Select, SimpleGrid, Stack,
  Switch, Table, Tabs, Text, Textarea, TextInput, Tooltip,
} from '@mantine/core';
import { TimeInput } from '@mantine/dates';
import { useForm } from '@mantine/form';
import type { BranchGst } from '@/api/pricing';
import {
  IconArrowLeft, IconBuildingStore, IconChevronDown, IconClock, IconDoorEnter, IconEdit, IconPlus, IconTrash, IconUserMinus,
  IconUserPlus, IconUsers, IconUsersGroup, IconHourglass, IconTools,
} from '@tabler/icons-react';
import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import type { Branch, BranchHours, BranchStatus, DailySummary, Facility, Page, StaffMember, UserResponse } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { hasAnyRole, MANAGERS, ORG_WIDE } from '@/auth/roles';
import { PageHeader } from '@/components/PageHeader';
import { QueryState } from '@/components/QueryState';
import { StatCard } from '@/components/StatCard';
import { StatusBadge } from '@/components/StatusBadge';
import { date, dateTime, label, pct, WEEKDAYS } from '@/lib/format';
import { errorMessage } from '@/lib/notify';
import { useApiMutation, useApiQuery } from '@/lib/useApi';
import { BranchForm } from './BranchesPage';

const STATUSES: BranchStatus[] = ['ACTIVE', 'UNDER_MAINTENANCE', 'INACTIVE'];

export default function BranchDetailPage() {
  const { id } = useParams();
  const { user } = useAuth();
  const isAdmin = hasAnyRole(user, ORG_WIDE);
  const isManager = hasAnyRole(user, MANAGERS);
  const path = id ? `/api/v1/branches/${id}` : null;
  const branchQuery = useApiQuery<Branch>(path);
  const summary = useApiQuery<DailySummary>(id ? `/api/v1/attendance/branches/${id}/daily-summary` : null, undefined,
    { refetchInterval: 60_000 });
  const [tab, setTab] = useState<string | null>('overview');
  const [editing, setEditing] = useState(false);
  const [newStatus, setNewStatus] = useState<BranchStatus | null>(null);
  const b = branchQuery.data;

  const changeStatus = useApiMutation<BranchStatus, Branch>({
    request: (status) => ({ path: `/api/v1/branches/${id}/status`, method: 'PATCH', body: { status } }),
    invalidate: ['/api/v1/branches'],
    successMessage: (r) => `${r.name} is now ${label(r.status).toLowerCase()}`,
    onSuccess: () => setNewStatus(null),
  });

  if (!b) {
    return (
      <>
        <BackLink />
        <QueryState isLoading={branchQuery.isLoading} error={branchQuery.error}>
          <Center py="xl"><Loader size="sm" /></Center>
        </QueryState>
      </>
    );
  }

  const s = summary.data;
  return (
    <>
      <BackLink />
      <PageHeader
        title={<Group gap="sm"><span>{b.name}</span><StatusBadge value={b.status} size="lg" /></Group>}
        subtitle={`${b.code} · ${b.city}${b.state ? `, ${b.state}` : ''} · ${b.timezone}`}
        actions={isAdmin && (
          <>
            <Button variant="light" leftSection={<IconEdit size={16} />} onClick={() => setEditing(true)}>Edit</Button>
            <Menu position="bottom-end" withinPortal>
              <Menu.Target>
                <Button variant="default" rightSection={<IconChevronDown size={14} />}>Change status</Button>
              </Menu.Target>
              <Menu.Dropdown>
                {STATUSES.filter((st) => st !== b.status).map((st) => (
                  <Menu.Item key={st} onClick={() => setNewStatus(st)} color={st === 'INACTIVE' ? 'red' : undefined}>
                    {label(st)}
                  </Menu.Item>
                ))}
              </Menu.Dropdown>
            </Menu>
          </>
        )}
      />

      <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} mb="lg">
        <StatCard label="Check-ins today" value={s?.checkIns ?? '—'} icon={<IconDoorEnter size={20} />}
          hint={s ? `${s.uniqueMembers} unique members · ${date(s.date)}` : undefined} />
        <StatCard label="Inside now" value={s ? `${s.currentlyInside} / ${s.capacity}` : '—'} icon={<IconUsers size={20} />}
          color="blue" hint={s && s.capacity > 0 ? `${pct(s.currentlyInside / s.capacity, 0)} of capacity` : undefined} />
        <StatCard label="Avg. visit" value={s ? `${Math.round(s.averageVisitMinutes)} min` : '—'} icon={<IconHourglass size={20} />}
          color="grape" />
        <StatCard label="Facilities" value={`${b.facilities.filter((f) => f.available).length} / ${b.facilities.length}`}
          icon={<IconTools size={20} />} color="orange" hint="available" />
      </SimpleGrid>

      <Tabs value={tab} onChange={setTab} keepMounted={false}>
        <Tabs.List mb="md">
          <Tabs.Tab value="overview" leftSection={<IconBuildingStore size={16} />}>Overview</Tabs.Tab>
          <Tabs.Tab value="hours" leftSection={<IconClock size={16} />}>Operating hours</Tabs.Tab>
          <Tabs.Tab value="facilities" leftSection={<IconTools size={16} />}>Facilities</Tabs.Tab>
          {isManager && <Tabs.Tab value="staff" leftSection={<IconUsersGroup size={16} />}>Staff</Tabs.Tab>}
        </Tabs.List>
        <Tabs.Panel value="overview"><Overview branch={b} /></Tabs.Panel>
        <Tabs.Panel value="hours">
          <HoursEditor key={JSON.stringify(b.operatingHours)} branch={b} canEdit={isManager} />
        </Tabs.Panel>
        <Tabs.Panel value="facilities"><Facilities branch={b} canEdit={isManager} /></Tabs.Panel>
        {isManager && <Tabs.Panel value="staff"><Staff branch={b} canEdit={isAdmin} /></Tabs.Panel>}
      </Tabs>

      <Modal opened={editing} onClose={() => setEditing(false)} title={`Edit ${b.name}`} size="lg">
        {editing && <BranchForm branch={b} onDone={() => setEditing(false)} />}
      </Modal>

      <Modal opened={newStatus !== null} onClose={() => setNewStatus(null)} title="Change branch status" centered>
        {newStatus && (
          <Stack>
            <Text size="sm">
              Change <b>{b.name}</b> from {label(b.status).toLowerCase()} to <b>{label(newStatus).toLowerCase()}</b>?
              {newStatus !== 'ACTIVE' && ' Members will not be able to check in and the branch is hidden from sign-up.'}
            </Text>
            {changeStatus.error && <Alert color="red">{errorMessage(changeStatus.error)}</Alert>}
            <Group justify="flex-end">
              <Button variant="default" onClick={() => setNewStatus(null)}>Cancel</Button>
              <Button color={newStatus === 'ACTIVE' ? 'teal' : 'red'} loading={changeStatus.isPending}
                onClick={() => changeStatus.mutate(newStatus)}>
                Set {label(newStatus).toLowerCase()}
              </Button>
            </Group>
          </Stack>
        )}
      </Modal>
    </>
  );
}

function BackLink() {
  return (
    <Anchor component={Link} to="/branches" size="sm" mb="xs" display="inline-flex" style={{ alignItems: 'center', gap: 4 }}>
      <IconArrowLeft size={14} /> All branches
    </Anchor>
  );
}

function Field({ k, v }: { k: string; v: string | null | undefined }) {
  return (
    <div>
      <Text size="xs" c="dimmed" tt="uppercase" fw={600}>{k}</Text>
      <Text size="sm">{v || '—'}</Text>
    </div>
  );
}

function Overview({ branch: b }: { branch: Branch }) {
  return (
    <Card withBorder padding="lg">
      <SimpleGrid cols={{ base: 1, sm: 2, md: 3 }} spacing="lg">
        <Field k="Address" v={[b.addressLine1, b.addressLine2].filter(Boolean).join(', ')} />
        <Field k="City / state" v={[b.city, b.state].filter(Boolean).join(', ')} />
        <Field k="Postal code · country" v={`${b.postalCode ?? '—'} · ${b.country}`} />
        <Field k="Phone" v={b.phone} />
        <Field k="Email" v={b.email} />
        <Field k="Timezone" v={b.timezone} />
        <Field k="Capacity" v={`${b.capacity} members`} />
        <Field k="GSTIN · GST state" v={`${(b as Branch & BranchGst).gstin ?? 'Not registered'} · ${(b as Branch & BranchGst).gstStateCode ?? '—'}`} />
        <Field k="Created" v={dateTime(b.createdAt)} />
        <Field k="Last updated" v={dateTime(b.updatedAt)} />
      </SimpleGrid>
    </Card>
  );
}

// ------------------------------------------------------------------ operating hours

interface DayDraft { dayOfWeek: number; openTime: string; closeTime: string; closed: boolean }

function HoursEditor({ branch, canEdit }: { branch: Branch; canEdit: boolean }) {
  const initial = useMemo<DayDraft[]>(() => [1, 2, 3, 4, 5, 6, 7].map((d) => {
    const h: BranchHours | undefined = branch.operatingHours.find((x) => x.dayOfWeek === d);
    return h
      ? { dayOfWeek: d, openTime: h.openTime?.slice(0, 5) ?? '06:00', closeTime: h.closeTime?.slice(0, 5) ?? '22:00', closed: h.closed }
      : { dayOfWeek: d, openTime: '06:00', closeTime: '22:00', closed: true };
  }), [branch.operatingHours]);
  const [days, setDays] = useState(initial);
  const errors = Object.fromEntries(days.map((d) => [
    d.dayOfWeek,
    d.closed ? null : !d.openTime || !d.closeTime ? 'Open and close times required' : d.openTime >= d.closeTime ? 'Open must be before close' : null,
  ]));
  const hasErrors = Object.values(errors).some(Boolean);
  const dirty = JSON.stringify(days) !== JSON.stringify(initial);
  const update = (d: number, patch: Partial<DayDraft>) => setDays((list) => list.map((x) => (x.dayOfWeek === d ? { ...x, ...patch } : x)));

  const save = useApiMutation<void, Branch>({
    request: () => ({
      path: `/api/v1/branches/${branch.id}/operating-hours`,
      method: 'PUT',
      body: {
        days: days.map((d) => ({
          dayOfWeek: d.dayOfWeek,
          openTime: d.closed ? null : d.openTime,
          closeTime: d.closed ? null : d.closeTime,
          closed: d.closed,
        })),
      },
    }),
    invalidate: ['/api/v1/branches'],
    successMessage: 'Operating hours saved',
    silentError: true,
  });

  return (
    <Paper withBorder p="lg" maw={640}>
      <Stack gap="sm">
        {days.map((d) => (
          <div key={d.dayOfWeek}>
            <Group gap="md" wrap="wrap">
              <Text fw={600} w={40}>{WEEKDAYS[d.dayOfWeek - 1]}</Text>
              <Switch label="Open" checked={!d.closed} disabled={!canEdit} w={80}
                onChange={(e) => update(d.dayOfWeek, { closed: !e.currentTarget.checked })} />
              {d.closed ? (
                <Badge color="gray" variant="light">Closed</Badge>
              ) : (
                <Group gap="xs">
                  <TimeInput w={120} value={d.openTime} disabled={!canEdit} aria-label="Opens" error={!!errors[d.dayOfWeek]}
                    onChange={(e) => update(d.dayOfWeek, { openTime: e.currentTarget.value })} />
                  <Text size="sm" c="dimmed">to</Text>
                  <TimeInput w={120} value={d.closeTime} disabled={!canEdit} aria-label="Closes" error={!!errors[d.dayOfWeek]}
                    onChange={(e) => update(d.dayOfWeek, { closeTime: e.currentTarget.value })} />
                </Group>
              )}
            </Group>
            {errors[d.dayOfWeek] && <Text size="xs" c="red" ml={56}>{errors[d.dayOfWeek]}</Text>}
          </div>
        ))}
        {save.error && <Alert color="red" style={{ whiteSpace: 'pre-line' }}>{errorMessage(save.error)}</Alert>}
        {canEdit && (
          <Group justify="flex-end" mt="sm">
            <Button variant="subtle" size="xs" disabled={days[0].closed}
              onClick={() => setDays((list) => list.map((x) => (x.dayOfWeek <= 5 ? { ...days[0], dayOfWeek: x.dayOfWeek } : x)))}>
              Copy Monday to weekdays
            </Button>
            <Button variant="default" disabled={!dirty} onClick={() => { setDays(initial); save.reset(); }}>Reset</Button>
            <Button disabled={!dirty || hasErrors} loading={save.isPending} onClick={() => save.mutate()}>Save hours</Button>
          </Group>
        )}
      </Stack>
    </Paper>
  );
}

// ------------------------------------------------------------------ facilities

function Facilities({ branch, canEdit }: { branch: Branch; canEdit: boolean }) {
  const [editing, setEditing] = useState<Facility | 'new' | null>(null);
  const [removing, setRemoving] = useState<Facility | null>(null);
  const base = `/api/v1/branches/${branch.id}/facilities`;
  const toggle = useApiMutation<Facility, Branch>({
    request: (f) => ({ path: `${base}/${f.id}`, method: 'PUT', body: { name: f.name, description: f.description, available: !f.available } }),
    invalidate: ['/api/v1/branches'],
  });
  const remove = useApiMutation<number>({
    request: (fid) => ({ path: `${base}/${fid}`, method: 'DELETE' }),
    invalidate: ['/api/v1/branches'],
    successMessage: 'Facility removed',
    onSuccess: () => setRemoving(null),
  });
  const sorted = [...branch.facilities].sort((a, b) => a.name.localeCompare(b.name));

  return (
    <>
      {canEdit && (
        <Group justify="flex-end" mb="sm">
          <Button size="xs" leftSection={<IconPlus size={14} />} onClick={() => setEditing('new')}>Add facility</Button>
        </Group>
      )}
      {sorted.length === 0 ? (
        <Text c="dimmed" ta="center" py="xl">No facilities recorded.</Text>
      ) : (
        <Table.ScrollContainer minWidth={520}>
          <Table striped verticalSpacing="sm">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Facility</Table.Th>
                <Table.Th>Description</Table.Th>
                <Table.Th>Available</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {sorted.map((f) => (
                <Table.Tr key={f.id}>
                  <Table.Td><Text size="sm" fw={600}>{f.name}</Text></Table.Td>
                  <Table.Td><Text size="sm" c="dimmed">{f.description ?? '—'}</Text></Table.Td>
                  <Table.Td>
                    {canEdit ? (
                      <Switch checked={f.available} onChange={() => toggle.mutate(f)}
                        disabled={toggle.isPending && toggle.variables?.id === f.id} aria-label={`${f.name} available`} />
                    ) : (
                      <Badge variant="light" color={f.available ? 'teal' : 'gray'}>{f.available ? 'Available' : 'Unavailable'}</Badge>
                    )}
                  </Table.Td>
                  <Table.Td>
                    {canEdit && (
                      <Group gap={4} justify="flex-end" wrap="nowrap">
                        <ActionIcon variant="subtle" onClick={() => setEditing(f)} aria-label="Edit facility"><IconEdit size={16} /></ActionIcon>
                        <ActionIcon variant="subtle" color="red" onClick={() => setRemoving(f)} aria-label="Remove facility">
                          <IconTrash size={16} />
                        </ActionIcon>
                      </Group>
                    )}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}

      <Modal opened={editing !== null} onClose={() => setEditing(null)} title={editing === 'new' ? 'Add facility' : 'Edit facility'} centered>
        {editing !== null && (
          <FacilityForm branchId={branch.id} facility={editing === 'new' ? null : editing} onDone={() => setEditing(null)} />
        )}
      </Modal>
      <Modal opened={removing !== null} onClose={() => setRemoving(null)} title="Remove facility" centered>
        {removing && (
          <Stack>
            <Text size="sm">Remove <b>{removing.name}</b> from {branch.name}? To hide it temporarily, mark it unavailable instead.</Text>
            <Group justify="flex-end">
              <Button variant="default" onClick={() => setRemoving(null)}>Cancel</Button>
              <Button color="red" loading={remove.isPending} onClick={() => remove.mutate(removing.id)}>Remove</Button>
            </Group>
          </Stack>
        )}
      </Modal>
    </>
  );
}

function FacilityForm({ branchId, facility, onDone }: { branchId: number; facility: Facility | null; onDone: () => void }) {
  const form = useForm({
    initialValues: { name: facility?.name ?? '', description: facility?.description ?? '', available: facility?.available ?? true },
    validate: {
      name: (v) => (!v.trim() ? 'Required' : v.length > 80 ? 'Max 80 characters' : null),
      description: (v) => (v.length > 300 ? 'Max 300 characters' : null),
    },
  });
  const save = useApiMutation<typeof form.values, Branch>({
    request: (v) => ({
      path: facility ? `/api/v1/branches/${branchId}/facilities/${facility.id}` : `/api/v1/branches/${branchId}/facilities`,
      method: facility ? 'PUT' : 'POST',
      body: { name: v.name.trim(), description: v.description.trim() || null, available: v.available },
    }),
    invalidate: ['/api/v1/branches'],
    successMessage: facility ? 'Facility updated' : 'Facility added',
    silentError: true,
    onSuccess: onDone,
  });
  return (
    <form onSubmit={form.onSubmit((v) => save.mutate(v))}>
      <Stack>
        <TextInput label="Name" placeholder="Steam room" withAsterisk {...form.getInputProps('name')} />
        <Textarea label="Description" autosize minRows={2} {...form.getInputProps('description')} />
        <Switch label="Available to members" {...form.getInputProps('available', { type: 'checkbox' })} />
        {save.error && <Alert color="red">{errorMessage(save.error)}</Alert>}
        <Group justify="flex-end">
          <Button variant="default" onClick={onDone}>Cancel</Button>
          <Button type="submit" loading={save.isPending}>Save</Button>
        </Group>
      </Stack>
    </form>
  );
}

// ------------------------------------------------------------------ staff

function Staff({ branch, canEdit }: { branch: Branch; canEdit: boolean }) {
  const staff = useApiQuery<StaffMember[]>(`/api/v1/branches/${branch.id}/staff`);
  const [assigning, setAssigning] = useState(false);
  const [removing, setRemoving] = useState<StaffMember | null>(null);
  const unassign = useApiMutation<number>({
    request: (userId) => ({ path: `/api/v1/branches/${branch.id}/staff/${userId}`, method: 'DELETE' }),
    invalidate: [`/api/v1/branches/${branch.id}/staff`, '/api/v1/users'],
    successMessage: 'Staff member unassigned',
    onSuccess: () => setRemoving(null),
  });
  const rows = staff.data ?? [];

  return (
    <>
      {canEdit && (
        <Group justify="flex-end" mb="sm">
          <Button size="xs" leftSection={<IconUserPlus size={14} />} onClick={() => setAssigning(true)}>Assign staff</Button>
        </Group>
      )}
      <QueryState isLoading={staff.isLoading} error={staff.error} empty={rows.length === 0} emptyText="No staff assigned.">
        <Table.ScrollContainer minWidth={560}>
          <Table striped verticalSpacing="sm">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Name</Table.Th>
                <Table.Th>Roles</Table.Th>
                <Table.Th>Assigned</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((m) => (
                <Table.Tr key={m.userId}>
                  <Table.Td>
                    <Text size="sm" fw={600}>{m.fullName}</Text>
                    <Text size="xs" c="dimmed">{m.email}</Text>
                  </Table.Td>
                  <Table.Td>
                    <Group gap={4}>{m.roles.map((r) => <Badge key={r} size="sm" variant="light">{label(r)}</Badge>)}</Group>
                  </Table.Td>
                  <Table.Td><Text size="sm">{date(m.assignedAt)}</Text></Table.Td>
                  <Table.Td>
                    {canEdit && (
                      <Group justify="flex-end">
                        <Tooltip label="Unassign from branch">
                          <ActionIcon variant="subtle" color="red" onClick={() => setRemoving(m)} aria-label="Unassign">
                            <IconUserMinus size={16} />
                          </ActionIcon>
                        </Tooltip>
                      </Group>
                    )}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      </QueryState>

      <Modal opened={assigning} onClose={() => setAssigning(false)} title={`Assign staff to ${branch.name}`} centered>
        {assigning && (
          <AssignStaffForm branchId={branch.id} assigned={new Set(rows.map((r) => r.userId))} onDone={() => setAssigning(false)} />
        )}
      </Modal>
      <Modal opened={removing !== null} onClose={() => setRemoving(null)} title="Unassign staff" centered>
        {removing && (
          <Stack>
            <Text size="sm">
              Remove <b>{removing.fullName}</b> from {branch.name}? They lose access to this branch&apos;s members and data.
            </Text>
            <Group justify="flex-end">
              <Button variant="default" onClick={() => setRemoving(null)}>Cancel</Button>
              <Button color="red" loading={unassign.isPending} onClick={() => unassign.mutate(removing.userId)}>Unassign</Button>
            </Group>
          </Stack>
        )}
      </Modal>
    </>
  );
}

function AssignStaffForm({ branchId, assigned, onDone }: { branchId: number; assigned: Set<number>; onDone: () => void }) {
  const managers = useApiQuery<Page<UserResponse>>('/api/v1/users', { role: 'MANAGER', status: 'ACTIVE', size: 100 });
  const desk = useApiQuery<Page<UserResponse>>('/api/v1/users', { role: 'RECEPTIONIST', status: 'ACTIVE', size: 100 });
  const trainers = useApiQuery<Page<UserResponse>>('/api/v1/users', { role: 'TRAINER', status: 'ACTIVE', size: 100 });
  const [userId, setUserId] = useState<string | null>(null);
  const groups = [
    { group: 'Managers', items: managers.data?.content ?? [] },
    { group: 'Receptionists', items: desk.data?.content ?? [] },
    { group: 'Trainers', items: trainers.data?.content ?? [] },
  ];
  const seen = new Set<number>();
  const data = groups.map((g) => ({
    group: g.group,
    items: g.items.filter((u) => !assigned.has(u.id) && !seen.has(u.id) && seen.add(u.id))
      .map((u) => ({ value: String(u.id), label: `${u.fullName} · ${u.email}` })),
  })).filter((g) => g.items.length > 0);
  const loading = managers.isLoading || desk.isLoading || trainers.isLoading;

  const assign = useApiMutation<void, StaffMember>({
    request: () => ({ path: `/api/v1/branches/${branchId}/staff`, body: { userId: Number(userId) } }),
    invalidate: [`/api/v1/branches/${branchId}/staff`, '/api/v1/users'],
    successMessage: (s) => `${s.fullName} assigned`,
    silentError: true,
    onSuccess: onDone,
  });
  return (
    <Stack>
      <Select
        label="Staff member"
        description="Active managers, receptionists and trainers not yet at this branch"
        placeholder={loading ? 'Loading…' : 'Search staff'}
        searchable
        data={data}
        value={userId}
        onChange={setUserId}
        nothingFoundMessage="No eligible staff"
      />
      {assign.error && <Alert color="red">{errorMessage(assign.error)}</Alert>}
      <Group justify="flex-end">
        <Button variant="default" onClick={onDone}>Cancel</Button>
        <Button disabled={!userId} loading={assign.isPending} onClick={() => assign.mutate()}>Assign</Button>
      </Group>
    </Stack>
  );
}
