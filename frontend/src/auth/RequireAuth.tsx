import { Center, Loader } from '@mantine/core';
import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import type { RoleName } from '@/api/types';
import { useAuth } from './AuthContext';
import { hasAnyRole, homePath } from './roles';

/** Route guard: requires a session and (optionally) one of the given roles. */
export function RequireAuth({ roles, children }: { roles?: RoleName[]; children: ReactNode }) {
  const { user, initializing } = useAuth();
  const location = useLocation();
  if (initializing) {
    return (
      <Center h="100vh">
        <Loader />
      </Center>
    );
  }
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  if (roles && !hasAnyRole(user, roles)) return <Navigate to={homePath(user)} replace />;
  return <>{children}</>;
}
