// 병원 스태프 라우트 가드 — 미인증은 로그인으로, role이 HOSPITAL_STAFF가 아니면 보호자 홈으로 보낸다.
// 실제 권한 강제는 백엔드 SecurityConfig(/api/hospital/** → ROLE_HOSPITAL_STAFF)가 하므로
// 이건 화면 노출만 막는다.
import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuthStore } from '@/lib/auth/authStore'
import { useMe } from '@/features/members/hooks'
import { PageLoader } from '@/components/common/States'
import { MemberRole } from '@/types/enums'

export function StaffRoute() {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  const location = useLocation()
  const me = useMe()

  if (!isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location }} />
  }
  if (me.isLoading) {
    return <PageLoader />
  }
  if (me.data?.role !== MemberRole.HOSPITAL_STAFF) {
    return <Navigate to="/" replace />
  }
  return <Outlet />
}
