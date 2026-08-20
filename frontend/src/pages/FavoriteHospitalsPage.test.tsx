// 찜 해제로 마지막 페이지가 사라졌을 때 빈 페이지에 갇히지 않는지 검증한다(PR #193 리뷰 P2).
// 페이지 이동 버튼까지 사라지므로 사용자가 스스로 1페이지로 돌아갈 방법이 없는 상태가 된다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { FavoriteHospitalsPage } from './FavoriteHospitalsPage'
import { hospitalApi } from '@/features/hospitals/api'
import { useAuthStore } from '@/lib/auth/authStore'
import type { FavoriteHospital } from '@/features/hospitals/types'

vi.mock('@/features/hospitals/api', () => ({
  hospitalApi: {
    listFavorites: vi.fn(),
    addFavorite: vi.fn(),
    removeFavorite: vi.fn(),
  },
}))

// 찜 훅·하트 버튼이 역할을 보고 동작하므로 보호자로 고정한다.
vi.mock('@/features/members/hooks', () => ({
  useMe: () => ({ data: { role: 'GUARDIAN' }, isLoading: false }),
  memberKeys: { me: ['members', 'me'] },
}))

function hospital(id: number): FavoriteHospital {
  return {
    hospitalId: id,
    name: `병원 ${id}`,
    address: '서울 강남구',
    businessStatus: 'OPEN',
    partnershipStatus: 'PARTNER',
    favorite: true,
    favoritedAt: '2026-08-20T00:00:00',
  }
}

function page(content: FavoriteHospital[], pageNo: number, totalPages: number) {
  return {
    content,
    page: pageNo,
    size: 20,
    totalElements: totalPages === 0 ? 0 : content.length + (pageNo - 1) * 20,
    totalPages,
    first: pageNo === 1,
    last: pageNo >= totalPages,
  }
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <FavoriteHospitalsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('FavoriteHospitalsPage', () => {
  beforeEach(() => {
    vi.mocked(hospitalApi.listFavorites).mockReset()
    useAuthStore.setState({ isAuthenticated: true })
  })

  it('2페이지가 비면 마지막 유효 페이지(1)로 접어 빈 화면에 갇히지 않는다', async () => {
    vi.mocked(hospitalApi.listFavorites).mockImplementation(
      async (pageNo = 1) =>
        pageNo === 1
          ? page([hospital(1), hospital(2)], 1, 2)
          : // 마지막 항목을 해제한 뒤의 응답 — content가 비고 totalPages가 줄어든다.
            page([], 2, 1),
    )
    renderPage()
    const user = userEvent.setup()

    expect(await screen.findByText('병원 1')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '다음' }))

    // 2페이지를 요청해 빈 응답을 받은 뒤, 1페이지를 다시 요청해 목록이 되살아난다.
    // 호출 순서(1 → 2 → 1)를 단언해야 "보정이 실제로 일어났는지"를 확인할 수 있다 —
    // 화면만 보면 keepPreviousData가 이전 페이지를 잠시 유지하는 것과 구분되지 않는다.
    await waitFor(() => {
      const pages = vi.mocked(hospitalApi.listFavorites).mock.calls.map((c) => c[0])
      expect(pages).toEqual([1, 2, 1])
    })
    expect(await screen.findByText('병원 1')).toBeInTheDocument()
    expect(screen.queryByText('찜한 병원이 없습니다.')).not.toBeInTheDocument()
  })

  it('처음부터 찜이 없으면 빈 상태를 그대로 보여준다(1페이지에서는 접지 않는다)', async () => {
    vi.mocked(hospitalApi.listFavorites).mockResolvedValue(page([], 1, 0))
    renderPage()

    expect(
      await screen.findByText(/찜한 병원이 없습니다/),
    ).toBeInTheDocument()
  })
})
