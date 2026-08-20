// 보호자 앱 공통 레이아웃 — 헤더(네비·인증 상태) + 본문.
import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom'
import { PawPrint } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { useAuthStore } from '@/lib/auth/authStore'
import { useMe } from '@/features/members/hooks'
import { MemberRole } from '@/types/enums'
import { NotificationBell } from '@/features/notifications/NotificationBell'
import { UserMenu } from '@/components/common/UserMenu'

// 공개 라우트 — 비인증 상태에서도 진입 가능.
const PUBLIC_NAV = [
  { to: '/hospitals', label: '병원 찾기' },
  { to: '/ai', label: 'AI 상담' },
]
// 인증 필요 — 로그인 상태에서만 노출. (펫·결제수단은 마이페이지 허브에서 접근)
const PRIVATE_NAV = [
  { to: '/reservations', label: '내 예약' },
  { to: '/waitlists', label: '내 대기열' },
]
// 보호자 전용 — 찜 API가 hasRole("GUARDIAN")이라 스태프에게 보여주면 누르는 순간 403이다.
// 스태프로 확인됐을 때만 감춘다 — 역할을 모르는 동안(조회 실패·재시도) 메뉴를 지우면 정상
// 보호자가 진입 경로를 잃는다(자기검토). 라우트·버튼도 같은 기준을 쓴다.
const GUARDIAN_NAV = [{ to: '/favorites', label: '관심 병원' }]

export function AppLayout() {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  const navigate = useNavigate()
  const me = useMe()
  const knownNonGuardian = me.data != null && me.data.role !== MemberRole.GUARDIAN

  return (
    <div className="min-h-svh bg-background">
      <header className="sticky top-0 z-30 border-b bg-background/80 backdrop-blur">
        <div className="mx-auto flex h-14 max-w-5xl items-center gap-4 px-4">
          <Link to="/" className="flex items-center gap-2 font-bold text-primary">
            <PawPrint className="h-5 w-5" />
            DoctorPet
          </Link>
          <nav className="hidden items-center gap-1 sm:flex">
            {[
              ...PUBLIC_NAV,
              ...(isAuthenticated ? PRIVATE_NAV : []),
              ...(isAuthenticated && !knownNonGuardian ? GUARDIAN_NAV : []),
            ].map(
              (item) => (
                <NavLink
                  key={item.to}
                  to={item.to}
                  className={({ isActive }) =>
                    cn(
                      'rounded-md px-3 py-1.5 text-sm font-medium transition-colors',
                      isActive
                        ? 'bg-accent text-accent-foreground'
                        : 'text-muted-foreground hover:text-foreground',
                    )
                  }
                >
                  {item.label}
                </NavLink>
              ),
            )}
          </nav>
          <div className="ml-auto flex items-center gap-2">
            {isAuthenticated ? (
              <>
                <NotificationBell />
                <UserMenu />
              </>
            ) : (
              <>
                <Button variant="ghost" size="sm" onClick={() => navigate('/login')}>
                  로그인
                </Button>
                <Button size="sm" onClick={() => navigate('/signup')}>
                  회원가입
                </Button>
              </>
            )}
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-5xl px-4 py-6">
        <Outlet />
      </main>
    </div>
  )
}
