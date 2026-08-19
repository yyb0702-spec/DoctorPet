// 결제수단 선택 목록의 표시명. 카드사·뒷자리가 같은 수단이 여러 개일 수 있어(자동 결제가 실패해
// 같은 은행 카드를 다시 등록하는 흐름이 실제로 그렇다) 문구가 겹치면 고를 수가 없다.
// 겹칠 때만 등록 시각을 덧붙여 구분한다 — 평소에는 짧은 문구를 유지한다.
import type { PaymentMethod } from './types'

type LabelSource = Pick<PaymentMethod, 'id' | 'cardBrand' | 'cardLast4' | 'createdAt'>

// 간편결제로 발급한 빌링키는 카드 정보가 비어 올 수 있다. 그때는 최소한 "카드"로 표기한다.
function baseLabel(method: LabelSource): string {
  return method.cardBrand
    ? `${method.cardBrand} ****${method.cardLast4 ?? '****'}`
    : '카드'
}

/** id → 표시명. 같은 표시명이 둘 이상이면 그 항목들에만 등록 시각을 붙인다. */
export function paymentMethodLabels(methods: LabelSource[]): Map<number, string> {
  const counts = new Map<string, number>()
  for (const method of methods) {
    const base = baseLabel(method)
    counts.set(base, (counts.get(base) ?? 0) + 1)
  }

  const labels = new Map<number, string>()
  for (const method of methods) {
    const base = baseLabel(method)
    labels.set(
      method.id,
      (counts.get(base) ?? 0) > 1
        ? `${base} · ${new Date(method.createdAt).toLocaleString('ko-KR')} 등록`
        : base,
    )
  }
  return labels
}
