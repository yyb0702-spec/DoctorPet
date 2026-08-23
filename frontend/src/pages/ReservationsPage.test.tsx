// ReservationsPage가 예약을 날짜 그룹(오늘·내일 헤더)으로 렌더하고, 카드 시각을 백엔드
// LocalDateTime 그대로(실행 환경 타임존에 밀리지 않게) 표시하는지 검증한다(PR #208 리뷰 P2).
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ReservationsPage } from './ReservationsPage'
import { useReservations } from '@/features/reservations/hooks'
import { usePets } from '@/features/pets/hooks'
import type { ReservationListItem } from '@/features/reservations/types'
import { shiftDateKey, todaySeoulKey } from '@/lib/seoulTime'

vi.mock('@/features/reservations/hooks', () => ({
  useReservations: vi.fn(),
}))
vi.mock('@/features/pets/hooks', () => ({
  usePets: vi.fn(),
}))

const today = todaySeoulKey()
const tomorrow = shiftDateKey(today, 1)

function item(
  overrides: Partial<ReservationListItem> &
    Pick<ReservationListItem, 'reservationId' | 'reservedAt' | 'hospitalName'>,
): ReservationListItem {
  return {
    hospitalId: 1,
    petId: 1,
    petName: '초코',
    reservationStatus: 'CONFIRMED',
    paymentStatus: null,
    progressStatus: 'RESERVATION_CONFIRMED',
    ...overrides,
  }
}

function renderWith(content: ReservationListItem[]) {
  vi.mocked(useReservations).mockReturnValue({
    data: {
      content,
      page: 0,
      size: 20,
      totalElements: content.length,
      totalPages: 1,
      first: true,
      last: true,
    },
    isLoading: false,
    isError: false,
    isFetching: false,
    refetch: vi.fn(),
  } as unknown as ReturnType<typeof useReservations>)
  vi.mocked(usePets).mockReturnValue({
    data: [],
  } as unknown as ReturnType<typeof usePets>)
  return render(
    <MemoryRouter>
      <ReservationsPage />
    </MemoryRouter>,
  )
}

describe('ReservationsPage', () => {
  beforeEach(() => vi.clearAllMocks())

  it('예약을 날짜 그룹(내일→오늘)으로 묶고 같은 날은 한 그룹 헤더 아래 넣는다', () => {
    renderWith([
      item({
        reservationId: 1,
        reservedAt: `${tomorrow}T14:00:00`,
        hospitalName: '내일병원',
      }),
      item({
        reservationId: 2,
        reservedAt: `${today}T10:00:00`,
        hospitalName: '오늘아침병원',
      }),
      item({
        reservationId: 3,
        reservedAt: `${today}T11:00:00`,
        hospitalName: '오늘낮병원',
      }),
    ])

    // 그룹 헤더(h2)는 최근 날짜부터 — 내일, 오늘 순.
    const headings = screen
      .getAllByRole('heading', { level: 2 })
      .map((h) => h.textContent)
    expect(headings).toEqual(['내일', '오늘'])

    // 같은 날(오늘) 2건이 모두 렌더된다.
    expect(screen.getByText('내일병원')).toBeInTheDocument()
    expect(screen.getByText('오늘아침병원')).toBeInTheDocument()
    expect(screen.getByText('오늘낮병원')).toBeInTheDocument()
  })

  it('카드 시각을 백엔드 LocalDateTime 그대로 표시한다(타임존 밀림 없음)', () => {
    renderWith([
      item({
        reservationId: 1,
        reservedAt: `${tomorrow}T14:00:00`,
        hospitalName: '내일병원',
      }),
    ])
    // naive 슬라이스라 실행 환경 타임존과 무관하게 "14:00"이 그대로 보인다(new Date였다면 밀린다).
    expect(screen.getByText('14:00')).toBeInTheDocument()
  })

  it('예약이 없으면 빈 상태 문구를 보여준다', () => {
    renderWith([])
    expect(screen.getByText('예약 내역이 없습니다.')).toBeInTheDocument()
    expect(screen.queryAllByRole('heading', { level: 2 })).toHaveLength(0)
  })
})
