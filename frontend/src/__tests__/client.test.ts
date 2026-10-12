import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError, applyTokens, buildUrl, clearSession, getCurrentUser, refreshSession } from '@/api/client';
import type { TokenResponse } from '@/api/types';

const user = { id: 1, email: 'a@b.c', fullName: 'A', roles: ['MANAGER' as const], branchIds: [1], memberId: null, trainerId: null };
const tokens = (access: string, refresh: string): TokenResponse => ({
  tokenType: 'Bearer', accessToken: access, accessTokenExpiresAt: '', refreshToken: refresh, refreshTokenExpiresAt: '', user,
});
const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

describe('api client', () => {
  beforeEach(() => {
    localStorage.clear();
    clearSession();
  });
  afterEach(() => vi.restoreAllMocks());

  it('builds query strings and skips empty values', () => {
    expect(buildUrl('/x', { a: 1, b: '', c: undefined, d: null, e: 'y z' })).toBe('/x?a=1&e=y+z');
    expect(buildUrl('/x')).toBe('/x');
  });

  it('refreshes once on 401 and retries the request with the new token', async () => {
    applyTokens(tokens('old-access', 'refresh-1'));
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = String(input);
      if (url.endsWith('/auth/refresh')) return json(200, tokens('new-access', 'refresh-2'));
      const auth = (init?.headers as Record<string, string>).Authorization;
      return auth === 'Bearer new-access' ? json(200, { ok: true }) : json(401, { code: 'INVALID_TOKEN' });
    });
    await expect(api('/api/v1/members')).resolves.toEqual({ ok: true });
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(localStorage.getItem('gymops.refreshToken')).toBe('refresh-2');
  });

  it('runs a single refresh for concurrent callers (refresh tokens rotate)', async () => {
    applyTokens(tokens('a', 'r1'));
    let refreshCalls = 0;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => {
      refreshCalls++;
      await new Promise((r) => setTimeout(r, 10));
      return json(200, tokens('b', 'r2'));
    });
    const results = await Promise.all([refreshSession(), refreshSession(), refreshSession()]);
    expect(results).toEqual([true, true, true]);
    expect(refreshCalls).toBe(1);
  });

  it('clears the session when the refresh token is rejected', async () => {
    applyTokens(tokens('a', 'r1'));
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(json(401, { code: 'INVALID_TOKEN' }));
    await expect(api('/api/v1/members')).rejects.toBeInstanceOf(ApiError);
    expect(getCurrentUser()).toBeNull();
    expect(localStorage.getItem('gymops.refreshToken')).toBeNull();
  });

  it('exposes code and field violations from the error envelope', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(json(400, {
      status: 400, code: 'VALIDATION_FAILED', message: 'Request validation failed',
      violations: [{ field: 'email', message: 'must be a well-formed email address' }],
    }));
    const err = (await api('/api/v1/members', { method: 'POST', body: {}, auth: false }).catch((e: unknown) => e)) as ApiError;
    expect(err).toBeInstanceOf(ApiError);
    expect(err.code).toBe('VALIDATION_FAILED');
    expect(err.details).toContain('email: must be a well-formed email address');
  });

  it('turns a declined payment (402 with a payment body) into a readable error', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(json(402, { id: 9, status: 'FAILED', failureReason: 'card_declined' }));
    const err = (await api('/api/v1/payments', { method: 'POST', body: {}, auth: false }).catch((e: unknown) => e)) as ApiError;
    expect(err.code).toBe('PAYMENT_DECLINED');
    expect(err.message).toContain('card_declined');
  });
});
