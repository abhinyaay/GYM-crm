// Leads & trials (sales pipeline): mirrors of com.gymops.leads.dto.LeadDtos.

export type LeadStatus = 'NEW' | 'CONTACTED' | 'TOUR_SCHEDULED' | 'TRIAL_ACTIVE' | 'CONVERTED' | 'LOST';
export type LeadSource =
  | 'WALK_IN' | 'WEBSITE' | 'INSTAGRAM' | 'FACEBOOK' | 'GOOGLE' | 'REFERRAL' | 'PHONE' | 'CORPORATE' | 'OTHER';
export type LeadInterest = 'MEMBERSHIP' | 'PERSONAL_TRAINING' | 'CLASSES' | 'CORPORATE';
export type LostReason = 'PRICE' | 'LOCATION' | 'TIMING' | 'COMPETITOR' | 'NOT_INTERESTED' | 'UNREACHABLE' | 'OTHER';
export type LeadActivityType =
  | 'NOTE' | 'CALL' | 'EMAIL' | 'WHATSAPP' | 'SMS' | 'VISIT' | 'TOUR' | 'STATUS_CHANGE' | 'FOLLOW_UP';

export const LEAD_STATUSES: LeadStatus[] = ['NEW', 'CONTACTED', 'TOUR_SCHEDULED', 'TRIAL_ACTIVE', 'CONVERTED', 'LOST'];
export const OPEN_LEAD_STATUSES: LeadStatus[] = ['NEW', 'CONTACTED', 'TOUR_SCHEDULED', 'TRIAL_ACTIVE'];
export const LEAD_SOURCES: LeadSource[] = [
  'WALK_IN', 'WEBSITE', 'INSTAGRAM', 'FACEBOOK', 'GOOGLE', 'REFERRAL', 'PHONE', 'CORPORATE', 'OTHER',
];
export const LEAD_INTERESTS: LeadInterest[] = ['MEMBERSHIP', 'PERSONAL_TRAINING', 'CLASSES', 'CORPORATE'];
export const LOST_REASONS: LostReason[] = [
  'PRICE', 'LOCATION', 'TIMING', 'COMPETITOR', 'NOT_INTERESTED', 'UNREACHABLE', 'OTHER',
];
/** Activity types staff can log by hand (STATUS_CHANGE and FOLLOW_UP come from the workflow). */
export const LOGGABLE_ACTIVITIES: LeadActivityType[] = ['CALL', 'WHATSAPP', 'EMAIL', 'SMS', 'VISIT', 'TOUR', 'NOTE'];

/** Mantine colour per pipeline stage. */
export const LEAD_STATUS_COLOR: Record<LeadStatus, string> = {
  NEW: 'blue', CONTACTED: 'indigo', TOUR_SCHEDULED: 'violet', TRIAL_ACTIVE: 'orange', CONVERTED: 'teal', LOST: 'gray',
};

export interface Lead {
  id: number;
  branchId: number;
  branchName: string;
  firstName: string;
  lastName: string | null;
  fullName: string;
  email: string | null;
  phone: string;
  source: LeadSource;
  interest: LeadInterest;
  status: LeadStatus;
  ownerUserId: number | null;
  ownerName: string | null;
  nextFollowUpAt: string | null;
  followUpOverdue: boolean;
  trialSubscriptionId: number | null;
  trialEndsOn: string | null;
  memberId: number | null;
  convertedAt: string | null;
  lostReason: LostReason | null;
  notes: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface LeadActivity {
  id: number;
  type: LeadActivityType;
  body: string;
  dueAt: string | null;
  completedAt: string | null;
  overdue: boolean;
  createdBy: number | null;
  createdByName: string | null;
  createdAt: string;
}

export interface LeadDetail {
  lead: Lead;
  /** newest first */
  activities: LeadActivity[];
  /** statuses reachable through PATCH /status from the current one */
  allowedStatusChanges: LeadStatus[];
  canStartTrial: boolean;
  canConvert: boolean;
  member: { id: number; memberCode: string; fullName: string } | null;
}

export interface TrialResult {
  lead: Lead;
  memberId: number;
  subscriptionId: number;
  subscriptionStatus: string;
  startDate: string;
  endDate: string;
  price: number;
  invoiceId: number | null;
  paymentDue: boolean;
  existingMemberLinked: boolean;
}

export interface FollowUpItem {
  activityId: number;
  leadId: number;
  leadName: string;
  phone: string;
  leadStatus: LeadStatus;
  branchId: number;
  ownerUserId: number | null;
  ownerName: string | null;
  body: string;
  dueAt: string;
  overdue: boolean;
}

export interface LeadOwnerOption {
  userId: number;
  fullName: string;
}

export interface PipelineSummary {
  byStatus: Record<LeadStatus, number>;
  open: number;
  followUpsDueToday: number;
  followUpsOverdue: number;
  myFollowUpsDue: number;
  conversionsThisMonth: number;
  newThisMonth: number;
  trialsEndingSoon: number;
}

export interface EnquiryRequest {
  branchId: number;
  firstName: string;
  lastName: string;
  email: string;
  phone: string;
  interest: LeadInterest;
  message: string;
  consent: boolean;
  /** honeypot: must stay empty */
  website: string;
}

export interface LeadCreateRequest {
  branchId: number;
  firstName: string;
  lastName?: string;
  email?: string;
  phone: string;
  source: LeadSource;
  interest: LeadInterest;
  ownerUserId?: number | null;
  notes?: string;
}

export type LeadUpdateRequest = Omit<LeadCreateRequest, 'branchId' | 'ownerUserId'>;
