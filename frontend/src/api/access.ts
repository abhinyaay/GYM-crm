// Access control (gates, credentials, biometric consent): mirrors com.gymops.access.dto.AccessDtos.

export type DeviceType = 'TURNSTILE' | 'DOOR' | 'KIOSK';
export type DeviceDirection = 'ENTRY' | 'EXIT' | 'BOTH';
export type DeviceStatus = 'ACTIVE' | 'DISABLED';
export type Direction = 'ENTRY' | 'EXIT';
export type Decision = 'GRANTED' | 'DENIED';
export type CredentialType = 'FINGERPRINT' | 'FACE' | 'RFID_CARD' | 'QR';
export type CredentialStatus = 'PENDING' | 'ACTIVE' | 'SUSPENDED' | 'REVOKED';
export type CommandType = 'ENROLL_TEMPLATE' | 'DELETE_TEMPLATE';
export type CommandStatus = 'PENDING' | 'DELIVERED' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';
export type AlarmType = 'DOOR_FORCED' | 'DOOR_HELD_OPEN' | 'TAILGATING' | 'TAMPER' | 'DEVICE_OFFLINE';
export type AccessReason =
  | 'OK' | 'UNKNOWN_CREDENTIAL' | 'CREDENTIAL_PENDING' | 'CREDENTIAL_REVOKED' | 'CREDENTIAL_SUSPENDED'
  | 'CONSENT_WITHDRAWN' | 'QR_INVALID' | 'QR_EXPIRED' | 'QR_REUSED' | 'MEMBER_SUSPENDED' | 'MEMBER_INACTIVE'
  | 'NO_ACTIVE_MEMBERSHIP' | 'FROZEN' | 'OUTSIDE_PLAN_HOURS' | 'BRANCH_NOT_ALLOWED' | 'VISIT_LIMIT' | 'AT_CAPACITY'
  | 'BRANCH_CLOSED' | 'ANTI_PASSBACK' | 'DIRECTION_NOT_ALLOWED' | 'OFFLINE_DENIED';

export const DEVICE_TYPES: DeviceType[] = ['TURNSTILE', 'DOOR', 'KIOSK'];
export const DEVICE_DIRECTIONS: DeviceDirection[] = ['ENTRY', 'EXIT', 'BOTH'];
export const ALARM_TYPES: AlarmType[] = ['DOOR_FORCED', 'DOOR_HELD_OPEN', 'TAILGATING', 'TAMPER'];

/** Human readable reason for gate screens. */
export const REASON_TEXT: Record<AccessReason, string> = {
  OK: 'Access granted',
  UNKNOWN_CREDENTIAL: 'Credential not recognised',
  CREDENTIAL_PENDING: 'Enrolment not completed',
  CREDENTIAL_REVOKED: 'Credential revoked',
  CREDENTIAL_SUSPENDED: 'Credential suspended',
  CONSENT_WITHDRAWN: 'Biometric consent not active',
  QR_INVALID: 'Invalid QR code',
  QR_EXPIRED: 'QR code expired',
  QR_REUSED: 'QR code already used',
  MEMBER_SUSPENDED: 'Member account suspended',
  MEMBER_INACTIVE: 'Member account inactive',
  NO_ACTIVE_MEMBERSHIP: 'No active membership',
  FROZEN: 'Membership frozen',
  OUTSIDE_PLAN_HOURS: 'Outside plan hours',
  BRANCH_NOT_ALLOWED: 'Plan not valid at this branch',
  VISIT_LIMIT: 'Monthly visit limit reached',
  AT_CAPACITY: 'Branch at capacity',
  BRANCH_CLOSED: 'Branch closed',
  ANTI_PASSBACK: 'Anti-passback',
  DIRECTION_NOT_ALLOWED: 'Wrong direction for this gate',
  OFFLINE_DENIED: 'Refused offline by the device',
};

export interface Device {
  id: number;
  deviceUid: string;
  branchId: number;
  branchName: string;
  name: string;
  type: DeviceType;
  direction: DeviceDirection;
  vendor: string | null;
  model: string | null;
  serialNumber: string | null;
  firmwareVersion: string | null;
  status: DeviceStatus;
  keyVersion: number;
  secretIssuedAt: string;
  lastHeartbeatAt: string | null;
  lastSeenIp: string | null;
  offlineSince: string | null;
  online: boolean;
  createdAt: string;
}

export interface DeviceSecret {
  device: Device;
  deviceSecret: string;
  signatureScheme: string;
}

export interface DeviceRequest {
  branchId: number;
  name: string;
  type: DeviceType;
  direction: DeviceDirection;
  vendor?: string;
  model?: string;
  serialNumber?: string;
  firmwareVersion?: string;
}

export interface CommandView {
  id: number;
  deviceId: number;
  type: CommandType;
  credentialType: CredentialType;
  credentialId: number | null;
  templateRef: string | null;
  memberRef: string | null;
  status: CommandStatus;
  deliveryCount: number;
  createdAt: string;
  deliveredAt: string | null;
  completedAt: string | null;
  resultMessage: string | null;
}

export interface Credential {
  id: number;
  memberId: number;
  type: CredentialType;
  status: CredentialStatus;
  displayHint: string | null;
  label: string | null;
  templateRef: string | null;
  enrolledDeviceId: number | null;
  enrolledDeviceName: string | null;
  createdAt: string;
  activatedAt: string | null;
  suspendedAt: string | null;
  suspensionSource: 'MANUAL' | 'MEMBER_STATUS' | null;
  revokedAt: string | null;
  revokeReason: string | null;
  commands: CommandView[];
}

export interface EnrolledCredential {
  credentialId: number;
  memberId: number;
  memberCode: string;
  memberName: string;
  type: CredentialType;
  templateRef: string | null;
  displayHint: string | null;
}

export interface ConsentNotice {
  version: string;
  purpose: string;
  text: string;
  sha256: string;
}

export interface Consent {
  id: number;
  memberId: number;
  purpose: string;
  noticeVersion: string;
  noticeSha256: string;
  captureMethod: 'STAFF_ASSISTED' | 'SELF_SERVICE';
  grantedAt: string;
  grantedBy: number | null;
  withdrawnAt: string | null;
  withdrawnBy: number | null;
  withdrawalReason: string | null;
  active: boolean;
}

export interface ConsentStatus {
  memberId: number;
  active: boolean;
  current: Consent | null;
  history: Consent[];
  currentNoticeVersion: string;
}

export interface ConsentWithdrawal {
  consent: Consent;
  credentialsRevoked: number;
  deleteCommandsQueued: number;
}

export interface AccessEvent {
  id: number;
  deviceId: number;
  deviceName: string | null;
  branchId: number;
  memberId: number | null;
  memberCode: string | null;
  memberName: string | null;
  credentialType: CredentialType;
  credentialHint: string | null;
  direction: Direction;
  decision: Decision;
  reasonCode: AccessReason | null;
  message: string | null;
  attendanceId: number | null;
  offline: boolean;
  antiPassbackViolation: boolean;
  occurredAt: string;
  receivedAt: string;
}

export interface Alarm {
  id: number;
  deviceId: number;
  deviceName: string | null;
  branchId: number;
  type: AlarmType;
  details: string | null;
  occurredAt: string;
  receivedAt: string;
  acknowledgedAt: string | null;
  acknowledgedBy: number | null;
}

export interface AccessStats {
  branchId: number | null;
  date: string;
  entriesGranted: number;
  exitsGranted: number;
  denied: number;
  offlineEvents: number;
  deniedByReason: Record<string, number>;
  grantedByCredential: Record<string, number>;
  devices: number;
  devicesOnline: number;
  devicesOffline: number;
  openAlarms: number;
}

export interface EntryPass {
  token: string;
  memberId: number;
  memberCode: string;
  memberName: string;
  issuedAt: string;
  expiresAt: string;
  windowSeconds: number;
  serverTime: string;
}

// ------------------------------------------------------------------ device (gate) API

export interface GateDecision {
  eventId: number | null;
  deviceEventId: string;
  decision: Decision;
  reasonCode: AccessReason | null;
  message: string | null;
  openGate: boolean;
  openSeconds: number;
  memberDisplayName: string | null;
  direction: Direction;
  offline: boolean;
  antiPassbackViolation: boolean;
  replayed: boolean;
  serverTime: string;
}

export interface GateCommand {
  id: number;
  type: CommandType;
  credentialType: CredentialType;
  templateRef: string | null;
  memberRef: string | null;
  createdAt: string;
  deliveryCount: number;
}

export interface HeartbeatResponse {
  serverTime: string;
  deviceStatus: DeviceStatus;
  pendingCommands: number;
  allowlistRefreshSeconds: number;
}

export interface AllowlistEntry {
  credentialType: CredentialType;
  credentialHash: string;
  memberRef: string;
  displayName: string;
  validFrom: string;
  validUntil: string;
  dailyAccessStart: string | null;
  dailyAccessEnd: string | null;
}

export interface Allowlist {
  deviceId: string;
  branchId: number;
  branchCode: string;
  timezone: string;
  generatedAt: string;
  validUntil: string;
  hashScheme: string;
  entries: AllowlistEntry[];
  notes: string;
}

/** Deterministic UID of a seeded demo card (same formula as AccessDemoSeeder): "D3" + 6 hex digits of the member id. */
export const demoCardUid = (memberId: number) => 'D3' + memberId.toString(16).toUpperCase().padStart(6, '0');
