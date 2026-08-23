// 대기열 승급 수락 폼 — 카드 정보가 없는 카카오페이 수단이 여럿일 때 결제수단 버튼이 서로 구분되는지 검증.
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { WaitlistsPage } from './WaitlistsPage'
import { usePets } from '@/features/pets/hooks'
import { usePaymentMethods } from '@/features/payments/hooks'
import {
  useAcceptWaitlist,
  useCancelWaitlist,
  useMyWaitlists,
  useRejectWaitlist,
} from '@/features/waitlist/hooks'
import { WaitlistStatus } from '@/features/waitlist/types'
import type { PaymentMethod } from '@/features/payments/types'

vi.mock('@/features/pets/hooks', () => ({ usePets: vi.fn() }))
vi.mock('@/features/payments/hooks', () => ({ usePaymentMethods: vi.fn() }))
vi.mock('@/features/waitlist/hooks', () => ({
  useAcceptWaitlist: vi.fn(),
  useCancelWaitlist: vi.fn(),
  useMyWaitlists: vi.fn(),
  useRejectWaitlist: vi.fn(),
}))

const kakaoPayMethods: PaymentMethod[] = [
  {
    id: 1,
    cardBrand: null,
    cardLast4: null,
    status: 'ACTIVE',
    isDefault: false,
    createdAt: '2026-08-20T01:00:00.000Z',
  },
  {
    id: 2,
    cardBrand: null,
    cardLast4: null,
    status: 'ACTIVE',
    isDefault: false,
    createdAt: '2026-08-21T02:00:00.000Z',
  },
]

// OFFERED 제안 — 만료가 한참 뒤여야 수락 버튼이 살아 있다.
const offeredWaitlist = {
  waitlistId: 10,
  slotId: 5,
  status: WaitlistStatus.OFFERED,
  requestedAt: '2026-08-20T01:00:00',
  offerExpiresAt: '2999-01-01T00:00:00',
}

function renderPage() {
  return render(
    <MemoryRouter>
      <WaitlistsPage />
    </MemoryRouter>,
  )
}

describe('WaitlistsPage 승급 수락 폼 결제수단 표시', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(useMyWaitlists).mockReturnValue({
      data: [offeredWaitlist],
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useMyWaitlists>)
    vi.mocked(usePets).mockReturnValue({
      data: [{ petId: 1, name: '초코', species: 'DOG', imageUrl: null }],
      isLoading: false,
      isError: false,
    } as unknown as ReturnType<typeof usePets>)
    vi.mocked(usePaymentMethods).mockReturnValue({
      data: kakaoPayMethods,
      isLoading: false,
      isError: false,
    } as unknown as ReturnType<typeof usePaymentMethods>)
    vi.mocked(useAcceptWaitlist).mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
      isError: false,
      error: null,
    } as unknown as ReturnType<typeof useAcceptWaitlist>)
    vi.mocked(useCancelWaitlist).mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
      isError: false,
      error: null,
    } as unknown as ReturnType<typeof useCancelWaitlist>)
    vi.mocked(useRejectWaitlist).mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
      isError: false,
      error: null,
    } as unknown as ReturnType<typeof useRejectWaitlist>)
  })

  it('카드 정보가 없는 카카오페이 수단이 여럿이면 등록 시각으로 구분해 표시한다', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: '수락' }))

    const buttons = screen.getAllByRole('button', { name: /카카오페이/ })
    expect(buttons).toHaveLength(2)
    expect(buttons[0].textContent).not.toBe(buttons[1].textContent)
    expect(buttons[0]).toHaveTextContent('등록')
  })
})
