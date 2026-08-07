// 병원 스태프 결제 API (SA §8-7, ROLE_HOSPITAL_STAFF).
import { http } from '@/lib/api/client'
import type { PaymentRecord } from '@/features/payments/types'
import type { PageResponse } from '@/types/api'
import type { HospitalPaymentListItem, StaffChargeResult } from './types'

export const staffPaymentApi = {
  // 병원 결제/환불 대시보드 목록 — 상태 필터는 페이지 정합성 때문에 서버에 없다(프론트에서
  // 현재 페이지 안에서만 걸러 보여준다).
  listHospitalPayments: (page = 0, size = 20) =>
    http.get<PageResponse<HospitalPaymentListItem>>(
      `/hospital/payments?page=${page}&size=${size}`,
    ),

  // 진료비 청구 — 201. 게이트웨이 승인 실패도 레코드는 생성되고 status로 결과를 표현한다.
  charge: (reservationId: number, amount: number) =>
    http.post<StaffChargeResult>(
      `/hospital/reservations/${reservationId}/payments`,
      { amount },
    ),
  getPayments: (reservationId: number) =>
    http.get<PaymentRecord[]>(
      `/hospital/reservations/${reservationId}/payments`,
    ),
  // 현장 수납 완료 — 전제 status==OFFLINE_REQUIRED (#36).
  settleOffline: (paymentId: number) =>
    http.patch<PaymentRecord>(`/hospital/payments/${paymentId}/offline-settle`),
  // 전액 환불 — 전제 status==PAID && channel==BILLING_KEY, 이미 REFUNDED면 멱등 200 (#37).
  refund: (paymentId: number, reason: string) =>
    http.post<PaymentRecord>(`/hospital/payments/${paymentId}/refund`, {
      reason,
    }),
}
