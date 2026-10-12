import { Alert, Badge, Button, Group, Modal, Select, SimpleGrid, Stack, Textarea, TextInput, type BadgeProps } from '@mantine/core';
import { useEffect, useState } from 'react';
import {
  LEAD_INTERESTS, LEAD_SOURCES, LEAD_STATUS_COLOR, LOST_REASONS, type Lead, type LeadCreateRequest, type LeadInterest,
  type LeadOwnerOption, type LeadSource, type LeadStatus, type LostReason,
} from '@/api/leads';
import { BranchSelect } from '@/components/BranchSelect';
import { label } from '@/lib/format';
import { errorMessage } from '@/lib/notify';
import { useApiMutation, useApiQuery } from '@/lib/useApi';

/** Mirror of LeadStatus.manualTargets() (the API is the source of truth and re-validates). */
export const MANUAL_MOVES: Record<LeadStatus, LeadStatus[]> = {
  NEW: ['CONTACTED', 'TOUR_SCHEDULED', 'LOST'],
  CONTACTED: ['TOUR_SCHEDULED', 'LOST'],
  TOUR_SCHEDULED: ['LOST'],
  TRIAL_ACTIVE: ['LOST'],
  CONVERTED: [],
  LOST: ['NEW'],
};

export const moveLabel = (target: LeadStatus) =>
  target === 'NEW' ? 'Reopen' : target === 'LOST' ? 'Mark lost…' : `Move to ${label(target).toLowerCase()}`;

export const options = (values: readonly string[]) => values.map((v) => ({ value: v, label: label(v) }));

export function LeadStatusBadge({ status, ...props }: { status: LeadStatus } & Omit<BadgeProps, 'children'>) {
  return (
    <Badge variant="light" radius="sm" color={LEAD_STATUS_COLOR[status]} {...props}>
      {label(status)}
    </Badge>
  );
}

export function useLeadOwners(branchId: number | null) {
  return useApiQuery<LeadOwnerOption[]>(branchId ? '/api/v1/leads/owners' : null, { branchId: branchId ?? undefined });
}

/** PATCH /status for the plain moves (contacted, tour scheduled, reopen); LOST goes through {@link LostModal}. */
export function useLeadStatusChange(onDone?: (lead: Lead) => void) {
  return useApiMutation<{ id: number; status: LeadStatus; lostReason?: LostReason; note?: string }, Lead>({
    request: ({ id, ...body }) => ({ path: `/api/v1/leads/${id}/status`, method: 'PATCH', body }),
    invalidate: ['/api/v1/leads'],
    successMessage: (l) => `${l.fullName} is now ${label(l.status).toLowerCase()}`,
    onSuccess: (l) => onDone?.(l),
  });
}

export function LostModal({ lead, onClose }: { lead: Pick<Lead, 'id' | 'fullName'> | null; onClose: () => void }) {
  const [reason, setReason] = useState<LostReason | null>(null);
  const [note, setNote] = useState('');
  useEffect(() => {
    if (lead) {
      setReason(null);
      setNote('');
    }
  }, [lead]);
  const change = useApiMutation<void, Lead>({
    request: () => ({
      path: `/api/v1/leads/${lead?.id}/status`, method: 'PATCH',
      body: { status: 'LOST', lostReason: reason, note: note.trim() || undefined },
    }),
    invalidate: ['/api/v1/leads'],
    successMessage: (l) => `${l.fullName} marked lost`,
    silentError: true,
    onSuccess: onClose,
  });
  return (
    <Modal opened={lead != null} onClose={onClose} title={`Mark ${lead?.fullName ?? ''} as lost`} centered>
      <Stack>
        <Select label="Reason" withAsterisk data={options(LOST_REASONS)} value={reason}
          onChange={(v) => setReason(v as LostReason | null)} />
        <Textarea label="Note (optional)" autosize minRows={2} maxLength={500} value={note}
          onChange={(e) => setNote(e.currentTarget.value)} />
        {change.error && <Alert color="red">{errorMessage(change.error)}</Alert>}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>Cancel</Button>
          <Button color="red" disabled={!reason} loading={change.isPending} onClick={() => change.mutate()}>Mark lost</Button>
        </Group>
      </Stack>
    </Modal>
  );
}

interface LeadFormValues {
  branchId: number | null;
  firstName: string;
  lastName: string;
  email: string;
  phone: string;
  source: LeadSource;
  interest: LeadInterest;
  ownerUserId: number | null;
  notes: string;
}

const PHONE = /^\+?[0-9 -]{7,20}$/;

/** Create (no `lead`) or edit (with `lead`) a lead. */
export function LeadFormModal({ opened, onClose, lead, defaultBranchId, onSaved }: {
  opened: boolean;
  onClose: () => void;
  lead?: Lead;
  defaultBranchId?: number | null;
  onSaved?: (lead: Lead) => void;
}) {
  const empty: LeadFormValues = {
    branchId: defaultBranchId ?? null, firstName: '', lastName: '', email: '', phone: '', source: 'WALK_IN',
    interest: 'MEMBERSHIP', ownerUserId: null, notes: '',
  };
  const [v, setV] = useState<LeadFormValues>(empty);
  const [touched, setTouched] = useState(false);
  useEffect(() => {
    if (!opened) return;
    setTouched(false);
    setV(lead ? {
      branchId: lead.branchId, firstName: lead.firstName, lastName: lead.lastName ?? '', email: lead.email ?? '',
      phone: lead.phone, source: lead.source, interest: lead.interest, ownerUserId: lead.ownerUserId, notes: lead.notes ?? '',
    } : empty);
  }, [opened]); // eslint-disable-line react-hooks/exhaustive-deps
  const owners = useLeadOwners(lead ? null : v.branchId);
  const set = <K extends keyof LeadFormValues>(key: K, value: LeadFormValues[K]) => setV((s) => ({ ...s, [key]: value }));

  const errors = {
    branchId: v.branchId ? null : 'Required',
    firstName: v.firstName.trim() ? null : 'Required',
    phone: PHONE.test(v.phone.trim()) ? null : 'Enter a valid phone number',
    email: !v.email.trim() || /^\S+@\S+\.\S+$/.test(v.email.trim()) ? null : 'Enter a valid email',
  };
  const valid = Object.values(errors).every((e) => e == null);
  const show = (e: string | null) => (touched ? e : null);

  const save = useApiMutation<void, Lead>({
    request: () => {
      const body: Omit<LeadCreateRequest, 'branchId'> = {
        firstName: v.firstName.trim(), lastName: v.lastName.trim() || undefined, email: v.email.trim() || undefined,
        phone: v.phone.trim(), source: v.source, interest: v.interest, notes: v.notes.trim() || undefined,
      };
      return lead
        ? { path: `/api/v1/leads/${lead.id}`, method: 'PUT', body }
        : { path: '/api/v1/leads', body: { ...body, branchId: v.branchId, ownerUserId: v.ownerUserId ?? undefined } };
    },
    invalidate: ['/api/v1/leads'],
    successMessage: (l) => (lead ? 'Lead updated' : `Lead ${l.fullName} created`),
    silentError: true,
    onSuccess: (l) => {
      onSaved?.(l);
      onClose();
    },
  });

  return (
    <Modal opened={opened} onClose={onClose} title={lead ? 'Edit lead' : 'New lead'} size="lg" centered>
      <Stack>
        {!lead && (
          <BranchSelect label="Branch" withAsterisk value={v.branchId} error={show(errors.branchId)}
            onChange={(id) => setV((s) => ({ ...s, branchId: id, ownerUserId: null }))} />
        )}
        <SimpleGrid cols={{ base: 1, sm: 2 }}>
          <TextInput label="First name" withAsterisk value={v.firstName} error={show(errors.firstName)} maxLength={80}
            onChange={(e) => set('firstName', e.currentTarget.value)} />
          <TextInput label="Last name" value={v.lastName} maxLength={80}
            onChange={(e) => set('lastName', e.currentTarget.value)} />
          <TextInput label="Phone" withAsterisk placeholder="+91 98765 43210" value={v.phone} error={show(errors.phone)}
            onChange={(e) => set('phone', e.currentTarget.value)} />
          <TextInput label="Email" value={v.email} error={show(errors.email)} description="Needed to start a trial"
            onChange={(e) => set('email', e.currentTarget.value)} />
          <Select label="Source" data={options(LEAD_SOURCES)} value={v.source} allowDeselect={false}
            onChange={(s) => s && set('source', s as LeadSource)} />
          <Select label="Interest" data={options(LEAD_INTERESTS)} value={v.interest} allowDeselect={false}
            onChange={(s) => s && set('interest', s as LeadInterest)} />
        </SimpleGrid>
        {!lead && (
          <Select label="Owner" placeholder="Me (if I work at this branch)" clearable disabled={!v.branchId}
            data={(owners.data ?? []).map((o) => ({ value: String(o.userId), label: o.fullName }))}
            value={v.ownerUserId == null ? null : String(v.ownerUserId)}
            onChange={(s) => set('ownerUserId', s ? Number(s) : null)} />
        )}
        <Textarea label="Notes" autosize minRows={2} maxLength={1000} value={v.notes}
          onChange={(e) => set('notes', e.currentTarget.value)} />
        {save.error && <Alert color="red" style={{ whiteSpace: 'pre-line' }}>{errorMessage(save.error)}</Alert>}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>Cancel</Button>
          <Button loading={save.isPending} onClick={() => { setTouched(true); if (valid) save.mutate(); }}>
            {lead ? 'Save' : 'Create lead'}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
