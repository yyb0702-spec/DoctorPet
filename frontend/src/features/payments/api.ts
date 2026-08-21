// 결제 API. 결제수단(등록/조회/삭제)·결제 내역 조회 모두 실연동.
import { http } from '@/lib/api/client'
import type { PaymentChargeResult, PaymentMethod, PaymentRecord, Receipt } from './types'

export interface BillingKeyIssue {
  issueId: string
  redirectUrl: string
}

export const paymentApi = {
  // 결제수단 — 실연동 (SA §8-7).
  // 빌링키 발급은 서버가 만든 일회성 issueId에 귀속한다. 모바일 원문은 서버 콜백으로,
  // PC iframe 원문은 이 issueId 전용 인증 요청 body로만 전달한다.
  listMethods: () => http.get<PaymentMethod[]>('/payment-methods'),
  issueBillingKey: () =>
    http.post<BillingKeyIssue>('/payment-methods/billing-key-issues'),
  completeBillingKeyIssue: (issueId: string, billingKey: string) =>
    http.post<PaymentMethod>(
      `/payment-methods/billing-key-issues/${issueId}/complete`,
      { billingKey },
    ),
  removeMethod: (paymentMethodId: number) =>
    http.delete<void>(`/payment-methods/${paymentMethodId}`),
  // 기본 결제수단 지정 — 실연동 (SA §8-7, PR #152). ACTIVE·본인 소유만 지정할 수 있고,
  // 서버가 기존 기본값을 같은 트랜잭션에서 해제하므로 회원당 기본은 항상 1건이다.
  setDefaultMethod: (paymentMethodId: number) =>
    http.patch<PaymentMethod>(`/payment-methods/${paymentMethodId}/default`),

  // 결제 내역 — 실연동 (SA §8-7, #47). 예약당 여러 건 가능(배열).
  getReservationPayments: (reservationId: number) =>
    http.get<PaymentRecord[]>(`/reservations/${reservationId}/payments`),

  // 결제 실패 셀프 복구(다시 결제) — 실연동 (SA §9-4, 고도화 3.3). 활성 결제가 OFFLINE_REQUIRED일
  // 때만 서버가 성립시킨다(그 외 409). 금액·항목은 보내지 않는다 — 원 결제 총액·항목을 서버가 승계한다.
  // 201로 새 결제가 생기며, 승인 실패도 레코드가 생기고 status로 결과가 온다.
  recharge: (reservationId: number, paymentMethodId: number) =>
    http.post<PaymentChargeResult>(
      `/reservations/${reservationId}/payments/recharge`,
      { paymentMethodId },
    ),

  // JSON 영수증 — 실연동 (PR #158). PAID·OFFLINE_PAID·REFUNDED 결제만. 본인 결제만 조회된다.
  getReceipt: (paymentId: number) =>
    http.get<Receipt>(`/payments/${paymentId}/receipt`),
}
