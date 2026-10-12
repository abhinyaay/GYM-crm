import { describe, expect, it } from 'vitest';
import type { RoleName, UserInfo } from '@/api/types';
import { hasAnyRole, homePath, isMemberOnly, isTrainerOnly } from '@/auth/roles';

const u = (...roles: RoleName[]): UserInfo => ({
  id: 1, email: 'x', fullName: 'X', roles, branchIds: [], memberId: null, trainerId: null,
});

describe('role helpers', () => {
  it('routes each role to its home page', () => {
    expect(homePath(null)).toBe('/login');
    expect(homePath(u('MEMBER'))).toBe('/me');
    expect(homePath(u('TRAINER'))).toBe('/trainer');
    expect(homePath(u('RECEPTIONIST'))).toBe('/front-desk');
    expect(homePath(u('MANAGER'))).toBe('/dashboard');
    expect(homePath(u('SUPER_ADMIN'))).toBe('/dashboard');
  });

  it('classifies users', () => {
    expect(isMemberOnly(u('MEMBER'))).toBe(true);
    expect(isMemberOnly(u('MEMBER', 'TRAINER'))).toBe(false);
    expect(isTrainerOnly(u('TRAINER'))).toBe(true);
    expect(isTrainerOnly(u('TRAINER', 'MANAGER'))).toBe(false);
    expect(hasAnyRole(u('RECEPTIONIST'), ['MANAGER', 'RECEPTIONIST'])).toBe(true);
    expect(hasAnyRole(null, ['MANAGER'])).toBe(false);
  });
});
