// ReceiptDialog가 Receipt를 받아 총액·항목(음수 조정 포함)·카드·환불 정보를 그리는지,
// paymentId가 null이면 닫히는지, 로딩·오류 상태와 항목화 이전 결제의 빈 items를 처리하는지
// 검증(PR #158 영수증). 조회는 fetcher 주입으로 대체한다.
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

// 오류 케이스에서 재시도까지 기다리지 않도록 retry를 끈다.
function renderWithFetcher(
  paymentId: number | null,
  fetcher: (paymentId: number) => Promise<Receipt>,
) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const spy = vi.fn(fetcher)
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <ReceiptDialog
        paymentId={paymentId}
        scope="guardian"
        fetcher={spy}
        onClose={() => {}}
      />
    </QueryClientProvider>,
  )
  return { fetcher: spy, ...utils }
}

function renderDialog(paymentId: number | null, receipt = sampleReceipt()) {
  return renderWithFetcher(paymentId, () => Promise.resolve(receipt))
}

describe('ReceiptDialog', () => {
  it('paymentId가 null이면 아무것도 렌더하지 않고 조회도 하지 않는다', () => {
    const { fetcher, container } = renderDialog(null)
    expect(container).toBeEmptyDOMElement()
    expect(fetcher).not.toHaveBeenCalled()
  })

  it('조회 중에는 로딩 문구를 보여준다', () => {
    // 영원히 pending인 fetcher로 로딩 상태를 고정한다.
    renderWithFetcher(9006, () => new Promise<Receipt>(() => {}))
    expect(screen.getByText(/불러오는 중/)).toBeInTheDocument()
  })

  it('조회 실패 시 오류 문구와 다시 시도 버튼을 보여준다', async () => {
    renderWithFetcher(9006, () => Promise.reject(new Error('boom')))
    expect(await screen.findByText(/불러오지 못했습니다/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument()
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

  it('항목화 이전 결제(빈 items)는 항목 표를 숨기고 총액만 보여준다', async () => {
    renderDialog(9006, sampleReceipt({ items: [], totalAmount: 30000 }))
    // 총액은 상단 한 곳에만 나타나고(항목 표의 합계 줄이 없어) 중복되지 않는다.
    expect(await screen.findAllByText('30,000원')).toHaveLength(1)
    expect(screen.queryByText('합계')).not.toBeInTheDocument()
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
