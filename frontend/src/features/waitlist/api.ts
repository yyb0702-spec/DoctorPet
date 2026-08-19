// 예약 대기열 API. 6개 엔드포인트 모두 보호자 인증, ApiResponse 래핑(http가 언랩).
// dev:mock에선 MSW가 대체한다.
import { http } from '@/lib/api/client'
import type {
  Waitlist,
  WaitlistAcceptInput,
  WaitlistAcceptResult,
} from './types'

const BASE = '/reservation-waitlists'

export const waitlistApi = {
  register: (slotId: number) => http.post<Waitlist>(BASE, { slotId }),
  list: () => http.get<Waitlist[]>(BASE),
  getDetail: (waitlistId: number) =>
    http.get<Waitlist>(`${BASE}/${waitlistId}`),
  // 취소는 WAITING 상태만 허용(백엔드 WAITLIST_006).
  cancel: (waitlistId: number) => http.delete<void>(`${BASE}/${waitlistId}`),
  // 수락/거절은 OFFERED 상태만 허용(WAITLIST_004/005). accept는 예약을 생성해 반환한다.
  accept: (waitlistId: number, input: WaitlistAcceptInput) =>
    http.post<WaitlistAcceptResult>(`${BASE}/${waitlistId}/accept`, input),
  reject: (waitlistId: number) =>
    http.patch<void>(`${BASE}/${waitlistId}/reject`),
}
