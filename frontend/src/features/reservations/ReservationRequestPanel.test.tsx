// 예약 요청 패널 — 카드 정보가 없는 카카오페이 수단이 여럿일 때 결제수단 버튼이 서로 구분되는지 검증.
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ReservationRequestPanel } from './ReservationRequestPanel'
import { useHospitalSlots } from '@/features/hospitals/hooks'
import { usePets } from '@/features/pets/hooks'
import { usePaymentMethods } from '@/features/payments/hooks'
import { useCreateReservation } from './hooks'
import { useMyWaitlists, useRegisterWaitlist } from '@/features/waitlist/hooks'
import { useAuthStore } from '@/lib/auth/authStore'
import type { PaymentMethod } from '@/features/payments/types'

vi.mock('@/features/hospitals/hooks', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/features/hospitals/hooks')>()),
  useHospitalSlots: vi.fn(),
}))
vi.mock('@/features/pets/hooks', () => ({ usePets: vi.fn() }))
vi.mock('@/features/payments/hooks', () => ({ usePaymentMethods: vi.fn() }))
vi.mock('./hooks', () => ({ useCreateReservation: vi.fn() }))
vi.mock('@/features/waitlist/hooks', () => ({
  useMyWaitlists: vi.fn(),
  useRegisterWaitlist: vi.fn(),
}))

// 카드사·뒷자리가 비어 표시 문구가 겹치는 카카오페이 수단 두 개(둘 다 비기본으로 두어 '기본' 꼬리표가 아닌
// 등록 시각 덧붙임만으로 구분되는지 본다).
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

function renderPanel() {
  return render(
    <MemoryRouter>
      <ReservationRequestPanel hospitalId={1} />
    </MemoryRouter>,
  )
}

describe('ReservationRequestPanel 결제수단 표시', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useAuthStore.setState({ isAuthenticated: true })
    vi.mocked(useHospitalSlots).mockReturnValue({
      data: undefined,
      isLoading: false,
    } as unknown as ReturnType<typeof useHospitalSlots>)
    vi.mocked(usePets).mockReturnValue({
      data: [],
      isLoading: false,
      isError: false,
    } as unknown as ReturnType<typeof usePets>)
    vi.mocked(usePaymentMethods).mockReturnValue({
      data: kakaoPayMethods,
      isLoading: false,
      isError: false,
    } as unknown as ReturnType<typeof usePaymentMethods>)
    vi.mocked(useCreateReservation).mockReturnValue({
      mutate: vi.fn(),
      isError: false,
      error: null,
    } as unknown as ReturnType<typeof useCreateReservation>)
    vi.mocked(useMyWaitlists).mockReturnValue({
      data: [],
    } as unknown as ReturnType<typeof useMyWaitlists>)
    vi.mocked(useRegisterWaitlist).mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
      isError: false,
    } as unknown as ReturnType<typeof useRegisterWaitlist>)
  })

  it('카드 정보가 없는 카카오페이 수단이 여럿이면 등록 시각으로 구분해 표시한다', () => {
    renderPanel()
    const buttons = screen.getAllByRole('button', { name: /카카오페이/ })
    expect(buttons).toHaveLength(2)
    // 둘 다 "카카오페이"지만 서로 다른 문구여야 한다(구분 불가 → 잘못된 결제수단 선택 방지).
    expect(buttons[0].textContent).not.toBe(buttons[1].textContent)
    expect(buttons[0]).toHaveTextContent('등록')
  })
})
