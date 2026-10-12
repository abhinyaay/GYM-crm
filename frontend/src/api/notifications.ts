// E-mail / SMS channels: mirrors of com.gymops.notification.{preference.PreferenceDtos, admin.MessagingDtos}.

export type NotificationChannel = 'IN_APP' | 'EMAIL' | 'SMS' | 'PUSH';
export type DeliveryStatus = 'PENDING' | 'SENT' | 'FAILED' | 'SKIPPED';
export type NotificationCategory =
  | 'ACCOUNT' | 'BILLING' | 'MEMBERSHIP' | 'CLASSES' | 'TRAINING' | 'SUPPORT' | 'ACCESS' | 'MARKETING';

export const CHANNELS: NotificationChannel[] = ['EMAIL', 'SMS', 'IN_APP'];
export const DELIVERY_STATUSES: DeliveryStatus[] = ['PENDING', 'SENT', 'FAILED', 'SKIPPED'];

/** Notification types (mirrors com.gymops.notification.NotificationType). */
export const NOTIFICATION_TYPES = [
  'SUBSCRIPTION_ACTIVATED', 'SUBSCRIPTION_FROZEN', 'SUBSCRIPTION_RESUMED', 'SUBSCRIPTION_CANCELLED', 'MEMBERSHIP_EXPIRED',
  'RENEWAL_REMINDER', 'MEMBERSHIP_EXPIRY_WARNING', 'PAYMENT_CONFIRMATION', 'PAYMENT_FAILED', 'REFUND_PROCESSED',
  'ATTENDANCE_MILESTONE', 'VISIT_AUTO_CLOSED', 'TRAINER_ASSIGNED', 'SESSION_SCHEDULED', 'WORKOUT_PLAN_ASSIGNED',
  'WORKOUT_PLAN_UPDATED', 'COMPLAINT_UPDATED', 'COMPLAINT_ESCALATED', 'AUTOPAY_SCHEDULED', 'AUTOPAY_SUCCEEDED',
  'AUTOPAY_FAILED', 'AUTOPAY_DISABLED', 'PAYMENT_METHOD_SAVED', 'STORE_CREDIT_ISSUED', 'CLASS_BOOKED', 'CLASS_WAITLISTED',
  'CLASS_WAITLIST_PROMOTED', 'CLASS_CANCELLED', 'CLASS_RESCHEDULED', 'CLASS_REMINDER', 'LEAD_ASSIGNED', 'LEAD_FOLLOW_UP_DUE',
  'TRIAL_STARTED', 'TRIAL_ENDING', 'TEST_MESSAGE', 'ACCESS_CREDENTIAL_ENROLLED', 'BIOMETRIC_CONSENT_WITHDRAWN',
  'ACCESS_DEVICE_OFFLINE', 'ACCESS_ALARM', 'PLAN_PRICE_CHANGED', 'CAMPAIGN_MESSAGE', 'REFERRAL_SIGNED_UP',
  'REFERRAL_REWARD_EARNED',
] as const;

export const DELIVERY_STATUS_COLOR: Record<DeliveryStatus, string> = {
  PENDING: 'yellow', SENT: 'teal', FAILED: 'red', SKIPPED: 'gray',
};

// ------------------------------------------------------------------ preferences

export interface ChannelPreference {
  channel: NotificationChannel;
  enabled: boolean;
  /** Cannot be switched off (legal / security notices). */
  locked: boolean;
  defaultEnabled: boolean;
}

export interface CategoryPreferences {
  category: NotificationCategory;
  label: string;
  description: string;
  promotional: boolean;
  lockedReason: string | null;
  channels: ChannelPreference[];
}

export interface PreferencesResponse {
  memberId: number;
  email: string;
  /** E.164 number SMS goes to; null when the member's phone cannot receive SMS. */
  smsNumber: string | null;
  smsReachable: boolean;
  quietHours: string;
  categories: CategoryPreferences[];
}

export interface PreferenceChange {
  category: NotificationCategory;
  channel: NotificationChannel;
  enabled: boolean;
}

export interface UnsubscribeResponse {
  category: NotificationCategory;
  categoryLabel: string;
  channel: NotificationChannel;
  subscribed: boolean;
  email: string;
}

export const preferencesPath = (memberId: number) => `/api/v1/members/${memberId}/notification-preferences`;

// ------------------------------------------------------------------ messaging centre

export interface NotificationLogEntry {
  id: number;
  type: string;
  category: NotificationCategory;
  channel: NotificationChannel;
  status: DeliveryStatus;
  memberId: number | null;
  memberName: string | null;
  recipientUserId: number | null;
  destination: string | null;
  title: string;
  body: string;
  attempts: number;
  lastError: string | null;
  provider: string | null;
  providerMessageId: string | null;
  segments: number | null;
  nextAttemptAt: string;
  createdAt: string;
  sentAt: string | null;
  readAt: string | null;
  requestedByUserId: number | null;
}

export interface ProviderStatus {
  channel: NotificationChannel;
  provider: string;
  /** Messages actually leave the system (not the log provider). */
  live: boolean;
  configured: boolean;
  details: Record<string, string | number | boolean | null>;
}

export interface ProvidersResponse {
  email: ProviderStatus;
  sms: ProviderStatus;
  quietHours: string;
  publicBaseUrl: string;
}

export interface TestMessageRequest {
  channel: 'EMAIL' | 'SMS';
  memberId?: number;
  to?: string;
  message?: string;
}
