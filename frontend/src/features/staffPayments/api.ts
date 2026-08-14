// 병원 스태프 결제 API (SA §8-7, ROLE_HOSPITAL_STAFF).
import { http } from '@/lib/api/client'
import type { PaymentRecord } from '@/features/payments/types'
import type { PageResponse } from '@/types/api'
import type {
  HospitalPaymentListItem,
  PaymentItemDraft,
  PaymentItemInput,
  StaffChargeResult,
} from './types'

export const staffPaymentApi = {
  // 병원 결제/환불 대시보드 목록 — 상태 필터는 페이지 정합성 때문에 서버에 없다(프론트에서
  // 현재 페이지 안에서만 걸러 보여준다).
  listHospitalPayments: (page = 0, size = 20) =>
    http.get<PageResponse<HospitalPaymentListItem>>(
      `/hospital/payments?page=${page}&size=${size}`,
    ),

  // 청구 항목 초안 조회 — 청구 전 작성 중인 목록. 청구 후에는 스탬프돼 빈 배열이 된다.
  getItemDrafts: (reservationId: number) =>
    http.get<PaymentItemDraft[]>(
      `/hospital/reservations/${reservationId}/payment-items`,
    ),
  // 청구 항목 초안 전체 교체(PUT) — 부분 수정이 아니라 최종 목록을 통째로 보낸다.
  // 항목 금액·총액은 보내지 않는다(서버가 quantity × unitPrice로 산출, SA §9-4).
  saveItemDrafts: (reservationId: number, items: PaymentItemInput[]) =>
    http.put<PaymentItemDraft[]>(
      `/hospital/reservations/${reservationId}/payment-items`,
      { items },
    ),
  // 진료비 청구 — 저장된 초안 항목의 합계로 청구하므로 **body가 없다**(SA §8-7).
  // 초안이 없으면 서버가 PAYMENT_ITEM_REQUIRED(409)로 거부한다.
  charge: (reservationId: number) =>
    http.post<StaffChargeResult>(
      `/hospital/reservations/${reservationId}/payments`,
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
