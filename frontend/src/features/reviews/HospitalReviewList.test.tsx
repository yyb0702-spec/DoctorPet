// 병원 상세의 후기 목록이 병원 간 이동에서 페이지 상태를 끌고 가지 않는지 검증한다
// (PR #189 리뷰 P2 — 이전 병원의 3페이지를 새 병원에 요청하면 후기가 있어도 빈 목록으로 보인다).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { HospitalReviewList } from './HospitalReviewList'
import { reviewApi } from './api'
import type { ReviewPage } from './types'

vi.mock('./api', () => ({
  reviewApi: {
    listByHospital: vi.fn(),
    getMine: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    remove: vi.fn(),
  },
}))

const listByHospital = vi.mocked(reviewApi.listByHospital)

// 병원마다 후기 2쪽(1쪽당 1건)을 가진 목록을 만든다 — 페이지 이동이 가능해야 상태가 남는지 볼 수 있다.
function page(hospitalId: number, pageNumber: number): ReviewPage {
  return {
    content: [
      {
        reviewId: hospitalId * 100 + pageNumber,
        rating: 4,
        content: `${hospitalId}번 병원 ${pageNumber}쪽 후기`,
        createdAt: '2026-08-10T10:00:00',
        updatedAt: '2026-08-10T10:00:00',
      },
    ],
    page: pageNumber,
    size: 5,
    totalElements: 2,
    totalPages: 2,
    first: pageNumber <= 1,
    last: pageNumber >= 2,
  }
}

function renderList(hospitalId: number) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const view = (id: number) => (
    <QueryClientProvider client={client}>
      <HospitalReviewList hospitalId={id} averageRating={4} reviewCount={2} />
    </QueryClientProvider>
  )
  const utils = render(view(hospitalId))
  return { ...utils, rerenderWith: (nextId: number) => utils.rerender(view(nextId)) }
}

describe('HospitalReviewList', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listByHospital.mockImplementation((hospitalId: number, p: number) =>
      Promise.resolve(page(hospitalId, p)),
    )
  })

  it('병원이 바뀌면 후기 페이지를 1쪽으로 되돌린다', async () => {
    const { rerenderWith } = renderList(1)
    expect(await screen.findByText('1번 병원 1쪽 후기')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: '다음' }))
    expect(await screen.findByText('1번 병원 2쪽 후기')).toBeInTheDocument()

    rerenderWith(2)

    expect(await screen.findByText('2번 병원 1쪽 후기')).toBeInTheDocument()
    expect(listByHospital).toHaveBeenCalledWith(2, 1, 5)
    expect(listByHospital).not.toHaveBeenCalledWith(2, 2, 5)
  })
})
