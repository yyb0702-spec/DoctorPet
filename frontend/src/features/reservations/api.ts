// 예약 API. 요청·취소·목록·상세 모두 실연동(PR #68). dev:mock에선 MSW가 대체.
import { http } from '@/lib/api/client'
import type { PageResponse } from '@/types/api'
import type {
  ReservationDetail,
  ReservationListItem,
  ReservationListParams,
  ReservationRequestInput,
  ReservationResponse,
} from './types'

function toQuery(params: ReservationListParams): string {
  const sp = new URLSearchParams()
  if (params.status) sp.set('status', params.status)
  if (params.from) sp.set('from', params.from)
  if (params.to) sp.set('to', params.to)
  if (params.page != null) sp.set('page', String(params.page))
  if (params.size != null) sp.set('size', String(params.size))
  if (params.sort) sp.set('sort', params.sort)
  const q = sp.toString()
  return q ? `?${q}` : ''
}

export const reservationApi = {
  create: (input: ReservationRequestInput) =>
    http.post<ReservationResponse>('/reservations', input),
  cancel: (reservationId: number) =>
    http.patch<void>(`/reservations/${reservationId}/cancel`),
  list: (params: ReservationListParams = {}) =>
    http.get<PageResponse<ReservationListItem>>(
      `/reservations${toQuery(params)}`,
    ),
  getDetail: (reservationId: number) =>
    http.get<ReservationDetail>(`/reservations/${reservationId}`),
  // 예약 결제수단 재지정 — 실연동 (SA §8-7, PR #152). 본인 소유 ACTIVE 수단만 지정할 수 있고,
  // 결제 선기록이 있으면 RESERVATION_019, 진료 시작 후면 RESERVATION_018로 거절된다.
  updatePaymentMethod: (reservationId: number, paymentMethodId: number) =>
    http.patch<void>(`/reservations/${reservationId}/payment-method`, {
      paymentMethodId,
    }),
}
