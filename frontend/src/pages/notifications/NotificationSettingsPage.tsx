import { Alert } from '@mantine/core';
import { useAuth } from '@/auth/AuthContext';
import { PageHeader } from '@/components/PageHeader';
import { PreferenceMatrix } from '@/components/notifications/PreferenceMatrix';

/** Member self-service: which notifications arrive by e-mail and by SMS. */
export default function NotificationSettingsPage() {
  const { user } = useAuth();
  return (
    <>
      <PageHeader title="Notifications" subtitle="Choose how we reach you. Changes apply immediately." />
      {user?.memberId
        ? <PreferenceMatrix memberId={user.memberId} />
        : <Alert color="yellow">Your login is not linked to a member profile yet.</Alert>}
    </>
  );
}
