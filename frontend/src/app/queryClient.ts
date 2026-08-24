// TanStack Query 클라이언트. 4xx는 재시도하지 않고, 서버 오류만 소폭 재시도한다.
import { QueryClient } from '@tanstack/react-query'
import { ApiError } from '@/lib/api/error'

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      gcTime: 5 * 60_000,
      refetchOnWindowFocus: false,
      retry: (failureCount, error) => {
        // 클라이언트 오류(4xx)는 재시도 무의미. 서버 오류만 2회까지.
        if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
          return false
        }
        return failureCount < 2
      },
    },
    mutations: {
      retry: false,
    },
  },
})
