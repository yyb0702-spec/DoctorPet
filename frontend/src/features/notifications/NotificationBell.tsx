// 헤더의 알림 벨 — 안읽음 배지 + 간단 드롭다운. useNotifications만 바라본다.
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Bell } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { useNotifications } from './useNotifications'
import type { Notification } from './types'

// 보호자 화면의 예약 경로. 병원 스태프 화면은 같은 예약을 /staff/reservations 아래에서 보므로
// StaffLayout이 reservationBasePath로 바꿔 넘긴다.
const GUARDIAN_RESERVATION_BASE_PATH = '/reservations'

export interface NotificationBellProps {
  /**
   * 예약 리소스 알림을 눌렀을 때 이동할 경로의 베이스. 역할별로 같은 예약의 화면 경로가 달라서
   * 벨을 복제하는 대신 이 값만 주입한다(보호자 기본값 `/reservations`, 병원 스태프 `/staff/reservations`).
   * recipientType·hospitalId 같은 수신자 판단은 서버가 인증 principal로 하므로 프론트는 관여하지 않는다.
   */
  reservationBasePath?: string
}

// 알림이 가리키는 리소스로 가는 경로. PAYMENT는 paymentId→예약 매핑이 없어 예약 목록으로.
function resourceLink(n: Notification, reservationBasePath: string): string | null {
  if (n.resourceType === 'RESERVATION' && n.resourceId != null) {
    return `${reservationBasePath}/${n.resourceId}`
  }
  if (n.resourceType === 'PAYMENT') return reservationBasePath
  return null
}

export function NotificationBell({
  reservationBasePath = GUARDIAN_RESERVATION_BASE_PATH,
}: NotificationBellProps = {}) {
  const [open, setOpen] = useState(false)
  const navigate = useNavigate()
  const { notifications, unreadCount, markRead } = useNotifications()

  const handleClick = (n: Notification) => {
    if (!n.isRead) markRead(n.id)
    const link = resourceLink(n, reservationBasePath)
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
