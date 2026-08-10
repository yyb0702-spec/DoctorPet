// 병원 스태프 예약 운영 API (SA §8-6, ROLE_HOSPITAL_STAFF).
import { http } from '@/lib/api/client'
import type { PageResponse } from '@/types/api'
import type {
  StaffReservationListItem,
  StaffReservationListParams,
} from './types'

function toQuery(params: StaffReservationListParams): string {
  const sp = new URLSearchParams()
  if (params.status) sp.set('status', params.status)
  if (params.page != null) sp.set('page', String(params.page))
  if (params.size != null) sp.set('size', String(params.size))
  const q = sp.toString()
  return q ? `?${q}` : ''
}

export const staffReservationApi = {
  list: (params: StaffReservationListParams = {}) =>
    http.get<PageResponse<StaffReservationListItem>>(
      `/hospital/reservations${toQuery(params)}`,
    ),
  approve: (reservationId: number) =>
    http.patch<void>(`/hospital/reservations/${reservationId}/approve`),
  reject: (reservationId: number, rejectReason: string) =>
    http.patch<void>(`/hospital/reservations/${reservationId}/reject`, {
      rejectReason,
    }),
  checkIn: (reservationId: number) =>
    http.patch<void>(`/hospital/reservations/${reservationId}/check-in`),
  startTreatment: (reservationId: number) =>
    http.patch<void>(`/hospital/reservations/${reservationId}/start`),
  completeTreatment: (reservationId: number) =>
    http.patch<void>(`/hospital/reservations/${reservationId}/complete`),
  confirmNoShow: (reservationId: number, reason: string) =>
    http.patch<void>(`/hospital/reservations/${reservationId}/no-show`, {
      reason,
    }),
  restoreNoShow: (reservationId: number, reason: string) =>
    http.patch<void>(`/hospital/reservations/${reservationId}/restore`, {
      reason,
    }),
}
