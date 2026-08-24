import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { StaffDashboardPage } from './StaffDashboardPage'
import { useStaffReservations } from '@/features/staffReservations/hooks'
import { useHospitalPayments } from '@/features/staffPayments/hooks'
import { PaymentStatus } from '@/types/enums'

vi.mock('@/features/staffReservations/hooks', () => ({
  useStaffReservations: vi.fn(),
}))
vi.mock('@/features/staffPayments/hooks', () => ({
  useHospitalPayments: vi.fn(),
}))

function renderPage() {
  return render(
    <MemoryRouter>
      <StaffDashboardPage />
    </MemoryRouter>,
  )
}

describe('StaffDashboardPage 미수금 카드', () => {
  beforeEach(() => {
    vi.mocked(useStaffReservations).mockReset()
    vi.mocked(useHospitalPayments).mockReset()
    vi.mocked(useStaffReservations).mockReturnValue({
      data: { content: [], totalElements: 0 },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useStaffReservations>)
  })

  it('첫 100건에 미수금이 없어도 다음 페이지가 있으면 전체 확인 카드를 보여준다', () => {
    vi.mocked(useHospitalPayments).mockReturnValue({
      data: {
        content: Array.from({ length: 100 }, () => ({
          paymentStatus: PaymentStatus.PAID,
        })),
        page: 0,
        size: 100,
        totalElements: 101,
        totalPages: 2,
        first: true,
        last: false,
      },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useHospitalPayments>)

    renderPage()

    expect(screen.getByText('미수금 현황 확인 필요')).toBeInTheDocument()
    expect(
      screen.getByText(
        '최근 100건에서는 미수금을 확인하지 못했어요. 전체 목록에서 확인하세요.',
      ),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '결제 관리로' })).toHaveAttribute(
      'href',
      '/staff/payments',
    )
  })
})
