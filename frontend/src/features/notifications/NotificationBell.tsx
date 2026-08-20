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
  // 대기열 승급 제안(RESERVATION_WAITLIST) 딥링크. 단건 상세 화면이 없어 목록으로 보낸다.
  // 스태프는 대기열 화면이 없어 null(WAITLIST_OFFERED는 보호자에게만 발송된다).
  waitlistList: string | null
}

const RESOURCE_ROUTES = {
  [MemberRole.GUARDIAN]: {
    reservationList: '/reservations',
    reservationDetail: (reservationId: number) => `/reservations/${reservationId}`,
    waitlistList: '/waitlists',
  },
  [MemberRole.HOSPITAL_STAFF]: {
    reservationList: '/staff/reservations',
    reservationDetail: null,
    waitlistList: null,
  },
} satisfies Record<MemberRole, ResourceRoutes>

// 알림이 가리키는 리소스로 가는 경로. PAYMENT는 paymentId→예약 매핑이 없어 예약 목록으로.
// RESERVATION_WAITLIST는 resourceId가 대기열 id지만 단건 화면이 없어 내 대기열 목록으로 보낸다.
function resourceLink(n: Notification, routes: ResourceRoutes): string | null {
  if (n.resourceType === 'RESERVATION' && n.resourceId != null) {
    return routes.reservationDetail
      ? routes.reservationDetail(n.resourceId)
      : routes.reservationList
  }
  if (n.resourceType === 'PAYMENT') return routes.reservationList
  if (n.resourceType === 'RESERVATION_WAITLIST') return routes.waitlistList
  return null
}

export function NotificationBell() {
  const [open, setOpen] = useState(false)
  const navigate = useNavigate()
  const me = useMe()
  const {
    notifications,
    unreadCount,
    markRead,
    markAllRead,
    isMarkingAllRead,
    refetch,
  } = useNotifications()

  /*
    역할을 아직 모르는 동안(내 정보 조회 로딩 중이거나 실패)에는 어느 쪽 경로도 고르지 않는다. 보호자를
    기본값으로 두면 그 창에 스태프가 알림을 눌렀을 때 보호자 전용 경로로 떨어진다(리뷰 지적 P2).
    /staff/* 안쪽은 StaffRoute가 role 확인 전까지 레이아웃 자체를 렌더하지 않아 안전하지만, 공개·보호자
    라우트의 AppLayout은 role 게이트 없이 인증만으로 벨을 렌더하므로(스태프가 홈·병원검색에 있는 경우)
    실제로 도달 가능한 창이다. useMe는 실패 시 재시도하는 동안 계속 undefined라 창이 짧지도 않다.

    이동만 보류하고 읽음 처리는 그대로 한다 — 읽음은 역할과 무관하고, 서버가 인증 principal로 판단한다.
  */
  const routes = me.data ? RESOURCE_ROUTES[me.data.role] : null

  const handleClick = (n: Notification) => {
    if (!n.isRead) markRead(n.id)
    const link = routes && resourceLink(n, routes)
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
        onClick={() => {
          // 목록은 주기 폴링을 하지 않으므로(useNotifications 주석) 열 때 한 번 최신을 받는다.
          if (!open) refetch()
          setOpen((v) => !v)
        }}
      >
        <Bell className="h-5 w-5" />
        {unreadCount > 0 && (
          <span className="absolute right-1 top-1 flex h-4 min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[10px] font-bold text-destructive-foreground">
            {unreadCount > 99 ? '99+' : unreadCount}
          </span>
        )}
      </Button>
      {open && (
        <div className="absolute right-0 mt-2 w-80 rounded-lg border bg-popover p-2 shadow-lg">
          <div className="flex items-center justify-between px-2 py-1">
            <p className="text-xs font-semibold text-muted-foreground">알림</p>
            {/*
              배지 개수는 서버 집계라 드롭다운에 보이는 20건보다 많을 수 있다. 그래서 "모두 읽음"은
              보이는 항목을 순회하지 않고 서버의 bulk UPDATE 한 번(PATCH /notifications/read-all)에 맡긴다.
            */}
            {unreadCount > 0 && (
              <Button
                variant="ghost"
                size="sm"
                className="h-6 px-2 text-xs"
                disabled={isMarkingAllRead}
                onClick={() => markAllRead()}
              >
                {isMarkingAllRead ? '처리 중…' : '모두 읽음'}
              </Button>
            )}
          </div>
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
