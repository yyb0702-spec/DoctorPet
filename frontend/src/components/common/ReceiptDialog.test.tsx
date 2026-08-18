// ReceiptDialog가 Receipt를 받아 총액·항목(음수 조정 포함)·카드·환불 정보를 그리는지,
// paymentId가 null이면 닫히는지 검증(PR #158 영수증). 조회는 fetcher 주입으로 대체한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ReceiptDialog } from './ReceiptDialog'
import type { Receipt } from '@/features/payments/types'

function sampleReceipt(overrides: Partial<Receipt> = {}): Receipt {
  return {
    paymentId: 9006,
    reservationId: 5006,
    hospitalId: 1,
    guardianMemberId: 1,
    petId: 1,
    petName: '초코',
    petSpecies: 'DOG',
    status: 'PAID',
    paymentChannel: 'BILLING_KEY',
    paidAt: '2026-08-10T10:00:00',
    offlineSettledAt: null,
    cardBrandSnapshot: 'KB',
    cardLast4Snapshot: '5678',
    items: [
      { name: '진찰료', quantity: 1, unitPrice: 15000, amount: 15000 },
      { name: '재진 할인', quantity: 1, unitPrice: -5000, amount: -5000 },
    ],
    totalAmount: 10000,
    refundStatus: null,
    refundedAt: null,
    ...overrides,
  }
}

function renderDialog(paymentId: number | null, receipt = sampleReceipt()) {
  const fetcher = vi.fn().mockResolvedValue(receipt)
  const queryClient = new QueryClient()
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <ReceiptDialog
        paymentId={paymentId}
        scope="guardian"
        fetcher={fetcher}
        onClose={() => {}}
      />
    </QueryClientProvider>,
  )
  return { fetcher, ...utils }
}

describe('ReceiptDialog', () => {
  it('paymentId가 null이면 아무것도 렌더하지 않고 조회도 하지 않는다', () => {
    const { fetcher, container } = renderDialog(null)
    expect(container).toBeEmptyDOMElement()
    expect(fetcher).not.toHaveBeenCalled()
  })

  it('영수증 총액·항목(음수 조정 포함)·카드 정보를 그린다', async () => {
    renderDialog(9006)
    // 총액(합계 라벨 옆)과 상단 금액 모두 10,000원.
    expect(await screen.findAllByText('10,000원')).toHaveLength(2)
    expect(screen.getByText('진찰료')).toBeInTheDocument()
    expect(screen.getByText('재진 할인')).toBeInTheDocument()
    expect(screen.getByText('-5,000원')).toBeInTheDocument()
    expect(screen.getByText(/KB \*\*\*\*5678/)).toBeInTheDocument()
    expect(screen.getByText(/초코/)).toBeInTheDocument()
  })

  it('환불된 결제는 환불 완료 라벨을 표시한다', async () => {
    renderDialog(
      9006,
      sampleReceipt({
        status: 'REFUNDED',
        refundStatus: 'COMPLETED',
        refundedAt: '2026-08-11T09:00:00',
      }),
    )
    // 상태 배지와 환불 상세줄 두 곳에 "환불 완료"가 나타난다.
    expect(await screen.findAllByText(/환불 완료/)).toHaveLength(2)
  })
})
