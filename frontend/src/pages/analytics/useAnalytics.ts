import { keepPreviousData } from '@tanstack/react-query';
import type { RequestOptions } from '@/api/client';
import { useApiQuery } from '@/lib/useApi';
import type { AnalyticsFilters } from './types';

/** Query params shared by the analytics endpoints. */
export function filterQuery(f: AnalyticsFilters, withGranularity = false): NonNullable<RequestOptions['query']> {
  return {
    branchId: f.branchId ?? undefined,
    from: f.from,
    to: f.to,
    granularity: withGranularity ? f.granularity : undefined,
  };
}

/**
 * GET against the analytics service. Keeps the previous result on screen while a new filter
 * combination loads, so the dashboard does not flash a spinner on every change.
 * Only mounted tabs call this: Nginx allows 5 req/s per IP on /analytics.
 */
export function useAnalytics<T>(path: string, query: RequestOptions['query']) {
  return useApiQuery<T>(path, query, { placeholderData: keepPreviousData });
}
