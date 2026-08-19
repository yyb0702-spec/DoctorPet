// 예약당 결제 이력(1:N)에서 활성 결제를 판정한다.
// 활성 = 가장 최근 생성분. 정정 재청구·셀프 복구의 대체는 늘 새 결제를 만들고 이전 결제를
// superseded 처리하므로 최신이 곧 활성이다. 프론트는 supersededAt을 응답으로 받지 않아 이렇게
// 추론하며, 실제 성립 여부(활성만 대체 가능)는 서버의 조건부 전이가 강제한다(SA §5-2·§9-4).
import { PaymentStatus } from '@/types/enums'
import type { PaymentRecord } from './types'

export function activePaymentId(payments: PaymentRecord[]): number | null {
  if (payments.length === 0) return null
  return payments.reduce((latest, p) =>
    new Date(p.createdAt) > new Date(latest.createdAt) ? p : latest,
  ).paymentId
}

/**
 * 보호자 셀프 복구(다시 결제)가 가능한 결제 id. 없으면 null.
 *
 * 게이트는 두 겹이다 — (1) 활성 결제여야 하고(대체된 과거 결제엔 액션이 없다), (2) 그 상태가
 * OFFLINE_REQUIRED여야 한다. PENDING에서는 절대 허용하지 않는다: 승인 여부가 불확정이라
 * 재청구하면 이중 결제가 될 수 있고, 정산 스케줄러의 단건 조회 결과를 기다려야 한다(SA §9-7·§9-4).
 * 서버도 조건부 전이로 같은 규칙을 강제하지만, 화면이 먼저 막아야 보호자가 위험한 버튼을 보지 않는다.
 */
export function rechargeablePaymentId(payments: PaymentRecord[]): number | null {
  const activeId = activePaymentId(payments)
  if (activeId === null) return null
  const active = payments.find((p) => p.paymentId === activeId)
  return active?.status === PaymentStatus.OFFLINE_REQUIRED ? activeId : null
}
