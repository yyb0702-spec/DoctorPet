// 병원 스태프 회원 이력 API (ROLE_HOSPITAL_STAFF). 예약으로 회원을 해석하므로 memberId를 보내지 않는다.
import { http } from '@/lib/api/client'
import type { PageResponse } from '@/types/api'
import type { HospitalMemberHistoryItem } from './types'

export const staffMemberHistoryApi = {
  getMemberHistory: (reservationId: number, page = 0, size = 20) =>
    http.get<PageResponse<HospitalMemberHistoryItem>>(
      `/hospital/reservations/${reservationId}/member-history?page=${page}&size=${size}`,
    ),
}
