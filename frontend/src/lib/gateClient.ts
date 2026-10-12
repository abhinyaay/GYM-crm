import { ApiError } from '@/api/client';
import type { ApiErrorBody } from '@/api/types';

/**
 * Browser implementation of the gate device protocol (the same one real gate firmware / a vendor bridge uses):
 *
 *   X-Signature = hex(HMAC-SHA256(deviceSecret, METHOD|PATH|X-Timestamp|X-Nonce|hex(SHA-256(body))))
 *
 * Responses are signed back: X-Signature = hex(HMAC(deviceSecret, "response|" + nonce + "|" + hex(SHA-256(body)))).
 * The secret never leaves the browser tab (kept in sessionStorage by the simulator page) and no JWT is sent:
 * the device API authenticates the device, not the staff member operating the simulator.
 * WebCrypto needs a secure context (https or http://localhost).
 */

const encoder = new TextEncoder();

function hex(buffer: ArrayBuffer): string {
  return Array.from(new Uint8Array(buffer)).map((b) => b.toString(16).padStart(2, '0')).join('');
}

function subtle(): SubtleCrypto {
  if (typeof crypto === 'undefined' || !crypto.subtle) {
    throw new Error('WebCrypto is unavailable: open the app over https or http://localhost');
  }
  return crypto.subtle;
}

export async function sha256Hex(text: string): Promise<string> {
  return hex(await subtle().digest('SHA-256', encoder.encode(text)));
}

export async function hmacHex(secret: string, message: string): Promise<string> {
  const key = await subtle().importKey('raw', encoder.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return hex(await subtle().sign('HMAC', key, encoder.encode(message)));
}

function randomNonce(): string {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  return Array.from(bytes).map((b) => b.toString(16).padStart(2, '0')).join('');
}

export interface GateIdentity {
  deviceUid: string;
  secret: string;
}

export interface GateResponse<T> {
  status: number;
  data: T;
  /** Whether the response carried a valid server signature for this request's nonce. */
  signatureValid: boolean;
  durationMs: number;
}

/** Sends one signed device request; throws {@link ApiError} for non-2xx responses (401 bad signature, 403 disabled ...). */
export async function gateRequest<T>(gate: GateIdentity, method: 'GET' | 'POST', path: string, body?: unknown,
  clockOffsetSeconds = 0): Promise<GateResponse<T>> {
  const json = body === undefined ? '' : JSON.stringify(body);
  const timestamp = String(Math.floor(Date.now() / 1000) + clockOffsetSeconds);
  const nonce = randomNonce();
  const signature = await hmacHex(gate.secret, `${method}|${path}|${timestamp}|${nonce}|${await sha256Hex(json)}`);
  const started = performance.now();
  const res = await fetch(path, {
    method,
    headers: {
      Accept: 'application/json',
      ...(json ? { 'Content-Type': 'application/json' } : {}),
      'X-Device-Id': gate.deviceUid,
      'X-Timestamp': timestamp,
      'X-Nonce': nonce,
      'X-Signature': signature,
    },
    body: json || undefined,
  });
  const text = await res.text();
  const durationMs = Math.round(performance.now() - started);
  let parsed: unknown = null;
  try {
    parsed = text ? JSON.parse(text) : null;
  } catch {
    parsed = null;
  }
  if (!res.ok) {
    throw new ApiError(res.status, parsed as Partial<ApiErrorBody> | null);
  }
  const responseSignature = res.headers.get('X-Signature');
  const expected = await hmacHex(gate.secret, `response|${nonce}|${await sha256Hex(text)}`);
  return { status: res.status, data: parsed as T, signatureValid: responseSignature === expected, durationMs };
}

/** Same hashing the server uses for card UIDs ("04:a1-b2" -> "04A1B2"), so the simulator can check its allowlist. */
export function normalizeCardUid(raw: string): string {
  return raw.replace(/[\s:-]/g, '').toUpperCase();
}

export const cardHash = (uid: string) => sha256Hex(`RFID_CARD:${normalizeCardUid(uid)}`);
export const templateHash = (type: 'FINGERPRINT' | 'FACE', ref: string) => sha256Hex(`TEMPLATE:${type}:${ref.trim()}`);
