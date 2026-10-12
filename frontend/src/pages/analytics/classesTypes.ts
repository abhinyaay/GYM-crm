// TypeScript mirror of analytics-service/app/schemas/classes.py (GET /analytics/classes).
// Rates are fractions 0..1 (null when the denominator is 0). "occupied" = BOOKED + ATTENDED + NO_SHOW seats.
import type { QueryMeta } from './types';

export interface ClassesSummary {
  /** non-cancelled sessions */
  sessions: number;
  cancelledSessions: number;
  capacity: number;
  occupied: number;
  fillRate: number | null;
  attended: number;
  noShows: number;
  attendanceRate: number | null;
  lateCancels: number;
  lateCancelRate: number | null;
  waitlistJoins: number;
  waitlistPromotions: number;
  sessionsFull: number;
  avgOccupiedPerSession: number | null;
}

export interface ClassTypeStats {
  classTypeId: number;
  name: string | null;
  category: string | null;
  sessions: number;
  capacity: number;
  occupied: number;
  fillRate: number | null;
  attended: number;
  noShows: number;
  attendanceRate: number | null;
  lateCancels: number;
  lateCancelRate: number | null;
  waitlistJoins: number;
}

export interface ClassTimeSlotStats {
  /** ISO weekday, 1 = Monday */
  weekday: number;
  /** local hour of the session start */
  hour: number;
  sessions: number;
  occupied: number;
  capacity: number;
  fillRate: number | null;
  waitlistJoins: number;
}

export interface InstructorClassStats {
  trainerId: number;
  name: string | null;
  sessionsTaught: number;
  occupied: number;
  avgFillRate: number | null;
  attended: number;
  noShows: number;
  attendanceRate: number | null;
}

export interface DailyClassStats {
  date: string;
  sessions: number;
  occupied: number;
  capacity: number;
  attended: number;
  fillRate: number | null;
}

export interface ClassesResponse {
  meta: QueryMeta;
  summary: ClassesSummary;
  byClassType: ClassTypeStats[];
  timeSlots: ClassTimeSlotStats[];
  instructors: InstructorClassStats[];
  daily: DailyClassStats[];
}
