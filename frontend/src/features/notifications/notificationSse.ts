// 실시간 알림(SSE) 연결 관리 (SA §9-8, PR #106/#107 실연동).
// 브라우저 EventSource는 커스텀 헤더(Authorization)를 못 실으므로, 인증된 요청으로 30초 1회성
// 티켓을 발급받아 쿼리로 제시해 구독한다(백엔드 NotificationSubscriptionController).
//
// 네이티브 EventSource 자동 재연결은 실패해도 "이미 소비된" 같은 티켓으로 같은 URL을 재요청하므로
// 401이 영구 반복된다. 그래서 오류 시 즉시 close()로 네이티브 재연결을 막고, 새 티켓으로 우리가
// 직접 재연결한다(지수 백오프, 최대 30초).
import { notificationApi } from './api'
import type { Notification } from './types'

const RECONNECT_BASE_MS = 1_000
const RECONNECT_MAX_MS = 30_000
const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api'
const NOTIFICATION_EVENT = 'notification'

// 구독을 시작하고 정리 함수를 반환한다. 인증 여부 판단은 호출자(useNotifications) 책임.
export function subscribeToNotifications(
  onNotification: (notification: Notification) => void,
): () => void {
  let stopped = false
  let source: EventSource | null = null
  let retryTimer: ReturnType<typeof setTimeout> | null = null
  let attempt = 0

  async function connect() {
    if (stopped) return
    let ticket: string
    try {
      ticket = (await notificationApi.issueTicket()).ticket
    } catch {
      // 티켓 발급 실패(네트워크·미인증 등) — 백오프 후 재시도.
      scheduleReconnect()
      return
    }
    if (stopped) return

    const es = new EventSource(
      `${BASE_URL}/notifications/subscribe?ticket=${encodeURIComponent(ticket)}`,
    )
    source = es

    es.addEventListener('open', () => {
      attempt = 0
    })

    es.addEventListener(NOTIFICATION_EVENT, (event) => {
      try {
        onNotification(JSON.parse((event as MessageEvent).data) as Notification)
      } catch {
        // 페이로드 하나가 깨져도 연결 자체는 유지한다.
      }
    })

    es.addEventListener('error', () => {
      // close()로 네이티브 자동 재연결(같은 티켓·같은 URL → 영구 401)을 막는다.
      es.close()
      if (source === es) source = null
      scheduleReconnect()
    })
  }

  function scheduleReconnect() {
    if (stopped) return
    const delay = Math.min(RECONNECT_BASE_MS * 2 ** attempt, RECONNECT_MAX_MS)
    attempt += 1
    retryTimer = setTimeout(connect, delay)
  }

  connect()

  return () => {
    stopped = true
    if (retryTimer) clearTimeout(retryTimer)
    source?.close()
  }
}
