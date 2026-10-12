import type { RoleName, UserInfo } from '@/api/types';

export const ORG_WIDE: RoleName[] = ['SUPER_ADMIN', 'GYM_ADMIN'];
export const MANAGERS: RoleName[] = ['SUPER_ADMIN', 'GYM_ADMIN', 'MANAGER'];
export const FRONT_DESK: RoleName[] = ['SUPER_ADMIN', 'GYM_ADMIN', 'MANAGER', 'RECEPTIONIST'];
export const STAFF: RoleName[] = ['SUPER_ADMIN', 'GYM_ADMIN', 'MANAGER', 'RECEPTIONIST', 'TRAINER'];

export const hasAnyRole = (user: UserInfo | null, roles: RoleName[]) =>
  !!user && user.roles.some((r) => roles.includes(r));

export const isMemberOnly = (user: UserInfo | null) =>
  !!user && user.roles.length === 1 && user.roles[0] === 'MEMBER';

export const isTrainerOnly = (user: UserInfo | null) =>
  !!user && user.roles.includes('TRAINER') && !hasAnyRole(user, FRONT_DESK);

/** Landing page for a user after login. */
export function homePath(user: UserInfo | null): string {
  if (!user) return '/login';
  if (isMemberOnly(user)) return '/me';
  if (isTrainerOnly(user)) return '/trainer';
  if (hasAnyRole(user, MANAGERS)) return '/dashboard';
  return '/front-desk';
}
