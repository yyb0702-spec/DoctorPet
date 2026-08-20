// 후기 목록 캐시가 조회 파라미터를 모두 구분하는지 검증한다 — size가 쿼리 키에서 빠지면 같은
// 병원·페이지를 다른 크기로 부를 때 이전 크기의 응답을 그대로 재사용한다(PR #189 리뷰 P2).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useHospitalReviews } from './hooks'
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

function pageOf(size: number): ReviewPage {
  return {
    content: [],
    page: 1,
    size,
    totalElements: 0,
    totalPages: 0,
    first: true,
    last: true,
  }
}

describe('useHospitalReviews', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listByHospital.mockImplementation((_hospitalId, _page, size) =>
      Promise.resolve(pageOf(size)),
    )
  })

  it('같은 병원·페이지라도 size가 다르면 캐시를 나눠 다시 조회한다', async () => {
    // 두 훅이 같은 캐시를 공유해야 재사용 여부가 드러난다.
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    )

    const small = renderHook(() => useHospitalReviews(1, 1, 5), { wrapper })
    await waitFor(() => expect(small.result.current.data?.size).toBe(5))

    const large = renderHook(() => useHospitalReviews(1, 1, 20), { wrapper })
    // 키가 갈리지 않으면 붙자마자 5건짜리 응답을 그대로 물려받는다(뒤이은 재조회로 덮이더라도
    // 그 사이 화면은 잘못된 크기의 목록을 보여준다). 새 키면 데이터 없이 로딩부터 시작한다.
    expect(large.result.current.data).toBeUndefined()

    await waitFor(() => expect(large.result.current.data?.size).toBe(20))
    expect(listByHospital).toHaveBeenCalledWith(1, 1, 5)
    expect(listByHospital).toHaveBeenCalledWith(1, 1, 20)
    // 먼저 붙은 훅은 남의 조회 결과에 흔들리지 않는다.
    expect(small.result.current.data?.size).toBe(5)
  })
})
