// 예약당 결제 이력(1:N)에서 활성 결제를 판정한다.
// 활성 = 가장 최근 생성분. 정정 재청구·셀프 복구의 대체는 늘 새 결제를 만들고 이전 결제를
// superseded 처리하므로 최신이 곧 활성이다. 프론트는 supersededAt을 응답으로 받지 않아 이렇게
// 추론하며, 실제 성립 여부(활성만 대체 가능)는 서버의 조건부 전이가 강제한다(SA §5-2·§9-4).
import type { PaymentRecord } from '@/features/payments/types'

export function activePaymentId(payments: PaymentRecord[]): number | null {
  if (payments.length === 0) return null
  return payments.reduce((latest, p) =>
    new Date(p.createdAt) > new Date(latest.createdAt) ? p : latest,
  ).paymentId
}
