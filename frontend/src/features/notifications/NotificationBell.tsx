// 헤더의 알림 벨 — 안읽음 배지 + 간단 드롭다운. useNotifications만 바라본다.
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Bell } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { useMe } from '@/features/members/hooks'
import { MemberRole } from '@/types/enums'
import { useNotifications } from './useNotifications'
import type { Notification } from './types'

/*
  역할별 리소스 이동 경로. 벨을 쓰는 레이아웃이 경로를 주입하는 방식은 쓰지 않는다 — 경로를 안 넘기면
  조용히 보호자 경로로 떨어지는데, 스태프가 보호자 AppLayout 화면(공개 라우트·미등록 /staff/* → `*`
  NotFound)에 서는 조합이 실제로 있어서 그때 스태프가 보호자 예약 경로 링크를 받게 된다. 그래서 경로는
  벨이 로그인 회원의 role로 직접 고른다.

  수신자 판단(recipientType·hospitalId)은 그대로 서버가 인증 principal로 한다 — 여기서 고르는 건 화면
  경로뿐이다. `satisfies`로 MemberRole 전수를 강제해, 역할이 추가되면 이 표에서 컴파일 에러가 난다.

  reservationDetail이 null인 역할은 단건 상세로 보내지 않고 목록으로 보낸다. 병원 스태프 예약 상세
  (StaffReservationDetailPage)는 목록에서 넘겨준 location.state 없이는 열리지 않아(병원 예약 단건 조회
  API가 SA §8-6에 없다) 알림에서 직접 열 수 없다 — 상세 URL로 보내면 즉시 목록으로 리다이렉트되는
  죽은 링크가 된다(리뷰 지적 P2). 단건 조회 API가 생기면 여기에 함수를 채운다.
*/
interface ResourceRoutes {
  reservationList: string
  reservationDetail: ((reservationId: number) => string) | null
}

const RESOURCE_ROUTES = {
  [MemberRole.GUARDIAN]: {
    reservationList: '/reservations',
    reservationDetail: (reservationId: number) => `/reservations/${reservationId}`,
  },
  [MemberRole.HOSPITAL_STAFF]: {
    reservationList: '/staff/reservations',
    reservationDetail: null,
  },
} satisfies Record<MemberRole, ResourceRoutes>

// 알림이 가리키는 리소스로 가는 경로. PAYMENT는 paymentId→예약 매핑이 없어 예약 목록으로.
function resourceLink(n: Notification, routes: ResourceRoutes): string | null {
  if (n.resourceType === 'RESERVATION' && n.resourceId != null) {
    return routes.reservationDetail
      ? routes.reservationDetail(n.resourceId)
      : routes.reservationList
  }
  if (n.resourceType === 'PAYMENT') return routes.reservationList
  return null
}

export function NotificationBell() {
  const [open, setOpen] = useState(false)
  const navigate = useNavigate()
  const me = useMe()
  const { notifications, unreadCount, markRead } = useNotifications()

  // role이 아직 로딩 중이면 보호자 경로다. 벨을 렌더하는 두 레이아웃이 마운트 시점에 이미 useMe를
  // 호출하므로 드롭다운을 열 수 있는 시점에는 캐시에 값이 있다.
  const routes =
    me.data?.role === MemberRole.HOSPITAL_STAFF
      ? RESOURCE_ROUTES[MemberRole.HOSPITAL_STAFF]
      : RESOURCE_ROUTES[MemberRole.GUARDIAN]

  const handleClick = (n: Notification) => {
    if (!n.isRead) markRead(n.id)
    const link = resourceLink(n, routes)
    if (link) {
      setOpen(false)
      navigate(link)
    }
  }

  return (
    <div className="relative">
      <Button
        variant="ghost"
        size="icon"
        aria-label="알림"
        onClick={() => setOpen((v) => !v)}
      >
        <Bell className="h-5 w-5" />
        {unreadCount > 0 && (
          <span className="absolute right-1 top-1 flex h-4 min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[10px] font-bold text-destructive-foreground">
            {unreadCount > 9 ? '9+' : unreadCount}
          </span>
        )}
      </Button>
      {open && (
        <div className="absolute right-0 mt-2 w-80 rounded-lg border bg-popover p-2 shadow-lg">
          <p className="px-2 py-1 text-xs font-semibold text-muted-foreground">
            알림
          </p>
          {notifications.length === 0 ? (
            <p className="px-2 py-6 text-center text-sm text-muted-foreground">
              새 알림이 없습니다.
            </p>
          ) : (
            <ul className="max-h-80 overflow-auto">
              {notifications.map((n) => (
                <li key={n.id}>
                  <button
                    type="button"
                    onClick={() => handleClick(n)}
                    className={cn(
                      'w-full rounded-md px-2 py-2 text-left text-sm hover:bg-accent',
                      !n.isRead && 'font-medium',
                    )}
                  >
                    <span className="block">{n.content}</span>
                    <span className="block text-xs text-muted-foreground">
                      {new Date(n.createdAt).toLocaleString('ko-KR')}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  )
}
