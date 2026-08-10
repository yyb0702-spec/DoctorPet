// 알림 DTO (SA §8-8, PR #87 실계약).
// type은 NotificationType name 문자열(RESERVATION_CONFIRMED/RESERVATION_REJECTED/PAYMENT_RESULT/NO_SHOW).
export type NotificationResourceType = 'RESERVATION' | 'PAYMENT'

export interface Notification {
  id: number
  type: string
  content: string
  resourceType: NotificationResourceType | null // 연결 리소스 종류
  resourceId: number | null // RESERVATION이면 reservationId, PAYMENT이면 paymentId
  isRead: boolean
  readAt: string | null
  createdAt: string
}

// POST /notifications/subscribe-ticket 응답. ticket은 30초 1회성이라 발급 즉시 구독에 써야 한다.
export interface SubscribeTicketResponse {
  ticket: string
}
