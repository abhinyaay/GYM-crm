// TypeScript mirror of analytics-service/app/schemas/leads.py (GET /analytics/leads).
// Rates are fractions 0..1 (4 dp) and null when the denominator is 0.
import type { QueryMeta } from './types';

export interface LeadsSummary {
  totalLeads: number;
  /** not yet CONVERTED or LOST */
  openLeads: number;
  conversions: number;
  conversionRate: number | null;
  lostLeads: number;
  /** leads that started a trial subscription */
  trialLeads: number;
  trialConversions: number;
  trialToPaidRate: number | null;
  medianDaysToConvert: number | null;
  /** leads without an owner (not in ownerLeaderboard) */
  unassignedLeads: number;
}

export interface LeadFunnelStage {
  status: string;
  count: number;
  share: number | null;
}

export interface LeadFunnel {
  totalLeads: number;
  /** always all six statuses in pipeline order */
  stages: LeadFunnelStage[];
}

export interface LeadSourceConversion {
  source: string;
  leads: number;
  conversions: number;
  conversionRate: number | null;
}

export interface WeeklyLeads {
  /** Monday of the week (ISO date) */
  weekStart: string;
  leads: number;
  conversions: number;
}

export interface LostReasonCount {
  reason: string;
  count: number;
  share: number | null;
}

export interface LeadOwnerStats {
  ownerUserId: number;
  ownerName: string | null;
  leadsHandled: number;
  conversions: number;
  conversionRate: number | null;
}

export interface LeadFollowUpSla {
  slaHours: number;
  totalLeads: number;
  /** leads with at least one CALL / EMAIL / WHATSAPP / SMS / VISIT */
  contactedLeads: number;
  contactedWithin24h: number;
  rate: number | null;
  medianHoursToFirstContact: number | null;
}

export interface LeadsAnalyticsResponse {
  meta: QueryMeta;
  summary: LeadsSummary;
  funnel: LeadFunnel;
  bySource: LeadSourceConversion[];
  weeklyTrend: WeeklyLeads[];
  lostReasons: LostReasonCount[];
  ownerLeaderboard: LeadOwnerStats[];
  followUpSla: LeadFollowUpSla;
}
