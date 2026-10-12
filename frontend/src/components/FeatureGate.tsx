import { Alert, Anchor } from '@mantine/core';
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '@/auth/AuthContext';
import { hasAnyRole, ORG_WIDE } from '@/auth/roles';
import { type FeatureKey, useFeatures } from '@/lib/features';
import { PageHeader } from './PageHeader';

const NAMES: Record<FeatureKey, string> = { gateAccess: 'Gate access control', biometrics: 'Fingerprint & face' };

/** Renders the page only while the optional module is switched on; otherwise explains where to switch it on. */
export function FeatureGate({ feature, children }: { feature: FeatureKey; children: ReactNode }) {
  const features = useFeatures();
  const { user } = useAuth();
  if (features[feature]) return <>{children}</>;
  return (
    <>
      <PageHeader title={NAMES[feature]} subtitle="This optional module is switched off" />
      <Alert variant="light" color="gray">
        {hasAnyRole(user, ORG_WIDE)
          ? <>Switch it on in <Anchor component={Link} to="/settings">Settings</Anchor>.</>
          : 'Ask your gym admin to switch it on in Settings.'}
      </Alert>
    </>
  );
}
