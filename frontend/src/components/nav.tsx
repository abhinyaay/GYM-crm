import {
  IconBarbell, IconBuildingStore, IconCalendarEvent, IconChartBar, IconCreditCard, IconDashboard, IconDoorEnter,
  IconHome, IconId, IconMessageReport, IconReceipt, IconStretching, IconTargetArrow, IconTicket, IconUserCog, IconUsers,
  IconUsersGroup, IconWallet, IconYoga, IconFingerprint, IconSpeakerphone, IconGift, IconMail, IconQrcode, IconBell, IconBook, IconSettings,
} from '@tabler/icons-react';
import type { ReactNode } from 'react';
import type { RoleName } from '@/api/types';
import type { FeatureKey } from '@/lib/features';

export interface NavItem {
  label: string;
  to: string;
  icon: ReactNode;
  roles: RoleName[];
  /** shown only while this optional module is switched on (Settings) */
  feature?: FeatureKey;
}

const ADMINS: RoleName[] = ['SUPER_ADMIN', 'GYM_ADMIN'];
const MANAGERS: RoleName[] = [...ADMINS, 'MANAGER'];
const DESK: RoleName[] = [...MANAGERS, 'RECEPTIONIST'];

export const NAV: NavItem[] = [
  { label: 'Dashboard', to: '/dashboard', icon: <IconDashboard size={18} />, roles: MANAGERS },
  { label: 'Front desk', to: '/front-desk', icon: <IconDoorEnter size={18} />, roles: DESK },
  { label: 'Members', to: '/members', icon: <IconUsers size={18} />, roles: DESK },
  { label: 'Billing', to: '/billing', icon: <IconReceipt size={18} />, roles: DESK },
  { label: 'Plans', to: '/plans', icon: <IconTicket size={18} />, roles: DESK },
  { label: 'Classes', to: '/classes', icon: <IconYoga size={18} />, roles: [...DESK, 'TRAINER'] },
  { label: 'Leads & trials', to: '/leads', icon: <IconTargetArrow size={18} />, roles: DESK },
  { label: 'Access control', to: '/access', icon: <IconFingerprint size={18} />, roles: DESK, feature: 'gateAccess' },
  { label: 'Campaigns', to: '/campaigns', icon: <IconSpeakerphone size={18} />, roles: MANAGERS },
  { label: 'Referrals', to: '/referrals', icon: <IconGift size={18} />, roles: MANAGERS },
  { label: 'Messaging', to: '/messaging', icon: <IconMail size={18} />, roles: MANAGERS },
  { label: 'Trainers', to: '/trainers', icon: <IconUsersGroup size={18} />, roles: MANAGERS },
  { label: 'Workouts', to: '/workouts', icon: <IconBarbell size={18} />, roles: [...MANAGERS, 'TRAINER'] },
  { label: 'Complaints', to: '/complaints', icon: <IconMessageReport size={18} />, roles: DESK },
  { label: 'Analytics', to: '/insights', icon: <IconChartBar size={18} />, roles: MANAGERS },
  { label: 'Branches', to: '/branches', icon: <IconBuildingStore size={18} />, roles: MANAGERS },
  { label: 'Users', to: '/users', icon: <IconUserCog size={18} />, roles: ADMINS },
  { label: 'Settings', to: '/settings', icon: <IconSettings size={18} />, roles: ADMINS },
  { label: 'Setup guide', to: '/guide', icon: <IconBook size={18} />, roles: MANAGERS },
  // trainer portal
  { label: 'My day', to: '/trainer', icon: <IconHome size={18} />, roles: ['TRAINER'] },
  { label: 'Schedule', to: '/trainer/schedule', icon: <IconCalendarEvent size={18} />, roles: ['TRAINER'] },
  // member portal
  { label: 'Home', to: '/me', icon: <IconHome size={18} />, roles: ['MEMBER'] },
  { label: 'Membership', to: '/me/membership', icon: <IconId size={18} />, roles: ['MEMBER'] },
  { label: 'Classes', to: '/me/classes', icon: <IconYoga size={18} />, roles: ['MEMBER'] },
  { label: 'Payments', to: '/me/payments', icon: <IconCreditCard size={18} />, roles: ['MEMBER'] },
  { label: 'Cards & autopay', to: '/me/payment-methods', icon: <IconWallet size={18} />, roles: ['MEMBER'] },
  { label: 'Entry pass', to: '/me/pass', icon: <IconQrcode size={18} />, roles: ['MEMBER'], feature: 'gateAccess' },
  { label: 'Refer a friend', to: '/me/referrals', icon: <IconGift size={18} />, roles: ['MEMBER'] },
  { label: 'Notifications', to: '/me/notifications', icon: <IconBell size={18} />, roles: ['MEMBER'] },
  { label: 'My workout', to: '/me/workout', icon: <IconStretching size={18} />, roles: ['MEMBER'] },
  { label: 'Support', to: '/me/complaints', icon: <IconMessageReport size={18} />, roles: ['MEMBER'] },
];
