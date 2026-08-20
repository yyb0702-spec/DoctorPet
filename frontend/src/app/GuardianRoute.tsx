// 보호자 전용 라우트 가드 — 미인증은 로그인으로, role이 GUARDIAN이 아니면 홈으로 보낸다.
// 실제 권한 강제는 백엔드 SecurityConfig(찜 API → hasRole("GUARDIAN"))가 하므로 이건 화면 노출만 막는다.
// StaffRoute와 대칭이며, 스태프가 URL로 직접 들어와 403 화면을 보는 것을 방지한다(PR #193 리뷰 P2).
import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuthStore } from '@/lib/auth/authStore'
import { useMe } from '@/features/members/hooks'
import { PageLoader } from '@/components/common/States'
import { MemberRole } from '@/types/enums'

export function GuardianRoute() {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  const location = useLocation()
  const me = useMe()

  if (!isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location }} />
  }
  if (me.isLoading) {
    return <PageLoader />
  }
  if (me.data?.role !== MemberRole.GUARDIAN) {
    return <Navigate to="/" replace />
  }
  return <Outlet />
}
