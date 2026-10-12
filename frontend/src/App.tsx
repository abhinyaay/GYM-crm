import { Center, Loader } from '@mantine/core';
import { lazy, Suspense, type ReactNode } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import type { RoleName } from './api/types';
import { useAuth } from './auth/AuthContext';
import { RequireAuth } from './auth/RequireAuth';
import { FRONT_DESK, homePath, MANAGERS, ORG_WIDE } from './auth/roles';
import { FeatureGate } from './components/FeatureGate';
import { AppLayout } from './components/AppLayout';

const LoginPage = lazy(() => import('./pages/auth/LoginPage'));
const RegisterPage = lazy(() => import('./pages/auth/RegisterPage'));
const ProfilePage = lazy(() => import('./pages/auth/ProfilePage'));
// staff - core
const DashboardPage = lazy(() => import('./pages/dashboard/DashboardPage'));
const FrontDeskPage = lazy(() => import('./pages/frontdesk/FrontDeskPage'));
const MembersPage = lazy(() => import('./pages/members/MembersPage'));
const MemberDetailPage = lazy(() => import('./pages/members/MemberDetailPage'));
// staff - administration
const BillingPage = lazy(() => import('./pages/billing/BillingPage'));
const PlansPage = lazy(() => import('./pages/plans/PlansPage'));
const TrainersPage = lazy(() => import('./pages/trainers/TrainersPage'));
const TrainerDetailPage = lazy(() => import('./pages/trainers/TrainerDetailPage'));
const WorkoutsPage = lazy(() => import('./pages/workouts/WorkoutsPage'));
const BranchesPage = lazy(() => import('./pages/branches/BranchesPage'));
const BranchDetailPage = lazy(() => import('./pages/branches/BranchDetailPage'));
const UsersPage = lazy(() => import('./pages/users/UsersPage'));
// staff - insight & support
const AnalyticsPage = lazy(() => import('./pages/analytics/AnalyticsPage'));
const ComplaintsPage = lazy(() => import('./pages/complaints/ComplaintsPage'));
const ComplaintDetailPage = lazy(() => import('./pages/complaints/ComplaintDetailPage'));
// trainer portal
const TrainerHomePage = lazy(() => import('./pages/trainer/TrainerHomePage'));
const TrainerClientPage = lazy(() => import('./pages/trainer/TrainerClientPage'));
const TrainerSchedulePage = lazy(() => import('./pages/trainer/TrainerSchedulePage'));
// member portal
const MemberHomePage = lazy(() => import('./pages/member/MemberHomePage'));
const MemberMembershipPage = lazy(() => import('./pages/member/MemberMembershipPage'));
const MemberPaymentsPage = lazy(() => import('./pages/member/MemberPaymentsPage'));
const MemberWorkoutPage = lazy(() => import('./pages/member/MemberWorkoutPage'));
const MemberComplaintsPage = lazy(() => import('./pages/member/MemberComplaintsPage'));
const MemberPaymentMethodsPage = lazy(() => import('./pages/member/MemberPaymentMethodsPage'));
// group classes
const ClassesPage = lazy(() => import('./pages/classes/ClassesPage'));
const ClassSessionPage = lazy(() => import('./pages/classes/ClassSessionPage'));
const MemberClassesPage = lazy(() => import('./pages/member/MemberClassesPage'));
// leads & trials
const LeadsPage = lazy(() => import('./pages/leads/LeadsPage'));
const LeadDetailPage = lazy(() => import('./pages/leads/LeadDetailPage'));
const EnquiryPage = lazy(() => import('./pages/leads/EnquiryPage'));
// notifications
const NotificationSettingsPage = lazy(() => import('./pages/notifications/NotificationSettingsPage'));
const MessagingCenterPage = lazy(() => import('./pages/notifications/MessagingCenterPage'));
const UnsubscribePage = lazy(() => import('./pages/notifications/UnsubscribePage'));
const GuidePage = lazy(() => import('./pages/guide/GuidePage'));
const SettingsPage = lazy(() => import('./pages/settings/SettingsPage'));
// access control
const AccessControlPage = lazy(() => import('./pages/access/AccessControlPage'));
const GateSimulatorPage = lazy(() => import('./pages/access/GateSimulatorPage'));
const MemberEntryPassPage = lazy(() => import('./pages/member/MemberEntryPassPage'));
// growth
const CampaignsPage = lazy(() => import('./pages/growth/CampaignsPage'));
const ReferralsPage = lazy(() => import('./pages/growth/ReferralsPage'));
const MemberReferralsPage = lazy(() => import('./pages/member/MemberReferralsPage'));

const TRAINER_OR_MANAGER: RoleName[] = [...MANAGERS, 'TRAINER'];

function Guard({ roles, children }: { roles?: RoleName[]; children: ReactNode }) {
  return <RequireAuth roles={roles}>{children}</RequireAuth>;
}

function Home() {
  const { user, initializing } = useAuth();
  if (initializing) return null;
  return <Navigate to={homePath(user)} replace />;
}

export function App() {
  return (
    <Suspense fallback={<Center h="60vh"><Loader /></Center>}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/enquire" element={<EnquiryPage />} />
        <Route path="/unsubscribe" element={<UnsubscribePage />} />
        <Route path="/guide" element={<GuidePage />} />
        <Route element={<Guard><AppLayout /></Guard>}>
          <Route index element={<Home />} />
          <Route path="profile" element={<ProfilePage />} />

          <Route path="dashboard" element={<Guard roles={MANAGERS}><DashboardPage /></Guard>} />
          <Route path="front-desk" element={<Guard roles={FRONT_DESK}><FrontDeskPage /></Guard>} />
          <Route path="members" element={<Guard roles={FRONT_DESK}><MembersPage /></Guard>} />
          <Route path="members/:id" element={<Guard roles={[...FRONT_DESK, 'TRAINER']}><MemberDetailPage /></Guard>} />
          <Route path="billing" element={<Guard roles={FRONT_DESK}><BillingPage /></Guard>} />
          <Route path="plans" element={<Guard roles={FRONT_DESK}><PlansPage /></Guard>} />
          <Route path="trainers" element={<Guard roles={MANAGERS}><TrainersPage /></Guard>} />
          <Route path="trainers/:id" element={<Guard roles={MANAGERS}><TrainerDetailPage /></Guard>} />
          <Route path="workouts" element={<Guard roles={TRAINER_OR_MANAGER}><WorkoutsPage /></Guard>} />
          <Route path="branches" element={<Guard roles={MANAGERS}><BranchesPage /></Guard>} />
          <Route path="branches/:id" element={<Guard roles={MANAGERS}><BranchDetailPage /></Guard>} />
          <Route path="users" element={<Guard roles={ORG_WIDE}><UsersPage /></Guard>} />
          {/* not "/analytics": that path prefix belongs to the analytics API behind the gateway */}
          <Route path="insights" element={<Guard roles={MANAGERS}><AnalyticsPage /></Guard>} />
          <Route path="complaints" element={<Guard roles={FRONT_DESK}><ComplaintsPage /></Guard>} />
          <Route path="complaints/:id" element={<Guard roles={[...FRONT_DESK, 'MEMBER']}><ComplaintDetailPage /></Guard>} />

          <Route path="trainer" element={<Guard roles={['TRAINER']}><TrainerHomePage /></Guard>} />
          <Route path="trainer/schedule" element={<Guard roles={['TRAINER']}><TrainerSchedulePage /></Guard>} />
          <Route path="trainer/clients/:memberId" element={<Guard roles={TRAINER_OR_MANAGER}><TrainerClientPage /></Guard>} />

          <Route path="me" element={<Guard roles={['MEMBER']}><MemberHomePage /></Guard>} />
          <Route path="me/membership" element={<Guard roles={['MEMBER']}><MemberMembershipPage /></Guard>} />
          <Route path="me/payments" element={<Guard roles={['MEMBER']}><MemberPaymentsPage /></Guard>} />
          <Route path="me/workout" element={<Guard roles={['MEMBER']}><MemberWorkoutPage /></Guard>} />
          <Route path="me/complaints" element={<Guard roles={['MEMBER']}><MemberComplaintsPage /></Guard>} />
          <Route path="me/payment-methods" element={<Guard roles={['MEMBER']}><MemberPaymentMethodsPage /></Guard>} />
          <Route path="me/classes" element={<Guard roles={['MEMBER']}><MemberClassesPage /></Guard>} />
          <Route path="classes" element={<Guard roles={[...FRONT_DESK, 'TRAINER']}><ClassesPage /></Guard>} />
          <Route path="classes/:id" element={<Guard roles={[...FRONT_DESK, 'TRAINER']}><ClassSessionPage /></Guard>} />
          <Route path="leads" element={<Guard roles={FRONT_DESK}><LeadsPage /></Guard>} />
          <Route path="leads/:id" element={<Guard roles={FRONT_DESK}><LeadDetailPage /></Guard>} />
          <Route path="messaging" element={<Guard roles={MANAGERS}><MessagingCenterPage /></Guard>} />
          <Route path="settings" element={<Guard roles={ORG_WIDE}><SettingsPage /></Guard>} />
          <Route path="me/notifications" element={<Guard roles={['MEMBER']}><NotificationSettingsPage /></Guard>} />
          <Route path="access" element={<Guard roles={FRONT_DESK}><FeatureGate feature="gateAccess"><AccessControlPage /></FeatureGate></Guard>} />
          <Route path="access/gate" element={<Guard roles={FRONT_DESK}><FeatureGate feature="gateAccess"><GateSimulatorPage /></FeatureGate></Guard>} />
          <Route path="me/pass" element={<Guard roles={['MEMBER']}><MemberEntryPassPage /></Guard>} />
          <Route path="campaigns" element={<Guard roles={MANAGERS}><CampaignsPage /></Guard>} />
          <Route path="referrals" element={<Guard roles={MANAGERS}><ReferralsPage /></Guard>} />
          <Route path="me/referrals" element={<Guard roles={['MEMBER']}><MemberReferralsPage /></Guard>} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Routes>
    </Suspense>
  );
}
