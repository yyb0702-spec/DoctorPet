// 병원 스태프 전용 레이아웃 — 좌측 사이드바(예약 관리) + 상단(병원명·로그아웃).
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { CalendarCheck, CalendarClock, CreditCard, LayoutDashboard, PawPrint } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { useMe } from '@/features/members/hooks'
import { useHospitalDetail } from '@/features/hospitals/hooks'
import { useLogout } from '@/features/auth/hooks'

const NAV = [
  { to: '/staff', label: '대시보드', icon: LayoutDashboard, end: true },
  { to: '/staff/reservations', label: '예약 관리', icon: CalendarCheck },
  { to: '/staff/payments', label: '결제 관리', icon: CreditCard },
  { to: '/staff/slots', label: '슬롯 관리', icon: CalendarClock },
]

export function StaffLayout() {
  const me = useMe()
  const hospital = useHospitalDetail(me.data?.hospitalId ?? Number.NaN)
  const navigate = useNavigate()
  const logout = useLogout()

  const handleLogout = () => {
    logout.mutate(undefined, {
      onSettled: () => navigate('/login'),
    })
  }

  return (
    <div className="flex min-h-svh bg-background">
      <aside className="hidden w-56 flex-col border-r bg-muted/30 sm:flex">
        <div className="flex h-14 items-center gap-2 border-b px-4 font-bold text-primary">
          <PawPrint className="h-5 w-5" />
          DoctorPet
        </div>
        <nav className="flex flex-col gap-1 p-3">
          {NAV.map(({ to, label, icon: Icon, end }) => (
            <NavLink
              key={to}
              to={to}
              end={end}
              className={({ isActive }) =>
                cn(
                  'flex items-center gap-2 rounded-md px-3 py-2 text-sm font-medium transition-colors',
                  isActive
                    ? 'bg-accent text-accent-foreground'
                    : 'text-muted-foreground hover:text-foreground',
                )
              }
            >
              <Icon className="h-4 w-4" />
              {label}
            </NavLink>
          ))}
        </nav>
      </aside>
      <div className="flex flex-1 flex-col">
        <header className="sticky top-0 z-30 flex h-14 items-center justify-between border-b bg-background/80 px-4 backdrop-blur">
          <div className="text-sm font-medium">
            {hospital.data?.name ?? '병원 운영'}
          </div>
          <div className="flex items-center gap-3">
            <span className="hidden text-sm text-muted-foreground sm:inline">
              {me.data?.nickname ?? me.data?.email}
            </span>
            <Button
              variant="ghost"
              size="sm"
              disabled={logout.isPending}
              onClick={handleLogout}
            >
              로그아웃
            </Button>
          </div>
        </header>
        <main className="flex-1 px-4 py-6">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
