// 알림 DTO (SA §8-8, PR #87 실계약).
// type은 NotificationType name 문자열(RESERVATION_CONFIRMED/RESERVATION_REJECTED/PAYMENT_RESULT/
// NO_SHOW/RESERVATION_WAITLIST_OFFERED).
export type NotificationResourceType =
  | 'RESERVATION'
  | 'PAYMENT'
  | 'RESERVATION_WAITLIST'

export interface Notification {
  id: number
  type: string
  content: string
  resourceType: NotificationResourceType | null // 연결 리소스 종류
  resourceId: number | null // RESERVATION이면 reservationId, PAYMENT이면 paymentId, RESERVATION_WAITLIST이면 waitlistId
  isRead: boolean
  readAt: string | null
  createdAt: string
}

// POST /notifications/subscribe-ticket 응답. ticket은 30초 1회성이라 발급 즉시 구독에 써야 한다.
export interface SubscribeTicketResponse {
  ticket: string
}

// GET /notifications/unread-count 응답(고도화 3.8). 배지 숫자만 필요할 때 목록을 폴링하지 않는다.
export interface NotificationUnreadCount {
  unreadCount: number
}

// PATCH /notifications/read-all 응답. 이번 요청에서 미읽음→읽음으로 바뀐 건수(멱등이라 0일 수 있다).
export interface NotificationReadAllResult {
  updatedCount: number
}
