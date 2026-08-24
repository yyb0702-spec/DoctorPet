import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { MemberHistorySection } from './MemberHistorySection'
import { useMemberHistory } from './hooks'
import { PaymentStatus, ReservationStatus } from '@/types/enums'

vi.mock('./hooks', () => ({ useMemberHistory: vi.fn() }))

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}>{node}</QueryClientProvider>)
}

describe('MemberHistorySection', () => {
  beforeEach(() => {
    vi.mocked(useMemberHistory).mockReturnValue({
      data: {
        content: [
          {
            reservationId: 1,
            reservedAt: '2026-08-20T10:00:00',
            petName: '초코',
            petSpecies: 'DOG',
            reservationStatus: ReservationStatus.TREATMENT_COMPLETED,
            paymentId: 900,
            paymentStatus: PaymentStatus.PAID,
            amount: 45000,
          },
          {
            reservationId: 2,
            reservedAt: '2026-08-01T14:00:00',
            petName: '초코',
            petSpecies: 'DOG',
            reservationStatus: ReservationStatus.CANCELED,
            paymentId: null,
            paymentStatus: null,
            amount: null,
          },
        ],
        page: 0,
        size: 20,
        totalElements: 2,
        totalPages: 1,
        first: true,
        last: true,
      },
      isLoading: false,
      isError: false,
      isFetching: false,
    } as unknown as ReturnType<typeof useMemberHistory>)
  })

  it('닫혀 있다가 열면 이력 행을 보여주고, 결제 있는 행에만 영수증 버튼·미청구 표기를 낸다', async () => {
    const user = userEvent.setup()
    wrap(<MemberHistorySection reservationId={500} />)

    // 처음엔 접혀 있어 행이 없다.
    expect(screen.queryByText('45,000원')).not.toBeInTheDocument()

    await user.click(
      screen.getByRole('button', { name: '우리 병원 진료·결제 이력 보기' }),
    )

    expect(screen.getByText('45,000원')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '영수증' })).toBeInTheDocument()
    // 결제 없는 행은 미청구로만 표기(영수증 버튼 없음, 위 getByRole가 단일이므로 검증됨).
    expect(screen.getByText('미청구')).toBeInTheDocument()
  })
})
