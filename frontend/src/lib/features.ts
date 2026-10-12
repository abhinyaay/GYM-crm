import { useAuth } from '@/auth/AuthContext';
import { useApiQuery } from './useApi';

/** Optional modules the owner can switch on or off (Settings page). */
export interface Features {
  gateAccess: boolean;
  biometrics: boolean;
}

export type FeatureKey = keyof Features;

/** Current switches; while loading (or signed out) everything counts as on, so nothing flickers away. */
export function useFeatures(): Features {
  const { user } = useAuth();
  const { data } = useApiQuery<Features>(user ? '/api/v1/settings/features' : null, undefined, { staleTime: 60_000 });
  return data ?? { gateAccess: true, biometrics: true };
}
