import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { authApi } from './api'
import { useLogin } from './hooks'
import { hospitalKeys } from '@/features/hospitals/hooks'
import { tokenStore } from '@/lib/auth/tokenStore'
import { useAuthStore } from '@/lib/auth/authStore'

vi.mock('./api', () => ({
  authApi: {
    login: vi.fn(),
  },
}))

describe('useLogin', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    tokenStore.clear()
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    })
    vi.mocked(authApi.login).mockResolvedValue({
      accessToken: 'access-token',
      refreshToken: 'refresh-token',
    })
  })

  afterEach(() => {
    tokenStore.clear()
    queryClient.clear()
    vi.clearAllMocks()
  })

  it('비로그인 홈 조회 뒤 로그인하면 병원 검색 캐시를 비우고 호출별 성공 콜백을 유지한다', async () => {
    const searchKey = hospitalKeys.search({ page: 1, size: 20 })
    // 비로그인 응답은 favorite=false다. 로그인 뒤 이 값을 재사용하면 실제 찜 상태가 숨겨진다.
    queryClient.setQueryData(searchKey, {
      content: [{ hospitalId: 1, name: '가나동물병원', favorite: false }],
    })
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    const { result } = renderHook(() => useLogin(), { wrapper })
    const onSuccess = vi.fn()

    act(() => {
      result.current.mutate(
        {
          email: 'guardian@example.com',
          password: 'password123',
        },
        { onSuccess },
      )
    })

    await waitFor(() => expect(onSuccess).toHaveBeenCalledTimes(1))

    expect(authApi.login).toHaveBeenCalledWith({
      email: 'guardian@example.com',
      password: 'password123',
    })
    expect(queryClient.getQueryData(searchKey)).toBeUndefined()
    expect(useAuthStore.getState().isAuthenticated).toBe(true)
  })
})
