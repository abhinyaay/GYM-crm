import { apiBlob } from './client';
import type { Subscription } from './types';

// ------------------------------------------------------------------ price changes

export type ExistingMemberPolicy = 'NEW_PRICE_AT_RENEWAL' | 'PROTECT_EXISTING';
export type PriceChangeStatus = 'SCHEDULED' | 'APPLIED' | 'CANCELLED';
export type PriceChangeSource = 'SCHEDULED' | 'DIRECT_EDIT';
/** Why a subscription term costs what it costs. */
export type PriceBasis = 'LIST' | 'NEGOTIATED' | 'PRICE_PROTECTED' | 'NOTICE_HELD';

export interface PriceChangeRequest {
  newPrice?: number | null;
  newJoiningFee?: number | null;
  effectiveDate: string;
  existingMemberPolicy: ExistingMemberPolicy;
  protectMonths?: number | null;
  protectUntil?: string | null;
  reason: string;
}

export interface PriceChange {
  id: number;
  planId: number;
  planCode: string;
  planName: string;
  oldPrice: number;
  newPrice: number;
  oldJoiningFee: number;
  newJoiningFee: number;
  /** percent, one decimal; null when the old price was 0 */
  changePct: number | null;
  effectiveDate: string;
  existingMemberPolicy: ExistingMemberPolicy;
  protectedUntil: string | null;
  reason: string;
  source: PriceChangeSource;
  status: PriceChangeStatus;
  createdAt: string;
  createdBy: number | null;
  createdByName: string | null;
  appliedAt: string | null;
  protectedCount: number | null;
  notifiedCount: number;
  cancelledAt: string | null;
  cancelledByName: string | null;
  cancelReason: string | null;
}

export interface AffectedMember {
  subscriptionId: number;
  memberId: number;
  memberCode: string;
  memberName: string;
  termEnd: string;
  currentPrice: number;
  newRenewalPrice: number;
  renewalBasis: PriceBasis;
  firstDebitAtNewPrice: string | null;
}

export interface PriceChangePreview {
  planId: number;
  planName: string;
  currentPrice: number;
  newPrice: number;
  currentJoiningFee: number;
  newJoiningFee: number;
  changePct: number | null;
  increase: boolean;
  effectiveDate: string;
  existingMemberPolicy: ExistingMemberPolicy;
  protectedUntil: string | null;
  noticeDays: number;
  noticeSentOn: string;
  openSubscriptions: number;
  autopaySubscriptions: number;
  autopayMembersAffected: number;
  autopayMembers: AffectedMember[];
  notes: string[];
}

export interface PricingSettings {
  priceChangeNoticeDays: number;
  maxOverrideDiscountPct: number;
  sacCode: string;
  legalBusinessName: string;
}

/** Pricing facts the API adds to every subscription (see SubscriptionResponse). */
export interface SubscriptionPricing {
  priceBasis?: PriceBasis;
  /** plan list price when the member pays less (negotiated / protected / notice hold) */
  listPrice?: number | null;
  priceOverrideReason?: string | null;
  keepPriceOnRenewal?: boolean;
  lockedPrice?: number | null;
  priceProtectedUntil?: string | null;
}

export type PricedSubscription = Subscription & SubscriptionPricing;

export const PRICE_BASIS_LABEL: Record<PriceBasis, string> = {
  LIST: 'List price',
  NEGOTIATED: 'Negotiated price',
  PRICE_PROTECTED: 'Price protected',
  NOTICE_HELD: 'Held at old price (notice period)',
};

export const PRICING = {
  settings: '/api/v1/pricing/settings',
  scheduled: '/api/v1/pricing/changes/scheduled',
  history: (planId: number) => `/api/v1/pricing/plans/${planId}/history`,
  schedule: (planId: number) => `/api/v1/pricing/plans/${planId}/changes`,
  preview: (planId: number) => `/api/v1/pricing/plans/${planId}/changes/preview`,
  cancel: (changeId: number) => `/api/v1/pricing/changes/${changeId}/cancel`,
};

// ------------------------------------------------------------------ GST documents

export const invoicePdfPath = (invoiceId: number) => `/api/v1/payments/invoices/${invoiceId}/pdf`;
export const creditNotePdfPath = (refundId: number) => `/api/v1/payments/refunds/${refundId}/credit-note.pdf`;

/** Refund as returned by the API, including its GST credit note number. */
export interface RefundWithCreditNote {
  id: number;
  paymentId: number;
  amount: number;
  reason: string;
  status: 'SUCCEEDED' | 'FAILED';
  processedAt: string;
  creditNoteNumber?: string | null;
}

/**
 * Fetches an authenticated PDF and saves it via a temporary object URL (the API needs the bearer
 * token, so a plain link would not work).
 */
export async function downloadPdf(path: string, fallbackName: string): Promise<void> {
  const { blob, filename } = await apiBlob(path);
  const url = URL.createObjectURL(blob);
  try {
    const a = document.createElement('a');
    a.href = url;
    a.download = filename ?? fallbackName;
    a.rel = 'noopener';
    document.body.appendChild(a);
    a.click();
    a.remove();
  } finally {
    setTimeout(() => URL.revokeObjectURL(url), 10_000);
  }
}

// ------------------------------------------------------------------ GST registration

/** GST fields the API adds to branches. */
export interface BranchGst {
  gstin?: string | null;
  gstStateCode?: string | null;
}

export const GST_STATES: { value: string; label: string }[] = [
  ['01', 'Jammu and Kashmir'], ['02', 'Himachal Pradesh'], ['03', 'Punjab'], ['04', 'Chandigarh'], ['05', 'Uttarakhand'],
  ['06', 'Haryana'], ['07', 'Delhi'], ['08', 'Rajasthan'], ['09', 'Uttar Pradesh'], ['10', 'Bihar'], ['11', 'Sikkim'],
  ['12', 'Arunachal Pradesh'], ['13', 'Nagaland'], ['14', 'Manipur'], ['15', 'Mizoram'], ['16', 'Tripura'],
  ['17', 'Meghalaya'], ['18', 'Assam'], ['19', 'West Bengal'], ['20', 'Jharkhand'], ['21', 'Odisha'],
  ['22', 'Chhattisgarh'], ['23', 'Madhya Pradesh'], ['24', 'Gujarat'], ['26', 'Dadra and Nagar Haveli and Daman and Diu'],
  ['27', 'Maharashtra'], ['29', 'Karnataka'], ['30', 'Goa'], ['31', 'Lakshadweep'], ['32', 'Kerala'], ['33', 'Tamil Nadu'],
  ['34', 'Puducherry'], ['35', 'Andaman and Nicobar Islands'], ['36', 'Telangana'], ['37', 'Andhra Pradesh'],
  ['38', 'Ladakh'], ['97', 'Other Territory'],
].map(([value, name]) => ({ value, label: `${value} · ${name}` }));

const GSTIN_CHARS = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ';

/** Format, state code and mod-36 check character, as validated by the API. */
export function isValidGstin(raw: string): boolean {
  const g = raw.trim().toUpperCase();
  if (!/^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$/.test(g)) return false;
  if (!GST_STATES.some((s) => s.value === g.slice(0, 2))) return false;
  let sum = 0;
  for (let i = 0; i < 14; i++) {
    const product = GSTIN_CHARS.indexOf(g[i]) * (i % 2 === 0 ? 1 : 2);
    sum += Math.floor(product / 36) + (product % 36);
  }
  return GSTIN_CHARS[(36 - (sum % 36)) % 36] === g[14];
}
