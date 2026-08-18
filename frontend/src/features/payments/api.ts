// 결제 API. 결제수단(등록/조회/삭제)·결제 내역 조회 모두 실연동.
import { http } from '@/lib/api/client'
import type { PaymentMethod, PaymentRecord, Receipt } from './types'

export const paymentApi = {
  // 결제수단 — 실연동 (SA §8-7).
  // 등록은 빌링키만 받는다. 빌링키 발급(카드 인증)은 PortOne SDK 몫으로,
  // 프론트 통합은 후속 작업. 지금은 발급된 billingKey 문자열을 전달한다.
  listMethods: () => http.get<PaymentMethod[]>('/payment-methods'),
  registerMethod: (billingKey: string) =>
    http.post<PaymentMethod>('/payment-methods', { billingKey }),
  removeMethod: (paymentMethodId: number) =>
    http.delete<void>(`/payment-methods/${paymentMethodId}`),

  // 결제 내역 — 실연동 (SA §8-7, #47). 예약당 여러 건 가능(배열).
  getReservationPayments: (reservationId: number) =>
    http.get<PaymentRecord[]>(`/reservations/${reservationId}/payments`),

  // JSON 영수증 — 실연동 (PR #158). PAID·OFFLINE_PAID·REFUNDED 결제만. 본인 결제만 조회된다.
  getReceipt: (paymentId: number) =>
    http.get<Receipt>(`/payments/${paymentId}/receipt`),
}
