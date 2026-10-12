import {
  ActionIcon, Alert, Anchor, Badge, Button, Checkbox, Group, Menu, Modal, MultiSelect, PasswordInput, Select, SimpleGrid, Stack,
  Table, Text, TextInput,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { useDebouncedValue } from '@mantine/hooks';
import {
  IconDots, IconKey, IconLock, IconLockOpen, IconPlus, IconRefresh, IconSearch, IconShieldCog, IconTrash, IconUserOff,
} from '@tabler/icons-react';
import { useState } from 'react';
import type { Page, RoleName, UserResponse, UserStatus } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { useBranchNames } from '@/components/BranchSelect';
import { PageHeader } from '@/components/PageHeader';
import { Pager } from '@/components/Pager';
import { QueryState } from '@/components/QueryState';
import { StatusBadge } from '@/components/StatusBadge';
import { fromNow, label } from '@/lib/format';
import { errorMessage } from '@/lib/notify';
import { useApiMutation, useApiQuery } from '@/lib/useApi';

const ALL_ROLES: RoleName[] = ['SUPER_ADMIN', 'GYM_ADMIN', 'MANAGER', 'TRAINER', 'RECEPTIONIST', 'MEMBER'];
const STAFF_ROLES: RoleName[] = ['SUPER_ADMIN', 'GYM_ADMIN', 'MANAGER', 'TRAINER', 'RECEPTIONIST'];
const GYM_ADMIN_GRANTABLE: RoleName[] = ['MANAGER', 'TRAINER', 'RECEPTIONIST'];
const BRANCH_SCOPED: RoleName[] = ['MANAGER', 'TRAINER', 'RECEPTIONIST'];
const ADMIN_ROLES: RoleName[] = ['SUPER_ADMIN', 'GYM_ADMIN'];
const STATUSES: UserStatus[] = ['ACTIVE', 'LOCKED', 'DISABLED'];
const ROLE_COLOR: Record<RoleName, string> = {
  SUPER_ADMIN: 'red', GYM_ADMIN: 'grape', MANAGER: 'indigo', TRAINER: 'cyan', RECEPTIONIST: 'blue', MEMBER: 'gray',
};
const STRONG = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{10,72}$/;

function generatePassword(): string {
  const sets = ['ABCDEFGHJKLMNPQRSTUVWXYZ', 'abcdefghijkmnopqrstuvwxyz', '23456789', '!@#$%&*?'];
  const all = sets.join('');
  const rnd = (n: number) => {
    const a = new Uint32Array(1);
    crypto.getRandomValues(a);
    return a[0] % n;
  };
  const chars = [...sets.map((s) => s[rnd(s.length)]), ...Array.from({ length: 10 }, () => all[rnd(all.length)])];
  for (let i = chars.length - 1; i > 0; i--) {
    const j = rnd(i + 1);
    [chars[i], chars[j]] = [chars[j], chars[i]];
  }
  return chars.join('');
}

type Confirm =
  | { kind: 'status'; user: UserResponse; status: UserStatus }
  | { kind: 'delete'; user: UserResponse };

export default function UsersPage() {
  const { user: me } = useAuth();
  const isSuper = !!me?.roles.includes('SUPER_ADMIN');
  const branchNames = useBranchNames();
  const [q, setQ] = useState('');
  const [debounced] = useDebouncedValue(q.trim(), 300);
  const [role, setRole] = useState<string | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [sort, setSort] = useState('fullName');
  const [page, setPage] = useState(0);
  const [creating, setCreating] = useState(false);
  const [rolesFor, setRolesFor] = useState<UserResponse | null>(null);
  const [confirm, setConfirm] = useState<Confirm | null>(null);

  const query = useApiQuery<Page<UserResponse>>('/api/v1/users', {
    q: debounced || undefined, role: role ?? undefined, status: status ?? undefined, page, size: 20, sort,
  });
  const rows = query.data?.content ?? [];

  const changeStatus = useApiMutation<{ id: number; status: UserStatus }, UserResponse>({
    request: ({ id, status: s }) => ({ path: `/api/v1/users/${id}/status`, method: 'PATCH', body: { status: s } }),
    invalidate: ['/api/v1/users'],
    successMessage: (u) => `${u.fullName} is now ${label(u.status).toLowerCase()}`,
    silentError: true,
    onSuccess: () => setConfirm(null),
  });
  const remove = useApiMutation<number>({
    request: (id) => ({ path: `/api/v1/users/${id}`, method: 'DELETE' }),
    invalidate: ['/api/v1/users', '/api/v1/branches'],
    successMessage: 'User deleted',
    silentError: true,
    onSuccess: () => setConfirm(null),
  });
  const confirmError = changeStatus.error ?? remove.error;

  /** GYM_ADMIN may not manage administrator accounts; nobody manages their own account here. */
  const mayManage = (u: UserResponse) => u.id !== me?.id && (isSuper || !u.roles.some((r) => ADMIN_ROLES.includes(r)));
  /** Role edits: SUPER_ADMIN any; GYM_ADMIN only staff without MEMBER (MEMBER isn't grantable by them). */
  const mayEditRoles = (u: UserResponse) => mayManage(u) && (isSuper || !u.roles.includes('MEMBER'));

  const openConfirm = (c: Confirm) => { changeStatus.reset(); remove.reset(); setConfirm(c); };

  return (
    <>
      <PageHeader
        title="Users"
        subtitle="Staff accounts, roles and access"
        actions={<Button leftSection={<IconPlus size={16} />} onClick={() => setCreating(true)}>New staff account</Button>}
      />

      <Group mb="md" align="flex-end" wrap="wrap">
        <TextInput label="Search" placeholder="Name or email" leftSection={<IconSearch size={14} />} value={q}
          onChange={(e) => { setQ(e.currentTarget.value); setPage(0); }} w={240} />
        <Select label="Role" placeholder="Any role" clearable data={ALL_ROLES.map((r) => ({ value: r, label: label(r) }))}
          value={role} onChange={(v) => { setRole(v); setPage(0); }} w={170} />
        <Select label="Status" placeholder="Any status" clearable data={STATUSES.map((s) => ({ value: s, label: label(s) }))}
          value={status} onChange={(v) => { setStatus(v); setPage(0); }} w={150} />
        <Select label="Sort" allowDeselect={false} value={sort} onChange={(v) => { if (v) { setSort(v); setPage(0); } }} w={160}
          data={[
            { value: 'fullName', label: 'Name' },
            { value: 'email', label: 'Email' },
            { value: 'createdAt,desc', label: 'Newest first' },
          ]} />
      </Group>

      <QueryState isLoading={query.isLoading} error={query.error} empty={rows.length === 0} emptyText="No users match these filters.">
        <Table.ScrollContainer minWidth={900}>
          <Table striped highlightOnHover verticalSpacing="sm">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>User</Table.Th>
                <Table.Th>Phone</Table.Th>
                <Table.Th>Roles</Table.Th>
                <Table.Th>Branches</Table.Th>
                <Table.Th>Status</Table.Th>
                <Table.Th>Last sign-in</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((u) => (
                <Table.Tr key={u.id}>
                  <Table.Td>
                    <Text size="sm" fw={600} component="div">{u.fullName}{u.id === me?.id && <Badge size="xs" ml={6} variant="outline">You</Badge>}</Text>
                    <Text size="xs" c="dimmed">{u.email}</Text>
                  </Table.Td>
                  <Table.Td><Text size="sm">{u.phone ?? '—'}</Text></Table.Td>
                  <Table.Td>
                    <Group gap={4}>
                      {u.roles.map((r) => <Badge key={r} size="sm" variant="light" color={ROLE_COLOR[r]}>{label(r)}</Badge>)}
                    </Group>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm" lineClamp={2}>
                      {u.roles.some((r) => ADMIN_ROLES.includes(r)) ? 'All (org-wide)'
                        : u.branchIds.length === 0 ? '—' : u.branchIds.map((id) => branchNames[id] ?? `#${id}`).join(', ')}
                    </Text>
                  </Table.Td>
                  <Table.Td><StatusBadge value={u.status} /></Table.Td>
                  <Table.Td><Text size="sm">{u.lastLoginAt ? fromNow(u.lastLoginAt) : 'Never'}</Text></Table.Td>
                  <Table.Td>
                    {mayManage(u) && (
                      <Group justify="flex-end">
                        <Menu position="bottom-end" withinPortal>
                          <Menu.Target>
                            <ActionIcon variant="subtle" aria-label={`Actions for ${u.fullName}`}><IconDots size={16} /></ActionIcon>
                          </Menu.Target>
                          <Menu.Dropdown>
                            <Menu.Label>Status</Menu.Label>
                            {u.status !== 'ACTIVE' && (
                              <Menu.Item leftSection={<IconLockOpen size={14} />}
                                onClick={() => openConfirm({ kind: 'status', user: u, status: 'ACTIVE' })}>Activate</Menu.Item>
                            )}
                            {u.status !== 'LOCKED' && (
                              <Menu.Item leftSection={<IconLock size={14} />}
                                onClick={() => openConfirm({ kind: 'status', user: u, status: 'LOCKED' })}>Lock</Menu.Item>
                            )}
                            {u.status !== 'DISABLED' && (
                              <Menu.Item leftSection={<IconUserOff size={14} />}
                                onClick={() => openConfirm({ kind: 'status', user: u, status: 'DISABLED' })}>Disable</Menu.Item>
                            )}
                            {mayEditRoles(u) && (
                              <>
                                <Menu.Divider />
                                <Menu.Item leftSection={<IconShieldCog size={14} />} onClick={() => setRolesFor(u)}>Change roles</Menu.Item>
                              </>
                            )}
                            {isSuper && (
                              <>
                                <Menu.Divider />
                                <Menu.Item color="red" leftSection={<IconTrash size={14} />}
                                  onClick={() => openConfirm({ kind: 'delete', user: u })}>Delete user</Menu.Item>
                              </>
                            )}
                          </Menu.Dropdown>
                        </Menu>
                      </Group>
                    )}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
        <Pager page={query.data} onChange={setPage} />
      </QueryState>

      <Modal opened={creating} onClose={() => setCreating(false)} title="New staff account" size="lg">
        {creating && <CreateUserForm isSuper={isSuper} onDone={() => setCreating(false)} />}
      </Modal>

      <Modal opened={rolesFor !== null} onClose={() => setRolesFor(null)} title={`Roles for ${rolesFor?.fullName ?? ''}`} centered>
        {rolesFor && <RolesForm key={rolesFor.id} target={rolesFor} isSuper={isSuper} onDone={() => setRolesFor(null)} />}
      </Modal>

      <Modal
        opened={confirm !== null}
        onClose={() => setConfirm(null)}
        title={confirm?.kind === 'delete' ? 'Delete user' : 'Change account status'}
        centered
      >
        {confirm && (
          <Stack>
            {confirm.kind === 'delete' ? (
              <Text size="sm">
                Delete <b>{confirm.user.fullName}</b> ({confirm.user.email})? The account is deactivated immediately and all of its
                sessions are signed out. This cannot be undone from the app.
              </Text>
            ) : (
              <Text size="sm">
                Set <b>{confirm.user.fullName}</b> to <b>{label(confirm.status).toLowerCase()}</b>?
                {confirm.status !== 'ACTIVE' && ' All of their active sessions will be signed out immediately.'}
              </Text>
            )}
            {confirmError && <Alert color="red">{errorMessage(confirmError)}</Alert>}
            <Group justify="flex-end">
              <Button variant="default" onClick={() => setConfirm(null)}>Cancel</Button>
              {confirm.kind === 'delete' ? (
                <Button color="red" loading={remove.isPending} onClick={() => remove.mutate(confirm.user.id)}>Delete</Button>
              ) : (
                <Button color={confirm.status === 'ACTIVE' ? 'teal' : 'red'} loading={changeStatus.isPending}
                  onClick={() => changeStatus.mutate({ id: confirm.user.id, status: confirm.status })}>
                  {label(confirm.status)}
                </Button>
              )}
            </Group>
          </Stack>
        )}
      </Modal>
    </>
  );
}

// ------------------------------------------------------------------ create

interface CreateValues {
  email: string;
  fullName: string;
  phone: string;
  initialPassword: string;
  roles: string[];
  branchIds: string[];
}

function CreateUserForm({ isSuper, onDone }: { isSuper: boolean; onDone: () => void }) {
  const branchNames = useBranchNames();
  const grantable = isSuper ? STAFF_ROLES : GYM_ADMIN_GRANTABLE;
  const form = useForm<CreateValues>({
    initialValues: { email: '', fullName: '', phone: '', initialPassword: generatePassword(), roles: [], branchIds: [] },
    validate: {
      email: (v) => (/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v.trim()) ? null : 'Valid email required'),
      fullName: (v) => (!v.trim() ? 'Required' : v.length > 120 ? 'Max 120 characters' : null),
      phone: (v) => (!v || /^\+?[0-9 -]{7,20}$/.test(v) ? null : 'Invalid phone number'),
      initialPassword: (v) => (STRONG.test(v) ? null : '10-72 characters with upper case, lower case and a digit'),
      roles: (v) => (v.length ? null : 'Pick at least one role'),
      branchIds: (v, vals) =>
        vals.roles.some((r) => BRANCH_SCOPED.includes(r as RoleName)) && v.length === 0
          ? 'Branch staff should be assigned to at least one branch'
          : null,
    },
  });
  const needsBranches = form.values.roles.some((r) => BRANCH_SCOPED.includes(r as RoleName));
  const create = useApiMutation<CreateValues, UserResponse>({
    request: (v) => ({
      path: '/api/v1/users',
      body: {
        email: v.email.trim(),
        initialPassword: v.initialPassword,
        fullName: v.fullName.trim(),
        phone: v.phone.trim() || null,
        roles: v.roles,
        branchIds: needsBranches ? v.branchIds.map(Number) : [],
      },
    }),
    invalidate: ['/api/v1/users', '/api/v1/branches'],
    successMessage: (u) => `Account created for ${u.fullName}. Share the initial password securely.`,
    silentError: true,
    onSuccess: onDone,
  });
  return (
    <form onSubmit={form.onSubmit((v) => create.mutate(v))}>
      <Stack>
        <SimpleGrid cols={{ base: 1, sm: 2 }}>
          <TextInput label="Full name" withAsterisk {...form.getInputProps('fullName')} />
          <TextInput label="Email" type="email" withAsterisk autoComplete="off" {...form.getInputProps('email')} />
          <TextInput label="Phone" placeholder="+91 98765 43210" {...form.getInputProps('phone')} />
          <PasswordInput
            label="Initial password"
            description={
              <>
                Share securely; the user should change it.{' '}
                <Anchor component="button" type="button" size="xs" onClick={() => form.setFieldValue('initialPassword', generatePassword())}>
                  <IconRefresh size={10} /> Generate
                </Anchor>
              </>
            }
            withAsterisk
            autoComplete="new-password"
            {...form.getInputProps('initialPassword')}
          />
        </SimpleGrid>
        <MultiSelect
          label="Roles"
          withAsterisk
          data={grantable.map((r) => ({ value: r, label: label(r) }))}
          description={isSuper ? undefined : 'Gym admins can grant Manager, Trainer and Receptionist'}
          {...form.getInputProps('roles')}
        />
        {needsBranches && (
          <MultiSelect
            label="Branches"
            searchable
            data={Object.entries(branchNames).map(([id, name]) => ({ value: id, label: name }))}
            {...form.getInputProps('branchIds')}
          />
        )}
        <Alert color="blue" variant="light" icon={<IconKey size={16} />}>
          Member accounts are created through member registration, not here.
        </Alert>
        {create.error && <Alert color="red" style={{ whiteSpace: 'pre-line' }}>{errorMessage(create.error)}</Alert>}
        <Group justify="flex-end">
          <Button variant="default" onClick={onDone}>Cancel</Button>
          <Button type="submit" loading={create.isPending}>Create account</Button>
        </Group>
      </Stack>
    </form>
  );
}

// ------------------------------------------------------------------ roles

function RolesForm({ target, isSuper, onDone }: { target: UserResponse; isSuper: boolean; onDone: () => void }) {
  const grantable = isSuper ? STAFF_ROLES : GYM_ADMIN_GRANTABLE;
  const isMember = target.roles.includes('MEMBER');
  const [roles, setRoles] = useState<RoleName[]>(target.roles.filter((r) => r !== 'MEMBER'));
  const save = useApiMutation<void, UserResponse>({
    request: () => ({
      path: `/api/v1/users/${target.id}/roles`,
      method: 'PUT',
      body: { roles: isMember ? [...roles, 'MEMBER'] : roles },
    }),
    invalidate: ['/api/v1/users', '/api/v1/branches'],
    successMessage: (u) => `Roles updated for ${u.fullName}`,
    silentError: true,
    onSuccess: onDone,
  });
  const empty = roles.length === 0 && !isMember;
  return (
    <Stack>
      <Text size="sm" c="dimmed">The user is signed out everywhere so new sessions carry the updated roles.</Text>
      <Stack gap="xs">
        {grantable.map((r) => (
          <Checkbox
            key={r}
            label={label(r)}
            checked={roles.includes(r)}
            onChange={(e) => {
              const checked = e.currentTarget.checked;
              setRoles((list) => (checked ? [...list, r] : list.filter((x) => x !== r)));
            }}
          />
        ))}
        {isMember && <Checkbox label="Member (fixed: member accounts keep this role)" checked disabled />}
      </Stack>
      {empty && <Text size="xs" c="red">Pick at least one role</Text>}
      {save.error && <Alert color="red">{errorMessage(save.error)}</Alert>}
      <Group justify="flex-end">
        <Button variant="default" onClick={onDone}>Cancel</Button>
        <Button disabled={empty} loading={save.isPending} onClick={() => save.mutate()}>Save roles</Button>
      </Group>
    </Stack>
  );
}
