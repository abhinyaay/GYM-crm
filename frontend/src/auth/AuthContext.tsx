import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api, applyTokens, clearSession, getCurrentUser, onSessionChange, refreshSession } from '@/api/client';
import type { TokenResponse, UserInfo } from '@/api/types';

export interface RegisterInput {
  email: string;
  password: string;
  firstName: string;
  lastName: string;
  phone: string;
  homeBranchId: number;
  dateOfBirth?: string | null;
}

interface AuthState {
  user: UserInfo | null;
  /** true until the initial session restore (refresh) has completed */
  initializing: boolean;
  login: (email: string, password: string) => Promise<UserInfo>;
  register: (input: RegisterInput) => Promise<UserInfo>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

// Module-level so React StrictMode's double effect invocation cannot refresh twice
// (a second refresh with the rotated token would trigger the backend's reuse detection).
let restorePromise: Promise<boolean> | null = null;

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserInfo | null>(getCurrentUser());
  const [initializing, setInitializing] = useState(true);

  useEffect(() => onSessionChange(setUser), []);

  useEffect(() => {
    restorePromise ??= refreshSession();
    restorePromise.finally(() => setInitializing(false));
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    const tokens = await api<TokenResponse>('/api/v1/auth/login', {
      method: 'POST', body: { email, password }, auth: false,
    });
    applyTokens(tokens);
    return tokens.user;
  }, []);

  const register = useCallback(async (input: RegisterInput) => {
    const tokens = await api<TokenResponse>('/api/v1/auth/register', { method: 'POST', body: input, auth: false });
    applyTokens(tokens);
    return tokens.user;
  }, []);

  const logout = useCallback(async () => {
    const token = localStorage.getItem('gymops.refreshToken');
    clearSession();
    if (token) {
      await api('/api/v1/auth/logout', { method: 'POST', body: { refreshToken: token }, auth: false }).catch(() => undefined);
    }
  }, []);

  const value = useMemo(() => ({ user, initializing, login, register, logout }), [user, initializing, login, register, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}
