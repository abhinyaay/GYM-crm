// TypeScript mirrors of the analytics-service response models (analytics-service/app/schemas/*.py).
// Pydantic models are snake_case in Python and camelCase on the wire. Conventions:
// - money: rupees rounded to 2 dp; `number | null` where the schema says `float | None`
// - rates: fractions 0..1 (4 dp); fields ending in `Pct` are 0..100 (2 dp); NaN/inf become null
// - `period` values are ISO dates (start of the day/week/month bucket); `month` values are YYYY-MM

export type Granularity = 'day' | 'week' | 'month';
export type ChurnBand = 'LOW' | 'MEDIUM' | 'HIGH';
export type EngagementSegmentName = 'HIGHLY_ENGAGED' | 'REGULAR' | 'LOW' | 'INACTIVE';

/** common.QueryMeta */
export interface QueryMeta {
  branchId: number | null;
  /** null = every branch (admin, no filter) */
  scopeBranchIds: number[] | null;
  from: string | null;
  to: string | null;
  granularity: string | null;
  timezone: string;
  generatedAt: string;
}

// ------------------------------------------------------------------ revenue.py
export interface RevenuePeriod {
  period: string;
  gross: number | null;
  refunds: number | null;
  net: number | null;
  successfulPayments: number;
  failedPayments: number;
}

export interface RevenueByPlan {
  planId: number | null;
  planName: string | null;
  gross: number | null;
  refunds: number | null;
  net: number | null;
  successfulPayments: number;
  shareOfNet: number | null;
}

export interface RevenueByBranch {
  branchId: number | null;
  branchName: string | null;
  gross: number | null;
  refunds: number | null;
  net: number | null;
  successfulPayments: number;
  shareOfNet: number | null;
}

export interface PaymentMethodShare {
  method: string;
  payments: number;
  amount: number | null;
  shareOfAmount: number | null;
  shareOfPayments: number | null;
}

export interface RevenueSummary {
  gross: number | null;
  refunds: number | null;
  net: number | null;
  successfulPayments: number;
  failedPayments: number;
  paymentAttempts: number;
  failedPaymentRate: number | null;
  refundCount: number;
  payingMembers: number;
  avgRevenuePerPayingMember: number | null;
}

export interface RevenueResponse {
  meta: QueryMeta;
  summary: RevenueSummary;
  series: RevenuePeriod[];
  byPlan: RevenueByPlan[];
  byBranch: RevenueByBranch[];
  paymentMethods: PaymentMethodShare[];
}

// ------------------------------------------------------------------ attendance.py
export interface AttendancePeriod {
  period: string;
  visits: number;
  uniqueMembers: number;
  avgDurationMinutes: number | null;
}

export interface WeekdayVisits {
  /** MONDAY..SUNDAY */
  weekday: string;
  /** 1 = Monday .. 7 = Sunday */
  isoDay: number;
  visits: number;
  avgVisitsPerDay: number | null;
  share: number | null;
}

export interface AttendanceSummary {
  totalVisits: number;
  uniqueMembers: number;
  avgVisitsPerMember: number | null;
  avgDurationMinutes: number | null;
  visitsWithDuration: number;
  autoCheckedOutVisits: number;
  avgVisitsPerDay: number | null;
}

export interface AttendanceResponse {
  meta: QueryMeta;
  summary: AttendanceSummary;
  series: AttendancePeriod[];
  byWeekday: WeekdayVisits[];
}

export interface HeatmapRow {
  weekday: string;
  isoDay: number;
  /** number of such weekdays in the range */
  occurrences: number;
  totalCheckIns: number;
  /** 24 values, index = local hour 0..23 */
  avgCheckIns: (number | null)[];
}

export interface PeakWindow {
  weekday: string;
  isoDay: number;
  hour: number;
  /** e.g. "18:00-19:00" */
  window: string;
  avgCheckIns: number | null;
}

export interface OccupancyHour {
  hour: number;
  avgOccupancy: number | null;
  utilisationPct: number | null;
}

export interface Occupancy {
  branchId: number | null;
  branchName: string | null;
  capacity: number | null;
  assumedVisitMinutes: number | null;
  hours: OccupancyHour[];
  peakHour: number | null;
  peakAvgOccupancy: number | null;
  peakUtilisationPct: number | null;
}

export interface PeakHoursResponse {
  meta: QueryMeta;
  totalCheckIns: number;
  heatmap: HeatmapRow[];
  peakWindows: PeakWindow[];
  /** only when exactly one branch is in scope */
  occupancy: Occupancy | null;
}

// ------------------------------------------------------------------ members.py
export interface RegistrationPeriod {
  period: string;
  newMembers: number;
}

export interface MemberSummary {
  totalMembers: number;
  newMembers: number;
  activeMembers: number;
  /** e.g. { ACTIVE: 197, FROZEN: 2, GRACE: 4 } */
  activeByStatus: Record<string, number>;
  membersWithoutAccess: number;
}

export interface EngagementSegment {
  segment: EngagementSegmentName | string;
  minVisits: number;
  members: number;
  share: number | null;
}

export interface RetentionCell {
  monthOffset: number;
  retainedMembers: number;
  rate: number | null;
}

export interface Cohort {
  /** registration month YYYY-MM */
  cohort: string;
  members: number;
  retention: RetentionCell[];
}

export interface MembersResponse {
  meta: QueryMeta;
  summary: MemberSummary;
  series: RegistrationPeriod[];
  engagement: EngagementSegment[];
  cohorts: Cohort[];
}

export interface ChurnReason {
  code: string;
  points: number;
  description: string;
}

export interface ChurnRiskMember {
  memberId: number;
  memberCode: string | null;
  name: string | null;
  branchId: number | null;
  branchName: string | null;
  subscriptionId: number;
  subscriptionStatus: string;
  planName: string | null;
  /** ISO date */
  subscriptionEndDate: string;
  /** ISO datetime */
  lastVisit: string | null;
  daysSinceLastVisit: number;
  visitsLast30Days: number;
  /** 0..100 */
  score: number;
  band: ChurnBand;
  reasons: ChurnReason[];
}

export interface ChurnRuleInfo {
  code: string;
  weight: number;
  description: string;
}

export interface ChurnRiskResponse {
  meta: QueryMeta;
  items: ChurnRiskMember[];
  /** 0-based */
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
  bandCounts: Partial<Record<ChurnBand, number>>;
  scoredMembers: number;
  rules: ChurnRuleInfo[];
}

// ------------------------------------------------------------------ memberships.py
export interface MembershipMonth {
  /** YYYY-MM */
  month: string;
  endedSubscriptions: number;
  renewedSubscriptions: number;
  renewalRate: number | null;
  membersAtStart: number;
  churnedMembers: number;
  /** access ended but grace window still open */
  pendingMembers: number;
  churnRate: number;
  newSubscriptions: number;
  subscriptionsFrozen: number;
  frozenDays: number;
}

export interface PlanMix {
  planId: number;
  planName: string | null;
  activeSubscriptions: number;
  share: number | null;
}

export interface ExpiryWindow {
  withinDays: number;
  expiring: number;
  withRenewal: number;
  withoutRenewal: number;
}

export interface FreezeUsage {
  currentlyFrozen: number;
  activeSubscriptionsWithFreezeUsed: number;
  shareWithFreezeUsed: number | null;
  avgFreezeDaysUsed: number | null;
}

export interface CancellationsByPlan {
  planId: number;
  planName: string | null;
  cancellations: number;
  afterActivation: number;
}

export interface MembershipSummary {
  activeSubscriptions: number;
  endedSubscriptions: number;
  renewedSubscriptions: number;
  renewalRate: number | null;
  avgMonthlyChurnRate: number | null;
  cancellations: number;
}

export interface MembershipsResponse {
  meta: QueryMeta;
  summary: MembershipSummary;
  monthly: MembershipMonth[];
  planMix: PlanMix[];
  upcomingExpiries: ExpiryWindow[];
  freezeUsage: FreezeUsage;
  cancellationsByPlan: CancellationsByPlan[];
}

// ------------------------------------------------------------------ trainers.py
export interface TrainerStats {
  trainerId: number;
  name: string | null;
  employeeCode: string | null;
  branchId: number | null;
  branchName: string | null;
  status: string | null;
  activeClients: number;
  maxActiveClients: number | null;
  sessionsScheduled: number;
  sessionsCompleted: number;
  sessionsCancelled: number;
  sessionsNoShow: number;
  completionRate: number | null;
  noShowRate: number | null;
  bookedHours: number | null;
  availableHours: number | null;
  utilisationPct: number | null;
  avgClientVisits: number | null;
}

export interface TrainerSummary {
  trainers: number;
  activeClients: number;
  sessionsCompleted: number;
  sessionsNoShow: number;
  bookedHours: number | null;
  availableHours: number | null;
  utilisationPct: number | null;
}

export interface TrainersResponse {
  meta: QueryMeta;
  summary: TrainerSummary;
  trainers: TrainerStats[];
}

// ------------------------------------------------------------------ branches.py
export interface BranchStats {
  branchId: number;
  code: string | null;
  name: string | null;
  status: string | null;
  capacity: number | null;
  activeMembers: number;
  newMembers: number;
  visits: number;
  visitsPerActiveMember: number | null;
  netRevenue: number | null;
  revenuePerActiveMember: number | null;
  peakAvgOccupancy: number | null;
  peakOccupancyPct: number | null;
  openComplaints: number;
  revenueRank: number;
}

export interface BranchComparisonSummary {
  branches: number;
  activeMembers: number;
  visits: number;
  netRevenue: number | null;
  openComplaints: number;
}

export interface BranchesResponse {
  meta: QueryMeta;
  summary: BranchComparisonSummary;
  branches: BranchStats[];
}

/** Filters shared by every tab. */
export interface AnalyticsFilters {
  branchId: number | null;
  from: string;
  to: string;
  granularity: Granularity;
}
