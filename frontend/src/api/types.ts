// TypeScript mirrors of the core-service DTOs (Java records). Keep in sync with the backend.

export type RoleName = 'SUPER_ADMIN' | 'GYM_ADMIN' | 'MANAGER' | 'TRAINER' | 'RECEPTIONIST' | 'MEMBER';

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  last: boolean;
}

export interface ApiErrorBody {
  timestamp: string;
  status: number;
  error: string;
  code: string;
  message: string;
  path: string;
  correlationId?: string;
  violations?: { field: string; message: string; rejectedValue?: unknown }[];
}

// ------------------------------------------------------------------ auth & users
export interface UserInfo {
  id: number;
  email: string;
  fullName: string;
  roles: RoleName[];
  branchIds: number[];
  memberId: number | null;
  trainerId: number | null;
}

export interface TokenResponse {
  tokenType: string;
  accessToken: string;
  accessTokenExpiresAt: string;
  refreshToken: string;
  refreshTokenExpiresAt: string;
  user: UserInfo;
}

export type UserStatus = 'ACTIVE' | 'LOCKED' | 'DISABLED';

export interface UserResponse {
  id: number;
  email: string;
  fullName: string;
  phone: string | null;
  status: UserStatus;
  roles: RoleName[];
  branchIds: number[];
  lastLoginAt: string | null;
  createdAt: string;
}

// ------------------------------------------------------------------ branches
export type BranchStatus = 'ACTIVE' | 'INACTIVE' | 'UNDER_MAINTENANCE';

export interface BranchHours {
  dayOfWeek: number;
  openTime: string | null;
  closeTime: string | null;
  closed: boolean;
}

export interface Facility {
  id: number;
  name: string;
  description: string | null;
  available: boolean;
}

export interface Branch {
  id: number;
  code: string;
  name: string;
  phone: string | null;
  email: string | null;
  addressLine1: string;
  addressLine2: string | null;
  city: string;
  state: string | null;
  postalCode: string | null;
  country: string;
  timezone: string;
  capacity: number;
  status: BranchStatus;
  operatingHours: BranchHours[];
  facilities: Facility[];
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface BranchSummary {
  id: number;
  code: string;
  name: string;
  city: string;
  addressLine1: string;
  phone: string | null;
}

export interface StaffMember {
  userId: number;
  fullName: string;
  email: string;
  roles: RoleName[];
  assignedAt: string;
}

// ------------------------------------------------------------------ plans
export type BillingPeriod = 'MONTHLY' | 'QUARTERLY' | 'HALF_YEARLY' | 'ANNUAL' | 'CUSTOM';
export type CancellationPolicy = 'NON_REFUNDABLE' | 'PRORATED' | 'COOLING_OFF_FULL_REFUND';
export type PlanStatus = 'DRAFT' | 'ACTIVE' | 'ARCHIVED';

export interface Plan {
  id: number;
  code: string;
  name: string;
  description: string | null;
  billingPeriod: BillingPeriod;
  durationMonths: number | null;
  durationDays: number | null;
  price: number;
  currency: string;
  joiningFee: number;
  access: {
    allBranchAccess: boolean;
    accessStartTime: string | null;
    accessEndTime: string | null;
    maxVisitsPerMonth: number | null;
    /** entries allowed per day (gate or desk); null = unlimited */
    maxEntriesPerDay: number | null;
  };
  freezePolicy: { allowed: boolean; minDays: number; maxDays: number; maxCount: number };
  cancellation: { policy: CancellationPolicy; coolingOffDays: number; cancellationFee: number };
  gracePeriodDays: number;
  status: PlanStatus;
  benefits: string[];
  version: number;
  /** Group classes per calendar month: null = unlimited, 0 = no classes included. */
  classCreditsPerMonth: number | null;
}

// ------------------------------------------------------------------ members
export type MemberStatus = 'ACTIVE' | 'SUSPENDED' | 'INACTIVE';
export type MembershipState = 'ACTIVE' | 'FROZEN' | 'GRACE' | 'PENDING_PAYMENT' | 'SCHEDULED' | 'NONE';
export type Gender = 'MALE' | 'FEMALE' | 'NON_BINARY' | 'UNDISCLOSED';

export interface MembershipSummary {
  state: MembershipState;
  subscriptionId: number | null;
  planCode: string | null;
  planName: string | null;
  startDate: string | null;
  endDate: string | null;
  daysRemaining: number | null;
}

export interface EmergencyContact {
  id?: number;
  name: string;
  relationship: string;
  phone: string;
  email?: string | null;
  primary: boolean;
}

export interface Member {
  id: number;
  memberCode: string;
  userId: number | null;
  homeBranchId: number;
  homeBranchName: string;
  firstName: string;
  lastName: string;
  email: string;
  phone: string;
  dateOfBirth: string | null;
  gender: Gender | null;
  addressLine1: string | null;
  city: string | null;
  state: string | null;
  postalCode: string | null;
  status: MemberStatus;
  registeredAt: string;
  notes: string | null;
  emergencyContacts: EmergencyContact[];
  membership: MembershipSummary;
  version: number;
}

export interface MemberListItem {
  id: number;
  memberCode: string;
  fullName: string;
  email: string;
  phone: string;
  homeBranchId: number;
  status: MemberStatus;
  membershipState: MembershipState;
  membershipEndDate: string | null;
  registeredAt: string;
}

export interface MemberRequest {
  homeBranchId: number;
  firstName: string;
  lastName: string;
  email: string;
  phone: string;
  dateOfBirth?: string | null;
  gender?: Gender | null;
  addressLine1?: string | null;
  city?: string | null;
  state?: string | null;
  postalCode?: string | null;
  notes?: string | null;
  emergencyContacts?: EmergencyContact[];
}

export interface AuditEntry {
  id: number;
  entityType: string;
  entityId: number;
  action: string;
  actorUserId: number | null;
  actorEmail: string | null;
  details: Record<string, unknown> | null;
  correlationId: string | null;
  occurredAt: string;
}

// ------------------------------------------------------------------ subscriptions
export type SubscriptionStatus =
  | 'PENDING_PAYMENT' | 'SCHEDULED' | 'ACTIVE' | 'FROZEN' | 'GRACE' | 'EXPIRED' | 'CANCELLED' | 'SUPERSEDED';
export type ChangeType = 'NEW' | 'RENEWAL' | 'UPGRADE' | 'DOWNGRADE';

export interface Subscription {
  id: number;
  memberId: number;
  memberCode: string;
  planId: number;
  planCode: string;
  planName: string;
  branchId: number;
  status: SubscriptionStatus;
  changeType: ChangeType;
  previousSubscriptionId: number | null;
  startDate: string;
  endDate: string;
  price: number;
  freezeDaysUsed: number;
  freezeCount: number;
  frozenFrom: string | null;
  frozenUntil: string | null;
  activatedAt: string | null;
  cancelledAt: string | null;
  cancellationReason: string | null;
  invoiceId: number | null;
  version: number;
  /** autopay: renewed automatically by debiting the saved method {@link autopayMethodId} */
  autoRenew: boolean;
  autopayMethodId: number | null;
}

export interface CancellationResponse {
  subscription: Subscription;
  refundAmount: number;
  refund: Refund | null;
}

// ------------------------------------------------------------------ billing
export type InvoiceStatus = 'ISSUED' | 'PAID' | 'VOID' | 'PARTIALLY_REFUNDED' | 'REFUNDED';
export type PaymentMethod =
  | 'CASH' | 'CARD' | 'UPI' | 'BANK_TRANSFER' | 'NET_BANKING' | 'WALLET' | 'EMI' | 'STORE_CREDIT';
export type PaymentProvider = 'OFFLINE' | 'INTERNAL' | 'SIMULATED' | 'RAZORPAY';
export type PaymentStatus = 'SUCCEEDED' | 'FAILED' | 'PARTIALLY_REFUNDED' | 'REFUNDED';

export interface Invoice {
  id: number;
  invoiceNumber: string;
  memberId: number;
  memberCode: string;
  memberName: string;
  subscriptionId: number | null;
  branchId: number;
  status: InvoiceStatus;
  currency: string;
  lines: { type: 'PLAN_FEE' | 'JOINING_FEE' | 'PRORATION_CREDIT' | 'PRICE_ADJUSTMENT'; description: string; amount: number }[];
  subtotal: number;
  discountCode: string | null;
  discountAmount: number;
  taxRate: number;
  taxAmount: number;
  totalAmount: number;
  amountPaid: number;
  amountRefunded: number;
  dueDate: string;
  overdue: boolean;
  issuedAt: string;
  paidAt: string | null;
}

export interface Payment {
  id: number;
  invoiceId: number;
  invoiceNumber: string;
  memberId: number;
  memberCode: string;
  memberName: string;
  amount: number;
  currency: string;
  method: PaymentMethod;
  status: PaymentStatus;
  gatewayReference: string | null;
  failureReason: string | null;
  refundedAmount: number;
  processedAt: string;
  provider: PaymentProvider;
  /** display only, e.g. "Visa •••• 4242" */
  instrument: string | null;
  emiTenureMonths: number | null;
  paymentMethodId: number | null;
  autopay: boolean;
}

export interface Refund {
  id: number;
  paymentId: number;
  amount: number;
  reason: string;
  status: 'SUCCEEDED' | 'FAILED';
  gatewayReference: string | null;
  processedAt: string;
  destination: 'ORIGINAL_METHOD' | 'STORE_CREDIT';
}

export interface Discount {
  id: number;
  code: string;
  description: string | null;
  discountType: 'PERCENTAGE' | 'FIXED_AMOUNT';
  value: number;
  validFrom: string;
  validUntil: string | null;
  maxRedemptions: number | null;
  timesRedeemed: number;
  active: boolean;
}

// ------------------------------------------------------------------ attendance
export type CheckInMethod = 'FRONT_DESK' | 'QR_CODE' | 'MOBILE_APP' | 'GATE';

export interface Visit {
  id: number;
  memberId: number;
  memberCode: string;
  memberName: string;
  branchId: number;
  subscriptionId: number;
  checkInTime: string;
  checkOutTime: string | null;
  method: CheckInMethod;
  autoCheckedOut: boolean;
  durationMinutes: number | null;
}

export interface DailySummary {
  branchId: number;
  date: string;
  checkIns: number;
  uniqueMembers: number;
  currentlyInside: number;
  capacity: number;
  averageVisitMinutes: number;
}

export interface Occupancy {
  branchId: number;
  currentlyInside: number;
  capacity: number;
  utilisation: number;
}

// ------------------------------------------------------------------ trainers
export type TrainerStatus = 'ACTIVE' | 'ON_LEAVE' | 'INACTIVE';
export type SessionStatus = 'SCHEDULED' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW';

export interface AvailabilitySlot {
  branchId: number;
  dayOfWeek: number;
  startTime: string;
  endTime: string;
}

export interface Trainer {
  id: number;
  userId: number;
  fullName: string;
  email: string;
  homeBranchId: number;
  employeeCode: string;
  bio: string | null;
  certifications: string | null;
  yearsOfExperience: number;
  hourlyRate: number | null;
  maxActiveClients: number;
  status: TrainerStatus;
  specializations: string[];
  availability: AvailabilitySlot[];
}

export interface TrainerAssignment {
  id: number;
  memberId: number;
  memberCode: string;
  memberName: string;
  trainerId: number;
  trainerName: string;
  status: 'ACTIVE' | 'ENDED';
  goal: string | null;
  assignedAt: string;
  endedAt: string | null;
}

export interface TrainingSession {
  id: number;
  trainerId: number;
  memberId: number;
  memberName: string;
  branchId: number;
  startsAt: string;
  endsAt: string;
  status: SessionStatus;
  notes: string | null;
}

export interface TrainerPerformance {
  trainerId: number;
  from: string;
  to: string;
  activeClients: number;
  sessionsByStatus: Record<SessionStatus, number>;
  completionRate: number;
  noShowRate: number;
}

// ------------------------------------------------------------------ workouts
export type WorkoutGoal = 'FAT_LOSS' | 'MUSCLE_GAIN' | 'STRENGTH' | 'ENDURANCE' | 'GENERAL_FITNESS' | 'REHABILITATION';
export type Difficulty = 'BEGINNER' | 'INTERMEDIATE' | 'ADVANCED';

export interface Exercise {
  id: number;
  name: string;
  muscleGroup: string;
  equipment: string | null;
  description: string | null;
}

export interface PlanExercise {
  exerciseId: number;
  exerciseName: string;
  muscleGroup: string;
  dayNumber: number;
  position: number;
  sets: number;
  reps: number | null;
  weightKg: number | null;
  durationSeconds: number | null;
  restSeconds: number | null;
  notes: string | null;
}

export interface WorkoutPlan {
  id: number;
  name: string;
  description: string | null;
  goal: WorkoutGoal;
  difficulty: Difficulty;
  durationWeeks: number;
  template: boolean;
  memberId: number | null;
  trainerId: number;
  sourceTemplateId: number | null;
  status: 'DRAFT' | 'ACTIVE' | 'COMPLETED' | 'ARCHIVED';
  startDate: string | null;
  endDate: string | null;
  trainerNotes: string | null;
  exercises: PlanExercise[];
  version: number;
}

export interface Progress {
  id: number;
  memberId: number;
  workoutPlanId: number | null;
  recordedAt: string;
  weightKg: number | null;
  bodyFatPct: number | null;
  muscleMassKg: number | null;
  chestCm: number | null;
  waistCm: number | null;
  hipsCm: number | null;
  restingHeartRate: number | null;
  notes: string | null;
  recordedBy: number | null;
}

// ------------------------------------------------------------------ complaints
export type ComplaintCategory = 'FACILITY' | 'EQUIPMENT' | 'STAFF' | 'TRAINER' | 'BILLING' | 'HYGIENE' | 'MEMBERSHIP' | 'OTHER';
export type ComplaintPriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type ComplaintStatus = 'OPEN' | 'IN_PROGRESS' | 'ESCALATED' | 'RESOLVED' | 'CLOSED' | 'REOPENED';

export interface Complaint {
  id: number;
  ticketNumber: string;
  memberId: number | null;
  branchId: number;
  raisedBy: number;
  category: ComplaintCategory;
  priority: ComplaintPriority;
  status: ComplaintStatus;
  subject: string;
  description: string;
  assignedTo: number | null;
  resolution: string | null;
  escalationLevel: number;
  slaDueAt: string;
  slaBreached: boolean;
  firstResponseAt: string | null;
  resolvedAt: string | null;
  closedAt: string | null;
  createdAt: string;
  version: number;
  raisedByName: string | null;
  assignedToName: string | null;
}

export interface ComplaintComment {
  id: number;
  authorId: number;
  authorName: string | null;
  body: string;
  internal: boolean;
  createdAt: string;
}

// ------------------------------------------------------------------ notifications
export interface NotificationItem {
  id: number;
  type: string;
  channel: 'IN_APP' | 'EMAIL' | 'SMS' | 'PUSH';
  title: string;
  body: string;
  status: 'PENDING' | 'SENT' | 'FAILED';
  attempts: number;
  lastError: string | null;
  createdAt: string;
  sentAt: string | null;
  readAt: string | null;
}

// ------------------------------------------------------------------ dashboards
export interface AdminDashboard {
  scope: string;
  businessDate: string;
  totalMembers: number;
  activeMemberships: number;
  frozenMemberships: number;
  membershipsInGrace: number;
  expiredThisMonth: number;
  expiringNext7Days: number;
  revenueThisMonth: number;
  refundsThisMonth: number;
  pendingInvoices: number;
  pendingAmount: number;
  overdueInvoices: number;
  checkInsToday: number;
  newRegistrationsThisMonth: number;
  renewalsThisMonth: number;
  openComplaints: number;
  memberChurn: {
    month: string;
    churnRate: number | null;
    churnedMembers: number | null;
    membersAtStart: number | null;
    renewalRate: number | null;
  } | null;
  degraded: string[];
  generatedAt: string;
}

export interface TrainerDashboard {
  trainerId: number;
  trainerName: string;
  assignedMembers: number;
  maxActiveClients: number;
  todaysSessions: TrainingSession[];
  activeWorkoutPlans: number;
  memberProgress: {
    memberId: number;
    memberCode: string;
    memberName: string;
    workoutPlan: string | null;
    lastVisit: string | null;
    startWeightKg: number | null;
    latestWeightKg: number | null;
    weightChangeKg: number | null;
    lastMeasuredAt: string | null;
  }[];
}

export interface MemberDashboard {
  memberId: number;
  memberCode: string;
  name: string;
  membership: MembershipSummary;
  expiryDate: string | null;
  attendance: {
    visitsThisMonth: number;
    visitsLast30Days: number;
    totalVisits: number;
    lastVisit: string | null;
    currentlyCheckedIn: boolean;
  };
  workoutPlan: WorkoutPlan | null;
  trainer: TrainerAssignment | null;
  upcomingSessions: TrainingSession[];
  recentPayments: Payment[];
  unreadNotifications: number;
}
