// 병원 스태프 결제 API (SA §8-7, ROLE_HOSPITAL_STAFF).
import { http } from '@/lib/api/client'
import type { PaymentRecord, Receipt } from '@/features/payments/types'
import type { PageResponse } from '@/types/api'
import type {
  HospitalPaymentListItem,
  PaymentItemDraftResult,
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
    http.get<PaymentItemDraftResult>(
      `/hospital/reservations/${reservationId}/payment-items`,
    ),
  // 청구 항목 초안 전체 교체(PUT) — 부분 수정이 아니라 최종 목록을 통째로 보낸다.
  // 항목 금액·총액은 보내지 않는다(서버가 quantity × unitPrice로 산출, SA §9-4).
  saveItemDrafts: (reservationId: number, items: PaymentItemInput[]) =>
    http.put<PaymentItemDraftResult>(
      `/hospital/reservations/${reservationId}/payment-items`,
      { items },
    ),
  // 진료비 청구 — 금액·항목은 보내지 않고 초안 토큰만 보낸다(SA §8-7). 총액은 서버가 초안 합계로
  // 산출하며, 저장 이후 다른 직원이 초안을 바꿨으면 토큰 불일치로 PAYMENT_ITEM_CHANGED(409)가 온다.
  charge: (reservationId: number, draftToken: string) =>
    http.post<StaffChargeResult>(
      `/hospital/reservations/${reservationId}/payments`,
      { draftToken },
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
  // JSON 영수증 — 실연동 (PR #158). PAID·OFFLINE_PAID·REFUNDED 결제만. 자병원 결제만 조회된다.
  getReceipt: (paymentId: number) =>
    http.get<Receipt>(`/hospital/payments/${paymentId}/receipt`),
}
