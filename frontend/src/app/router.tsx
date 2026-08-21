// 라우트 정의. 공개(메인/로그인/회원가입/병원검색·상세)와 보호(예약/펫/결제수단) 분리.
import { createBrowserRouter } from 'react-router-dom'
import { AppLayout } from '@/components/common/AppLayout'
import { StaffLayout } from '@/components/common/StaffLayout'
import { ProtectedRoute } from '@/app/ProtectedRoute'
import { StaffRoute } from '@/app/StaffRoute'
import { HOSPITAL_OPS_BACKEND_READY } from '@/app/featureFlags'
import { HomePage } from '@/pages/HomePage'
import { LoginPage } from '@/pages/LoginPage'
import { SignupPage } from '@/pages/SignupPage'
import { PasswordResetRequestPage } from '@/pages/PasswordResetRequestPage'
import { PasswordResetConfirmPage } from '@/pages/PasswordResetConfirmPage'
import { VerifyEmailPage } from '@/pages/VerifyEmailPage'
import { HospitalSearchPage } from '@/pages/HospitalSearchPage'
import { AiConsultationPage } from '@/pages/AiConsultationPage'
import { HospitalDetailPage } from '@/pages/HospitalDetailPage'
import { PetsPage } from '@/pages/PetsPage'
import { MyPage } from '@/pages/MyPage'
import { PaymentMethodsPage } from '@/pages/PaymentMethodsPage'
import { ReservationsPage } from '@/pages/ReservationsPage'
import { ReservationDetailPage } from '@/pages/ReservationDetailPage'
import { WaitlistsPage } from '@/pages/WaitlistsPage'
import { FavoriteHospitalsPage } from '@/pages/FavoriteHospitalsPage'
import { GuardianRoute } from '@/app/GuardianRoute'
import { StaffDashboardPage } from '@/pages/StaffDashboardPage'
import { StaffReservationQueuePage } from '@/pages/StaffReservationQueuePage'
import { StaffReservationDetailPage } from '@/pages/StaffReservationDetailPage'
import { StaffPaymentsPage } from '@/pages/StaffPaymentsPage'
import { StaffOperatingHoursPage } from '@/pages/StaffOperatingHoursPage'
import { StaffCapabilitiesPage } from '@/pages/StaffCapabilitiesPage'
import { StaffTemporaryClosuresPage } from '@/pages/StaffTemporaryClosuresPage'
import { StaffSlotsPage } from '@/pages/StaffSlotsPage'
import { NotFoundPage } from '@/pages/NotFoundPage'

export const router = createBrowserRouter([
  {
    element: <AppLayout />,
    children: [
      // 공개 라우트
      { path: '/', element: <HomePage /> },
      { path: '/login', element: <LoginPage /> },
      { path: '/signup', element: <SignupPage /> },
      {
        path: '/password-reset/request',
        element: <PasswordResetRequestPage />,
      },
      {
        path: '/password-reset/confirm',
        element: <PasswordResetConfirmPage />,
      },
      { path: '/verify-email', element: <VerifyEmailPage /> },
      { path: '/hospitals', element: <HospitalSearchPage /> },
      { path: '/hospitals/:hospitalId', element: <HospitalDetailPage /> },
      { path: '/ai', element: <AiConsultationPage /> },
      // 보호 라우트(인증 필요)
      {
        element: <ProtectedRoute />,
        children: [
          { path: '/mypage', element: <MyPage /> },
          { path: '/pets', element: <PetsPage /> },
          { path: '/payment-methods', element: <PaymentMethodsPage /> },
          { path: '/reservations', element: <ReservationsPage /> },
          {
            path: '/reservations/:reservationId',
            element: <ReservationDetailPage />,
          },
          { path: '/waitlists', element: <WaitlistsPage /> },
          // 찜은 보호자 전용 API라 역할 가드를 한 겹 더 둔다(스태프가 URL로 들어와도 403을 보지 않게).
          {
            element: <GuardianRoute />,
            children: [
              { path: '/favorites', element: <FavoriteHospitalsPage /> },
            ],
          },
        ],
      },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
  // 병원 스태프 전용 — 보호자 AppLayout과 분리된 사이드바 레이아웃.
  {
    element: <StaffRoute />,
    children: [
      {
        element: <StaffLayout />,
        children: [
          { path: '/staff', element: <StaffDashboardPage /> },
          {
            path: '/staff/reservations',
            element: <StaffReservationQueuePage />,
          },
          {
            path: '/staff/reservations/:reservationId',
            element: <StaffReservationDetailPage />,
          },
          // 진료시간·진료역량·임시휴진은 백엔드 API가 develop에 있으므로 플래그와 무관하게 항상 등록한다.
          {
            path: '/staff/operating-hours',
            element: <StaffOperatingHoursPage />,
          },
          { path: '/staff/capabilities', element: <StaffCapabilitiesPage /> },
          {
            path: '/staff/temporary-closures',
            element: <StaffTemporaryClosuresPage />,
          },
          // 결제 관리·슬롯 관리는 백엔드 미착수 — 플래그가 켜질 때만 라우트를 등록해
          // URL 직접 접근으로도 미구현 API 404에 도달하지 못하게 막는다(PR #127 리뷰).
          ...(HOSPITAL_OPS_BACKEND_READY
            ? [
                { path: '/staff/payments', element: <StaffPaymentsPage /> },
                { path: '/staff/slots', element: <StaffSlotsPage /> },
              ]
            : []),
        ],
      },
    ],
  },
])
