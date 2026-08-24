// 알림 API (SA §8-8, 실연동). 목록은 페이지 응답, 읽음 처리는 Void.
import { http } from '@/lib/api/client'
import type { PageResponse } from '@/types/api'
import type {
  Notification,
  NotificationDeleteAllResult,
  NotificationReadAllResult,
  NotificationUnreadCount,
  SubscribeTicketResponse,
} from './types'

export interface NotificationListParams {
  isRead?: boolean
  page?: number // 0-base
  size?: number
}

function toQuery(p: NotificationListParams): string {
  const sp = new URLSearchParams()
  if (p.isRead != null) sp.set('isRead', String(p.isRead))
  if (p.page != null) sp.set('page', String(p.page))
  if (p.size != null) sp.set('size', String(p.size))
  const q = sp.toString()
  return q ? `?${q}` : ''
}

export const notificationApi = {
  list: (params: NotificationListParams = {}) =>
    http.get<PageResponse<Notification>>(`/notifications${toQuery(params)}`),
  markRead: (notificationId: number) =>
    http.patch<void>(`/notifications/${notificationId}/read`),
  // 배지용 미읽음 개수. 수신자(회원/병원)는 서버가 인증 principal로 판정하므로 파라미터가 없다.
  getUnreadCount: () =>
    http.get<NotificationUnreadCount>('/notifications/unread-count'),
  // 모두 읽음(조건부 bulk UPDATE, 멱등). 갱신된 건수만 돌려준다.
  markAllRead: () =>
    http.patch<NotificationReadAllResult>('/notifications/read-all'),
  // 전체 삭제(수신자 알림 하드 삭제, 멱등). 삭제된 건수만 돌려준다.
  deleteAll: () =>
    http.delete<NotificationDeleteAllResult>('/notifications'),
  // 실시간 구독용 1회성 티켓 발급(인증 필요). EventSource는 헤더를 못 실어 티켓으로 대신 식별한다.
  issueTicket: () =>
    http.post<SubscribeTicketResponse>('/notifications/subscribe-ticket'),
}
