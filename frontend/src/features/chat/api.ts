// 예약당 채팅 REST 계약. 인증·401 재발급은 공통 Axios 인터셉터를 그대로 사용한다.
import { http } from '@/lib/api/client'
import type { ChatMessagePage } from './types'

const PAGE_SIZE = 100

export const chatApi = {
  getMessages: (reservationId: number, afterMessageId?: number) => {
    const params = new URLSearchParams({ size: String(PAGE_SIZE) })
    if (afterMessageId != null) {
      params.set('afterMessageId', String(afterMessageId))
    }
    return http.get<ChatMessagePage>(
      `/reservations/${reservationId}/chat/messages?${params.toString()}`,
    )
  },
  markRead: (reservationId: number) =>
    http.patch<void>(`/reservations/${reservationId}/chat/messages/read`),
}
