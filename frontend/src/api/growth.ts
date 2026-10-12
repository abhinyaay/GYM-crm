// Growth: referral programme and retention campaigns. Mirrors com.gymops.growth.referral.ReferralDtos
// and com.gymops.growth.campaign.CampaignDtos.

export type ReferralStatus = 'PENDING' | 'CONVERTED' | 'REVERSED' | 'REJECTED';
export type RewardStatus = 'HELD' | 'AVAILABLE' | 'REVERSED';
export type ReferralChannel = 'LINK' | 'DESK' | 'SELF_SIGNUP';
export type RejectionReason = 'SELF_REFERRAL' | 'NOT_NEW';
export type RewardSkipReason = 'MONTHLY_CAP' | 'REFERRER_INACTIVE';
export type Beneficiary = 'REFERRER' | 'REFEREE';
export type RewardType = 'STORE_CREDIT' | 'DISCOUNT_CODE';
export type DiscountType = 'PERCENTAGE' | 'FIXED_AMOUNT';

export const REFERRAL_STATUS_COLOR: Record<ReferralStatus, string> = {
  PENDING: 'yellow', CONVERTED: 'teal', REVERSED: 'grape', REJECTED: 'red',
};
export const REWARD_STATUS_COLOR: Record<RewardStatus, string> = { HELD: 'orange', AVAILABLE: 'teal', REVERSED: 'grape' };

export const REFERRAL_STATUS_LABEL: Record<ReferralStatus, string> = {
  PENDING: 'Signed up', CONVERTED: 'Joined', REVERSED: 'Reversed', REJECTED: 'Not eligible',
};
export const REJECTION_LABEL: Record<RejectionReason, string> = {
  SELF_REFERRAL: 'Self-referral', NOT_NEW: 'Already a member',
};
export const SKIP_LABEL: Record<RewardSkipReason, string> = {
  MONTHLY_CAP: 'Monthly cap reached', REFERRER_INACTIVE: 'Referrer inactive',
};

export interface PublicReferral {
  code: string;
  referrerFirstName: string;
  headline: string;
  offer: string;
}

export interface RewardView {
  id: number;
  referralId: number;
  beneficiary: Beneficiary;
  rewardType: RewardType;
  amount: number;
  discountType: DiscountType | null;
  status: RewardStatus;
  description: string;
  availableAt: string;
  releasedAt: string | null;
  reversedAt: string | null;
  discountCode: string | null;
  codeValidUntil: string | null;
  codeUsed: boolean | null;
}

export interface MyReferral {
  id: number;
  refereeName: string;
  channel: ReferralChannel;
  status: ReferralStatus;
  rejectionReason: RejectionReason | null;
  referrerRewardSkipped: RewardSkipReason | null;
  createdAt: string;
  convertedAt: string | null;
  holdUntil: string | null;
  reward: RewardView | null;
}

export interface MyReferralDashboard {
  code: string;
  sharePath: string;
  headline: string;
  offerText: string;
  referrerRewardText: string;
  holdDays: number;
  monthlyCap: number;
  invited: number;
  joined: number;
  creditEarned: number;
  creditPending: number;
  referrals: MyReferral[];
  welcomeRewards: RewardView[];
  referredBy: string | null;
  /** New member who may still enter a friend's code. */
  canClaimCode: boolean;
}

export interface Referral {
  id: number;
  referrerMemberId: number;
  referrerName: string;
  referrerMemberCode: string;
  code: string;
  refereeMemberId: number | null;
  refereeMemberCode: string | null;
  refereeLeadId: number | null;
  refereeName: string;
  branchId: number;
  branchName: string;
  channel: ReferralChannel;
  status: ReferralStatus;
  rejectionReason: RejectionReason | null;
  referrerRewardSkipped: RewardSkipReason | null;
  createdAt: string;
  convertedAt: string | null;
  holdUntil: string | null;
  reversedAt: string | null;
  reversalReason: string | null;
  referrerRewardStatus: RewardStatus | null;
  referrerRewardAmount: number | null;
  refereeRewardStatus: RewardStatus | null;
}

export interface ReferralSettings {
  enabled: boolean;
  referrerCredit: number;
  refereeRewardType: RewardType;
  refereeDiscountType: DiscountType;
  refereeRewardValue: number;
  refereeCodeValidDays: number;
  holdDays: number;
  monthlyCap: number;
  attachGraceDays: number;
  referrerRewardText: string;
  refereeRewardText: string;
}

export interface RewardTotals {
  count: number;
  creditAmount: number;
  codes: number;
}

export interface TopReferrer {
  memberId: number;
  name: string;
  memberCode: string;
  code: string | null;
  referrals: number;
  conversions: number;
  creditEarned: number;
}

export interface ReferralStats {
  total: number;
  byStatus: Record<ReferralStatus, number>;
  conversionRate: number;
  issued: RewardTotals;
  pending: RewardTotals;
  reversed: RewardTotals;
  cappedReferrals: number;
  topReferrers: TopReferrer[];
  monthly: { month: string; referrals: number; conversions: number }[];
}

export interface AttachReferralRequest {
  code: string;
  memberId?: number | null;
  leadId?: number | null;
}

// ------------------------------------------------------------------ campaigns

export type CampaignSegment = 'CHURN_RISK' | 'EXPIRING_NO_AUTOPAY' | 'LAPSED' | 'NO_RECENT_VISIT' | 'TRIAL_NOT_CONVERTED';
export type ChurnBand = 'HIGH' | 'MEDIUM';
export type CampaignStatus = 'DRAFT' | 'ACTIVE' | 'PAUSED' | 'ARCHIVED';
export type CampaignSchedule = 'NONE' | 'DAILY' | 'WEEKLY';
export type RunTrigger = 'MANUAL' | 'SCHEDULED';
export type RunStatus = 'RUNNING' | 'COMPLETED' | 'FAILED';

export const CAMPAIGN_STATUS_COLOR: Record<CampaignStatus, string> = {
  DRAFT: 'gray', ACTIVE: 'teal', PAUSED: 'yellow', ARCHIVED: 'gray',
};

export interface SegmentInfo {
  value: CampaignSegment;
  label: string;
  description: string;
  /** Which numeric parameters the segment takes. */
  days?: { label: string; default: number };
  daysMax?: { label: string; default: number };
  churnBand?: boolean;
}

export const SEGMENTS: SegmentInfo[] = [
  {
    value: 'EXPIRING_NO_AUTOPAY', label: 'Expiring soon, no autopay',
    description: 'Active memberships ending within N days with autopay off and no renewal bought yet.',
    days: { label: 'Ending within (days)', default: 7 },
  },
  {
    value: 'LAPSED', label: 'Lapsed members',
    description: 'Membership expired between N and M days ago and nothing current or upcoming.',
    days: { label: 'Expired at least (days ago)', default: 7 }, daysMax: { label: 'and at most (days ago)', default: 90 },
  },
  {
    value: 'NO_RECENT_VISIT', label: 'No recent visit',
    description: 'Active members who have not checked in for N days.',
    days: { label: 'No visit for (days)', default: 14 },
  },
  {
    value: 'TRIAL_NOT_CONVERTED', label: 'Trial ended, not joined',
    description: 'Free / paid trials that ended within the last N days without a membership purchase.',
    days: { label: 'Trial ended within (days)', default: 14 },
  },
  {
    value: 'CHURN_RISK', label: 'Churn risk (analytics)',
    description: 'Members flagged by the analytics service’s rule-based churn-risk score.',
    churnBand: true,
  },
];

export const PLACEHOLDERS = ['{firstName}', '{planName}', '{endDate}', '{offerCode}'] as const;

export interface CampaignRequest {
  name: string;
  description?: string | null;
  branchId: number | null;
  segment: CampaignSegment;
  churnBand?: ChurnBand | null;
  segmentDays?: number | null;
  segmentDaysMax?: number | null;
  titleTemplate: string;
  messageTemplate: string;
  offerEnabled: boolean;
  offerDiscountType?: DiscountType | null;
  offerValue?: number | null;
  offerValidDays?: number | null;
  schedule: CampaignSchedule;
  scheduleDayOfWeek?: number | null;
  cooldownDays?: number | null;
  attributionDays?: number | null;
}

export interface CampaignStats {
  sent: number;
  uniqueMembers: number;
  converted: number;
  revenue: number;
  offersIssued: number;
  offersRedeemed: number;
  conversionRate: number;
  runs: number;
}

export interface Campaign {
  id: number;
  name: string;
  description: string | null;
  branchId: number | null;
  branchName: string | null;
  segment: CampaignSegment;
  churnBand: ChurnBand | null;
  segmentDays: number | null;
  segmentDaysMax: number | null;
  segmentDescription: string;
  titleTemplate: string;
  messageTemplate: string;
  offerEnabled: boolean;
  offerDiscountType: DiscountType | null;
  offerValue: number | null;
  offerValidDays: number | null;
  schedule: CampaignSchedule;
  scheduleDayOfWeek: number | null;
  status: CampaignStatus;
  cooldownDays: number;
  attributionDays: number;
  nextRunAt: string | null;
  lastRunAt: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
  stats: CampaignStats;
}

export interface PreviewRecipient {
  memberId: number;
  memberCode: string;
  name: string;
  branchName: string;
  planName: string | null;
  endDate: string | null;
  inCooldown: boolean;
  title: string;
  body: string;
}

export interface CampaignPreview {
  segmentDescription: string;
  audienceSize: number;
  truncated: boolean;
  inCooldown: number;
  eligible: number;
  sample: PreviewRecipient[];
  warnings: string[];
}

export interface CampaignRun {
  id: number;
  campaignId: number;
  triggerType: RunTrigger;
  status: RunStatus;
  audienceSize: number;
  sent: number;
  skipped: number;
  failed: number;
  error: string | null;
  startedAt: string;
  finishedAt: string | null;
}

export interface CampaignRecipient {
  id: number;
  runId: number;
  memberId: number;
  memberCode: string;
  memberName: string;
  branchName: string;
  sentAt: string;
  offerCode: string | null;
  attributionEndsAt: string;
  convertedAt: string | null;
  conversionAmount: number | null;
  offerRedeemed: boolean;
}

/**
 * Client-side mirror of the backend renderer for the live preview: single pass, `{{` / `}}` are
 * literal braces, unknown placeholders stay as typed.
 */
export function renderTemplate(template: string, values: Record<string, string>): string {
  return template.replace(/\{\{|\}\}|\{([A-Za-z][A-Za-z0-9]*)\}/g, (token, name: string | undefined) => {
    if (token === '{{') return '{';
    if (token === '}}') return '}';
    if (name && name in values) return values[name];
    return token;
  });
}

/** Placeholders used in a template that the backend does not know. */
export function unknownPlaceholders(template: string): string[] {
  const known = new Set(['firstName', 'planName', 'endDate', 'offerCode']);
  const out: string[] = [];
  for (const m of template.replace(/\{\{|\}\}/g, '').matchAll(/\{([A-Za-z][A-Za-z0-9]*)\}/g)) {
    if (!known.has(m[1]) && !out.includes(m[1])) out.push(m[1]);
  }
  return out;
}
