import type { ApiErrorBody, TokenResponse, UserInfo } from './types';

/**
 * Thin fetch wrapper around the GymOps API.
 *
 * - The access token (15 min) lives only in memory.
 * - The refresh token is kept in localStorage so a page reload can restore the session. The backend
 *   rotates it on every use and revokes the whole session if an old one is replayed. Therefore:
 *   (1) refreshes are single-flight per tab, and (2) tabs share the latest token via the `storage`
 *   event, so two tabs never present the same token twice.
 *   Production hardening: move the refresh token to an httpOnly SameSite cookie via a BFF.
 * - A 401 triggers one transparent refresh + retry; if that fails the app is logged out.
 */

const REFRESH_KEY = 'gymops.refreshToken';

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly violations: ApiErrorBody['violations'];
  readonly correlationId?: string;

  constructor(status: number, body: Partial<ApiErrorBody> | null) {
    super(body?.message ?? `Request failed (${status})`);
    this.status = status;
    this.code = body?.code ?? (status === 429 ? 'RATE_LIMITED' : 'HTTP_' + status);
    this.violations = body?.violations;
    this.correlationId = body?.correlationId;
  }

  /** Human readable message including field violations. */
  get details(): string {
    if (!this.violations?.length) return this.message;
    return this.violations.map((v) => `${v.field}: ${v.message}`).join('\n');
  }
}

type SessionListener = (user: UserInfo | null) => void;

let accessToken: string | null = null;
let currentUser: UserInfo | null = null;
let refreshInFlight: Promise<boolean> | null = null;
const listeners = new Set<SessionListener>();

function notify() {
  listeners.forEach((l) => l(currentUser));
}

export function onSessionChange(listener: SessionListener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function getCurrentUser(): UserInfo | null {
  return currentUser;
}

function readRefreshToken(): string | null {
  try {
    return localStorage.getItem(REFRESH_KEY);
  } catch {
    return null;
  }
}

function writeRefreshToken(token: string | null) {
  try {
    if (token) localStorage.setItem(REFRESH_KEY, token);
    else localStorage.removeItem(REFRESH_KEY);
  } catch {
    /* storage unavailable (private mode): session lasts for the tab only */
  }
}

export function applyTokens(tokens: TokenResponse) {
  accessToken = tokens.accessToken;
  currentUser = tokens.user;
  writeRefreshToken(tokens.refreshToken);
  notify();
}

export function clearSession() {
  accessToken = null;
  currentUser = null;
  writeRefreshToken(null);
  notify();
}

// Another tab rotated or cleared the refresh token: drop our access token so the next call
// refreshes with the new one (or we follow the logout).
if (typeof window !== 'undefined') {
  window.addEventListener('storage', (e) => {
    if (e.key !== REFRESH_KEY) return;
    if (!e.newValue) {
      accessToken = null;
      currentUser = null;
      notify();
    }
  });
}

/** Exchanges the stored refresh token for a fresh session. Single-flight. */
export function refreshSession(): Promise<boolean> {
  if (refreshInFlight) return refreshInFlight;
  const token = readRefreshToken();
  if (!token) return Promise.resolve(false);
  refreshInFlight = (async () => {
    try {
      const res = await fetch('/api/v1/auth/refresh', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: token }),
      });
      if (!res.ok) {
        // another tab may have rotated it in the meantime: retry once with the newer token
        const latest = readRefreshToken();
        if (latest && latest !== token) {
          refreshInFlight = null;
          return refreshSession();
        }
        clearSession();
        return false;
      }
      applyTokens((await res.json()) as TokenResponse);
      return true;
    } catch {
      return false;
    } finally {
      refreshInFlight = null;
    }
  })();
  return refreshInFlight;
}

export interface RequestOptions {
  method?: string;
  body?: unknown;
  headers?: Record<string, string>;
  query?: Record<string, string | number | boolean | null | undefined>;
  /** Send the bearer token (default true). */
  auth?: boolean;
}

export function buildUrl(path: string, query?: RequestOptions['query']): string {
  if (!query) return path;
  const params = new URLSearchParams();
  Object.entries(query).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') params.set(k, String(v));
  });
  const qs = params.toString();
  return qs ? `${path}?${qs}` : path;
}

async function send(path: string, opts: RequestOptions): Promise<Response> {
  const headers: Record<string, string> = { Accept: 'application/json', ...opts.headers };
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
  if (opts.auth !== false && accessToken) headers.Authorization = `Bearer ${accessToken}`;
  return fetch(buildUrl(path, opts.query), {
    method: opts.method ?? 'GET',
    headers,
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
  });
}

async function parse<T>(res: Response): Promise<T> {
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/** Performs an API call; throws {@link ApiError} for non-2xx responses (except 402, see below). */
export async function api<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  let res = await send(path, opts);
  if (res.status === 401 && opts.auth !== false) {
    if (await refreshSession()) {
      res = await send(path, opts);
    } else {
      clearSession();
    }
  }
  // 402 = payment declined: the body is still a (FAILED) payment resource, surface it as an error
  if (!res.ok) {
    let body: Partial<ApiErrorBody> | null = null;
    try {
      body = await parse<Partial<ApiErrorBody>>(res);
    } catch {
      body = null;
    }
    if (res.status === 402 && body && 'failureReason' in body) {
      throw new ApiError(402, {
        code: 'PAYMENT_DECLINED',
        message: `Payment declined: ${(body as { failureReason?: string }).failureReason ?? 'unknown reason'}`,
      });
    }
    throw new ApiError(res.status, body);
  }
  return parse<T>(res);
}

/**
 * Downloads a binary resource (e.g. a PDF) with the bearer token, refreshing the session once on 401.
 * Returns the blob and the file name from Content-Disposition (if any).
 */
export async function apiBlob(path: string): Promise<{ blob: Blob; filename: string | null }> {
  const opts: RequestOptions = { headers: { Accept: 'application/pdf, application/json' } };
  let res = await send(path, opts);
  if (res.status === 401) {
    if (await refreshSession()) res = await send(path, opts);
    else clearSession();
  }
  if (!res.ok) {
    let body: Partial<ApiErrorBody> | null = null;
    try {
      body = await parse<Partial<ApiErrorBody>>(res);
    } catch {
      body = null;
    }
    throw new ApiError(res.status, body);
  }
  const disposition = res.headers.get('Content-Disposition') ?? '';
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition);
  return { blob: await res.blob(), filename: match ? decodeURIComponent(match[1]) : null };
}

/** Client-generated idempotency key for payment / refund requests. */
export function newIdempotencyKey(prefix = 'web'): string {
  const rnd = typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  return `${prefix}-${rnd}`;
}
