// RowActions가 NO_SHOW_PENDING에서도 CONFIRMED와 동일한 액션을 노출하는지 검증
// (PR #127 리뷰 — SA §8-6, AWAITING_ARRIVAL_STATUSES).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { RowActions } from './StaffReservationQueuePage'
import type { StaffReservationListItem } from '@/features/staffReservations/types'
import { ReservationStatus } from '@/types/enums'

function renderRowActions(status: ReservationStatus) {
  const item: StaffReservationListItem = {
    reservationId: 1,
    memberId: 1,
    petId: 1,
    petName: '나비',
    reservedAt: '2026-08-10T10:00:00',
    reservationStatus: status,
    rejectionReason: null,
    reservationHistory: {
      totalReservationCount: 1,
      completedCount: 0,
      cancelCount: 0,
      noShowCount: 0,
    },
  }
  const queryClient = new QueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <RowActions item={item} />
    </QueryClientProvider>,
  )
}

describe('RowActions', () => {
  it.each([ReservationStatus.CONFIRMED, ReservationStatus.NO_SHOW_PENDING])(
    '%s 상태에서 내원 확인·노쇼 확정 액션을 노출한다',
    (status) => {
      renderRowActions(status)
      expect(
        screen.getByRole('button', { name: '내원 확인' }),
      ).toBeInTheDocument()
      expect(
        screen.getByRole('button', { name: '노쇼 확정' }),
      ).toBeInTheDocument()
    },
  )
})
