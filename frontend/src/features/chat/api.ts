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
  // 실제로 화면에 병합한 마지막 메시지까지만 읽음 처리한다 — 상한이 없으면 아직 도착하지 않은
  // 상대 메시지까지 서버가 읽음으로 바꾼다(SA §8-9).
  markRead: (reservationId: number, throughMessageId: number) =>
    http.patch<void>(`/reservations/${reservationId}/chat/messages/read`, {
      throughMessageId,
    }),
}
