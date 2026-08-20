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
import { MemberRole } from '@/types/enums'

vi.mock('./api', () => ({
  hospitalApi: {
    addFavorite: vi.fn(),
    removeFavorite: vi.fn(),
    listFavorites: vi.fn(),
  },
}))

// 찜은 보호자 전용 API라 버튼이 역할을 본다.
const useMeMock = vi.fn()
vi.mock('@/features/members/hooks', () => ({
  useMe: () => useMeMock(),
  memberKeys: { me: ['members', 'me'] },
}))

const DETAIL = { hospitalId: 7, name: '행복동물병원', favorite: false }

function CurrentPath() {
  const location = useLocation()
  const from = (location.state as { from?: { pathname?: string } } | null)?.from
  return (
    <>
      <span data-testid="path">{location.pathname}</span>
      {/* LoginPage가 읽는 것과 같은 경로(state.from.pathname)로 확인한다. */}
      <span data-testid="from-pathname">{from?.pathname ?? ''}</span>
    </>
  )
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
    useMeMock.mockReturnValue({ data: { role: MemberRole.GUARDIAN } })
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

  /*
    LoginPage는 state.from을 Location 객체로 읽는다(`state.from.pathname`). 문자열을 넘기면
    로그인 후 항상 '/'로 떨어져 원래 병원 화면으로 돌아오지 못한다(리뷰 P2).
  */
  it('로그인으로 보낼 때 복귀 위치를 Location 객체로 넘긴다', async () => {
    useAuthStore.setState({ isAuthenticated: false })
    renderButton(false)

    await userEvent.click(screen.getByRole('button', { name: '찜하기' }))

    expect(screen.getByTestId('from-pathname')).toHaveTextContent('/hospitals/7')
  })

  // 백엔드가 찜 API를 hasRole("GUARDIAN")으로 제한하므로, 스태프에게 하트를 보여주면 403만 난다.
  it('병원 스태프에게는 하트를 노출하지 않는다', () => {
    useMeMock.mockReturnValue({ data: { role: MemberRole.HOSPITAL_STAFF } })
    renderButton(false)

    expect(screen.queryByRole('button', { name: '찜하기' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '찜 해제' })).not.toBeInTheDocument()
  })
})

/*
  검색 목록에서 서로 다른 병원의 하트를 연달아 누르면 두 mutation이 동시에 살아 있다.
  이때 먼저 시작한 쪽이 실패했다고 캐시 전체를 스냅샷으로 되돌리면, 그 사이 성공한 다른 병원의
  토글까지 함께 되돌아간다(리뷰 P2). 실패한 병원의 플래그만 복원해야 한다.
*/
describe('동시 토글', () => {
  beforeEach(() => {
    vi.mocked(hospitalApi.addFavorite).mockReset()
    useMeMock.mockReturnValue({ data: { role: MemberRole.GUARDIAN } })
    useAuthStore.setState({ isAuthenticated: true })
  })

  it('한 병원의 실패가 다른 병원의 성공을 되돌리지 않는다', async () => {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    // 검색 결과 한 페이지에 두 병원이 함께 떠 있는 상황.
    const searchKey = hospitalKeys.search({})
    queryClient.setQueryData(searchKey, {
      content: [
        { hospitalId: 7, favorite: false },
        { hospitalId: 8, favorite: false },
      ],
      page: 1,
      totalPages: 1,
    })

    let rejectSeven: ((reason: Error) => void) | undefined
    vi.mocked(hospitalApi.addFavorite).mockImplementation((hospitalId: number) =>
      hospitalId === 7
        ? new Promise<void>((_resolve, reject) => {
            rejectSeven = reject
          })
        : Promise.resolve(undefined),
    )

    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <FavoriteButton hospitalId={7} favorite={false} />
          <FavoriteButton hospitalId={8} favorite={false} />
        </MemoryRouter>
      </QueryClientProvider>,
    )

    const [seven, eight] = screen.getAllByRole('button', { name: '찜하기' })
    await userEvent.click(seven)
    await userEvent.click(eight)

    // 8은 성공해 캐시가 true, 7은 아직 미결이라 낙관적으로 true.
    const favoriteOf = (hospitalId: number) =>
      queryClient
        .getQueryData<{ content: { hospitalId: number; favorite: boolean }[] }>(searchKey)
        ?.content.find((h) => h.hospitalId === hospitalId)?.favorite

    await waitFor(() => expect(favoriteOf(8)).toBe(true))

    rejectSeven?.(new Error('boom'))

    // 7만 false로 돌아가고 8의 성공은 유지된다.
    await waitFor(() => expect(favoriteOf(7)).toBe(false))
    expect(favoriteOf(8)).toBe(true)
  })
})
