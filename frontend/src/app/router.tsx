// 라우트 정의. 공개(메인/로그인/회원가입/병원검색·상세)와 보호(예약/펫/결제수단) 분리.
import { createBrowserRouter } from 'react-router-dom'
import { AppLayout } from '@/components/common/AppLayout'
import { StaffLayout } from '@/components/common/StaffLayout'
import { ProtectedRoute } from '@/app/ProtectedRoute'
import { StaffRoute } from '@/app/StaffRoute'
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
import { StaffDashboardPage } from '@/pages/StaffDashboardPage'
import { StaffReservationQueuePage } from '@/pages/StaffReservationQueuePage'
import { StaffReservationDetailPage } from '@/pages/StaffReservationDetailPage'
import { StaffPaymentsPage } from '@/pages/StaffPaymentsPage'
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
          { path: '/staff/payments', element: <StaffPaymentsPage /> },
          { path: '/staff/slots', element: <StaffSlotsPage /> },
        ],
      },
    ],
  },
])
