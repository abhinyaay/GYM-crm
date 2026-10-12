import { useMutation, useQuery, useQueryClient, type QueryKey, type UseQueryOptions } from '@tanstack/react-query';
import { api, type RequestOptions } from '@/api/client';
import { notifyError, notifySuccess } from './notify';

/**
 * GET helper. The query key is derived from path + query so identical requests are shared/cached.
 * Example: useApiQuery<Page<MemberListItem>>('/api/v1/members', { q, page })
 */
export function useApiQuery<T>(
  path: string | null,
  query?: RequestOptions['query'],
  options?: Omit<UseQueryOptions<T, Error, T, QueryKey>, 'queryKey' | 'queryFn'>,
) {
  return useQuery<T, Error, T, QueryKey>({
    queryKey: [path, query ?? {}],
    queryFn: () => api<T>(path as string, { query }),
    enabled: path !== null && (options?.enabled ?? true),
    ...options,
  });
}

interface MutationConfig<TVars, TResult> {
  /** Builds the request from the variables. */
  request: (vars: TVars) => { path: string } & RequestOptions;
  /** Path prefixes whose cached queries are invalidated on success, e.g. ['/api/v1/members']. */
  invalidate?: string[];
  successMessage?: string | ((result: TResult) => string);
  onSuccess?: (result: TResult, vars: TVars) => void;
  /** Suppress the default error toast (caller shows the error inline). */
  silentError?: boolean;
}

export function useApiMutation<TVars = void, TResult = unknown>(config: MutationConfig<TVars, TResult>) {
  const client = useQueryClient();
  return useMutation<TResult, Error, TVars>({
    mutationFn: (vars) => {
      const { path, ...opts } = config.request(vars);
      return api<TResult>(path, { method: 'POST', ...opts });
    },
    onSuccess: (result, vars) => {
      config.invalidate?.forEach((prefix) =>
        client.invalidateQueries({
          predicate: (q) => typeof q.queryKey[0] === 'string' && (q.queryKey[0] as string).startsWith(prefix),
        }),
      );
      if (config.successMessage) {
        notifySuccess(typeof config.successMessage === 'function' ? config.successMessage(result) : config.successMessage);
      }
      config.onSuccess?.(result, vars);
    },
    onError: (err) => {
      if (!config.silentError) notifyError(err);
    },
  });
}
