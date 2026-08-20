// 하트는 서버 응답을 기다리지 않고 즉시 뒤집히고, 실패하면 원래대로 돌아와야 한다.
// 낙관적 편집이 캐시(병원 상세)에 실제로 반영되는지, 실패 시 되돌려지는지까지 확인한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { FavoriteButton } from './FavoriteButton'
import { hospitalApi } from './api'
import { hospitalKeys } from './hooks'
import { useAuthStore } from '@/lib/auth/authStore'

vi.mock('./api', () => ({
  hospitalApi: {
    addFavorite: vi.fn(),
    removeFavorite: vi.fn(),
    listFavorites: vi.fn(),
  },
}))

const DETAIL = { hospitalId: 7, name: '행복동물병원', favorite: false }

function CurrentPath() {
  return <span data-testid="path">{useLocation().pathname}</span>
}

function renderButton(favorite = false) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  // 상세 화면이 이미 떠 있는 상황 — 낙관적 편집 대상이 되는 캐시를 심는다.
  queryClient.setQueryData(hospitalKeys.detail(7), { ...DETAIL, favorite })
  const view = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/hospitals/7']}>
        <FavoriteButton hospitalId={7} favorite={favorite} />
        <CurrentPath />
        <Routes>
          <Route path="/hospitals/:id" element={<span />} />
          <Route path="/login" element={<span data-testid="screen">로그인</span>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return { ...view, queryClient }
}

function cachedFavorite(queryClient: QueryClient): boolean | undefined {
  return queryClient.getQueryData<{ favorite: boolean }>(hospitalKeys.detail(7))
    ?.favorite
}

describe('FavoriteButton', () => {
  beforeEach(() => {
    vi.mocked(hospitalApi.addFavorite).mockReset()
    vi.mocked(hospitalApi.removeFavorite).mockReset()
    useAuthStore.setState({ isAuthenticated: true })
  })

  it('찜하기를 누르면 PUT을 호출하고 캐시의 favorite가 즉시 true가 된다', async () => {
    vi.mocked(hospitalApi.addFavorite).mockResolvedValue(undefined)
    const { queryClient } = renderButton(false)

    await userEvent.click(screen.getByRole('button', { name: '찜하기' }))

    expect(hospitalApi.addFavorite).toHaveBeenCalledWith(7)
    await waitFor(() => expect(cachedFavorite(queryClient)).toBe(true))
  })

  it('이미 찜한 병원은 해제(DELETE)를 호출한다', async () => {
    vi.mocked(hospitalApi.removeFavorite).mockResolvedValue(undefined)
    renderButton(true)

    await userEvent.click(screen.getByRole('button', { name: '찜 해제' }))

    expect(hospitalApi.removeFavorite).toHaveBeenCalledWith(7)
  })

  it('요청이 실패하면 낙관적 편집을 되돌린다', async () => {
    vi.mocked(hospitalApi.addFavorite).mockRejectedValue(new Error('boom'))
    const { queryClient } = renderButton(false)

    await userEvent.click(screen.getByRole('button', { name: '찜하기' }))

    // 실패가 확정된 뒤에는 캐시가 원래 값(false)으로 복구돼 하트가 다시 비어 보인다.
    await waitFor(() => expect(cachedFavorite(queryClient)).toBe(false))
  })

  /*
    비로그인 상태에서는 서버가 favorite=false만 주고 호출은 401이 된다. 눌렀을 때 실패를 보여주는
    대신 로그인으로 보내고, API는 아예 호출하지 않는다.
  */
  it('비로그인 상태에서는 API를 호출하지 않고 로그인으로 보낸다', async () => {
    useAuthStore.setState({ isAuthenticated: false })
    renderButton(false)

    await userEvent.click(screen.getByRole('button', { name: '찜하기' }))

    expect(hospitalApi.addFavorite).not.toHaveBeenCalled()
    expect(screen.getByTestId('path')).toHaveTextContent('/login')
  })
})
