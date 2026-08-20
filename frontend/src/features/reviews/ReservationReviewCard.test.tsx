// 후기 카드가 서버(GET /api/reservations/{id}/review)의 판정만 따르는지 검증한다 — 브라우저에
// 상태를 캐시하던 방식(myReviewCache)을 걷어냈으므로, 다른 기기·새로고침에서도 같은 화면이 나오고
// 삭제 뒤 재작성 차단도 서버의 reviewable=false가 만든다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ReservationReviewCard } from './ReservationReviewCard'
import { reviewApi } from './api'
import type { MyReview, Review } from './types'

vi.mock('./api', () => ({
  reviewApi: {
    getMine: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    remove: vi.fn(),
  },
}))

const getMine = vi.mocked(reviewApi.getMine)
const removeReview = vi.mocked(reviewApi.remove)

function review(overrides: Partial<Review> = {}): Review {
  return {
    reviewId: 10,
    reservationId: 100,
    hospitalId: 1,
    memberId: 2,
    rating: 5,
    content: '설명이 자세했어요.',
    createdAt: '2026-08-10T10:00:00',
    updatedAt: '2026-08-10T10:00:00',
    ...overrides,
  }
}

function renderCard(reservationId: number) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const utils = render(
    <QueryClientProvider client={client}>
      <ReservationReviewCard reservationId={reservationId} />
    </QueryClientProvider>,
  )
  return {
    ...utils,
    rerenderWith: (nextId: number) =>
      utils.rerender(
        <QueryClientProvider client={client}>
          <ReservationReviewCard reservationId={nextId} />
        </QueryClientProvider>,
      ),
  }
}

describe('ReservationReviewCard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('서버가 내 후기를 주면 수정·삭제할 수 있게 보여준다', async () => {
    getMine.mockResolvedValue({ review: review(), reviewable: false })

    renderCard(100)

    expect(await screen.findByText('내가 남긴 후기')).toBeInTheDocument()
    expect(screen.getByText('설명이 자세했어요.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '수정' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '삭제' })).toBeInTheDocument()
  })

  it('후기가 없고 작성 가능하면 작성 폼을 보여준다', async () => {
    getMine.mockResolvedValue({ review: null, reviewable: true })

    renderCard(100)

    expect(await screen.findByText('후기 작성')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '후기 등록' })).toBeInTheDocument()
  })

  // 삭제해도 예약의 작성 기회(reservations.reviewed_at)는 복구되지 않는다. 그 판정은 서버가
  // review=null·reviewable=false로 알려주므로, 프론트가 삭제 사실을 따로 기억할 필요가 없다.
  it('후기가 없고 작성도 불가하면 폼 대신 작성 기회 소진을 안내한다', async () => {
    getMine.mockResolvedValue({ review: null, reviewable: false })

    renderCard(100)

    expect(
      await screen.findByText(/후기 작성 기회는 이미 사용했습니다/),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '후기 등록' }),
    ).not.toBeInTheDocument()
  })

  // PR #189 리뷰 P1 — 같은 인스턴스가 재사용되면 다른 예약의 후기를 실제로 수정·삭제할 수 있다.
  it('예약이 바뀌면 이전 예약의 후기를 남기지 않는다', async () => {
    const byReservation: Record<number, MyReview> = {
      100: { review: review({ reviewId: 10, reservationId: 100 }), reviewable: false },
      200: { review: null, reviewable: true },
    }
    getMine.mockImplementation((reservationId: number) =>
      Promise.resolve(byReservation[reservationId]),
    )

    const { rerenderWith } = renderCard(100)
    expect(await screen.findByText('내가 남긴 후기')).toBeInTheDocument()

    rerenderWith(200)

    expect(await screen.findByText('후기 작성')).toBeInTheDocument()
    expect(screen.queryByText('설명이 자세했어요.')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '삭제' })).not.toBeInTheDocument()
  })

  it('삭제하면 서버 판정을 다시 받아 작성 폼 대신 안내로 바뀐다', async () => {
    getMine
      .mockResolvedValueOnce({ review: review(), reviewable: false })
      .mockResolvedValue({ review: null, reviewable: false })
    removeReview.mockResolvedValue(undefined)

    renderCard(100)
    await screen.findByText('내가 남긴 후기')

    await userEvent.click(screen.getByRole('button', { name: '삭제' }))

    expect(removeReview).toHaveBeenCalledWith(10)
    expect(
      await screen.findByText(/후기 작성 기회는 이미 사용했습니다/),
    ).toBeInTheDocument()
  })
})
