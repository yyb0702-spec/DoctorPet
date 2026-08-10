// 전역 Provider — TanStack Query + 세션 만료 처리 + 토스트.
import { useEffect, type ReactNode } from 'react'
import { QueryClientProvider } from '@tanstack/react-query'
import { queryClient } from '@/app/queryClient'
import { onSessionExpired } from '@/lib/api/authEvents'
import { useAuthStore } from '@/lib/auth/authStore'

export function AppProviders({ children }: { children: ReactNode }) {
  useEffect(() => {
    // 인터셉터가 세션 만료를 알리면 인증 상태를 내리고 쿼리 캐시를 비운다.
    // 라우팅은 ProtectedRoute가 다음 렌더에서 로그인으로 넘긴다.
    const unsubscribe = onSessionExpired(() => {
      useAuthStore.getState().sync()
      queryClient.clear()
    })
    return unsubscribe
  }, [])

  return (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
}
