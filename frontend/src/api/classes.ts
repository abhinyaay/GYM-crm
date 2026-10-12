// Types for the group-classes API (/api/v1/classes/**), mirroring com.gymops.classes.dto.ClassDtos.

export type ClassCategory = 'YOGA' | 'HIIT' | 'CYCLING' | 'STRENGTH' | 'DANCE' | 'PILATES' | 'BOXING' | 'MOBILITY' | 'OTHER';
export type ClassIntensity = 'LOW' | 'MEDIUM' | 'HIGH';
export type ClassSessionStatus = 'SCHEDULED' | 'CANCELLED' | 'COMPLETED';
export type BookingStatus = 'BOOKED' | 'WAITLISTED' | 'CANCELLED' | 'LATE_CANCELLED' | 'ATTENDED' | 'NO_SHOW';

export const CLASS_CATEGORIES: ClassCategory[] = ['YOGA', 'HIIT', 'CYCLING', 'STRENGTH', 'DANCE', 'PILATES', 'BOXING', 'MOBILITY', 'OTHER'];
export const CLASS_INTENSITIES: ClassIntensity[] = ['LOW', 'MEDIUM', 'HIGH'];

export interface ClassType {
  id: number;
  name: string;
  description: string | null;
  category: ClassCategory;
  intensity: ClassIntensity;
  defaultDurationMin: number;
  defaultCapacity: number;
  active: boolean;
}

export interface ClassTypeRequest {
  name: string;
  description: string | null;
  category: ClassCategory;
  intensity: ClassIntensity;
  defaultDurationMin: number;
  defaultCapacity: number;
  active?: boolean;
}

export interface ClassSession {
  id: number;
  classTypeId: number;
  classTypeName: string;
  category: ClassCategory;
  intensity: ClassIntensity;
  branchId: number;
  branchName: string;
  instructorId: number;
  instructorName: string;
  room: string | null;
  startsAt: string;
  endsAt: string;
  durationMinutes: number;
  capacity: number;
  waitlistCapacity: number;
  bookingOpensHours: number;
  cancelCutoffMinutes: number;
  bookingOpensAt: string;
  cancelCutoffAt: string;
  status: ClassSessionStatus;
  seriesId: string | null;
  cancellationReason: string | null;
  /** seat holders: BOOKED + ATTENDED + NO_SHOW */
  booked: number;
  waitlisted: number;
  spotsLeft: number;
  attended: number;
  noShows: number;
  /** the calling member's latest booking for this class (members only) */
  myBookingId: number | null;
  myBookingStatus: BookingStatus | null;
}

export interface RosterEntry {
  bookingId: number;
  memberId: number;
  memberCode: string;
  memberName: string;
  status: BookingStatus;
  bookedAt: string;
  promotedAt: string | null;
  cancelledAt: string | null;
  attendedAt: string | null;
  waitlistPosition: number | null;
}

export interface ClassSessionDetail {
  session: ClassSession;
  /** null for members */
  roster: RosterEntry[] | null;
  canManage: boolean;
  canMarkAttendance: boolean;
  canBookForMembers: boolean;
}

export interface SessionRequest {
  classTypeId: number;
  branchId: number;
  instructorId: number;
  room: string | null;
  startsAt: string;
  durationMinutes: number | null;
  capacity: number | null;
  waitlistCapacity: number | null;
  bookingOpensHours: number | null;
  cancelCutoffMinutes: number | null;
}

export interface SeriesRequest {
  session: SessionRequest;
  /** ISO days, 1 = Monday */
  daysOfWeek: number[];
  weeks: number;
}

export interface SessionUpdateRequest {
  instructorId: number;
  room: string | null;
  startsAt: string;
  durationMinutes: number;
  capacity: number;
  waitlistCapacity: number;
  bookingOpensHours: number;
  cancelCutoffMinutes: number;
}

export interface SkippedSlot {
  startsAt: string;
  reason: string;
}

export interface SeriesResponse {
  seriesId: string;
  created: ClassSession[];
  skipped: SkippedSlot[];
}

export interface SeriesCancelResponse {
  seriesId: string;
  cancelledSessions: number;
  cancelledBookings: number;
}

export interface ClassBooking {
  id: number;
  sessionId: number;
  memberId: number;
  memberCode: string;
  memberName: string;
  status: BookingStatus;
  bookedAt: string;
  promotedAt: string | null;
  cancelledAt: string | null;
  attendedAt: string | null;
  waitlistPosition: number | null;
  classTypeName: string;
  category: ClassCategory;
  branchId: number;
  branchName: string;
  instructorName: string;
  room: string | null;
  startsAt: string;
  endsAt: string;
  sessionStatus: ClassSessionStatus;
  cancelCutoffAt: string;
}

export interface ClassCredits {
  memberId: number;
  /** YYYY-MM */
  month: string;
  planCode: string | null;
  planName: string | null;
  includesClasses: boolean;
  unlimited: boolean;
  /** null when unlimited */
  creditsPerMonth: number | null;
  used: number;
  /** null when unlimited */
  remaining: number | null;
  recentNoShows: number;
  noShowThreshold: number;
  bookingBlockedUntil: string | null;
}

export interface ClassJobRunSummary {
  sessionsCompleted: number;
  attended: number;
  noShows: number;
  waitlistExpired: number;
  remindersQueued: number;
  failures: number;
}

/** Badge colours for booking / class statuses (StatusBadge has no entries for these). */
export const BOOKING_COLORS: Record<BookingStatus, string> = {
  BOOKED: 'blue',
  WAITLISTED: 'yellow',
  CANCELLED: 'gray',
  LATE_CANCELLED: 'orange',
  ATTENDED: 'teal',
  NO_SHOW: 'red',
};

export const CATEGORY_COLORS: Record<ClassCategory, string> = {
  YOGA: 'teal',
  HIIT: 'red',
  CYCLING: 'orange',
  STRENGTH: 'indigo',
  DANCE: 'pink',
  PILATES: 'cyan',
  BOXING: 'grape',
  MOBILITY: 'lime',
  OTHER: 'gray',
};
