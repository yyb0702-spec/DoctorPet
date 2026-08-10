// 인증 mutation·쿼리 훅.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { authApi } from './api'
import type { LoginInput, SignupInput } from './schema'
import { useAuthStore } from '@/lib/auth/authStore'
import { ApiError } from '@/lib/api/error'

export function useSignup() {
  return useMutation({
    mutationFn: (input: SignupInput) => authApi.signup(input),
  })
}

export function useLogin() {
  const signIn = useAuthStore((s) => s.signIn)
  return useMutation({
    mutationFn: (input: LoginInput) => authApi.login(input),
    onSuccess: (tokens) => {
      signIn(tokens.accessToken, tokens.refreshToken)
    },
  })
}

export function usePasswordResetRequest() {
  return useMutation({
    mutationFn: (email: string) => authApi.requestPasswordReset(email),
  })
}

export function usePasswordResetConfirm() {
  return useMutation({
    mutationFn: (input: { token: string; newPassword: string }) =>
      authApi.confirmPasswordReset(input.token, input.newPassword),
  })
}

// 이메일 인증은 GET 검증이라 쿼리로 다룬다. 토큰은 1회용이므로 키 기반 dedup + staleTime
// Infinity로 (StrictMode 재마운트를 포함해) 토큰당 정확히 한 번만 호출한다.
export function useVerifyEmail(token: string) {
  return useQuery({
    queryKey: ['auth', 'verify-email', token],
    queryFn: () => authApi.verifyEmail(token),
    enabled: Boolean(token),
    retry: false,
    staleTime: Infinity,
    gcTime: Infinity,
    refetchOnWindowFocus: false,
    refetchOnMount: false,
  })
}

export function useResendVerificationEmail() {
  return useMutation({
    mutationFn: (email: string) => authApi.resendVerificationEmail(email),
  })
}

export function useLogout() {
  const signOut = useAuthStore((s) => s.signOut)
  const queryClient = useQueryClient()
  return useMutation({
    // 로그아웃 API가 실패(예: 이미 만료)해도 로컬 세션은 정리한다.
    mutationFn: async () => {
      try {
        await authApi.logout()
      } catch (error) {
        if (!(error instanceof ApiError)) throw error
      }
    },
    onSettled: () => {
      signOut()
      queryClient.clear()
    },
  })
}
