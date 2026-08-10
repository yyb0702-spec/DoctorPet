// 회원 조회·수정·탈퇴 훅.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { memberApi } from './api'
import type { Member } from './api'
import { useAuthStore } from '@/lib/auth/authStore'

export const memberKeys = {
  me: ['members', 'me'] as const,
}

export function useMe() {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  return useQuery({
    queryKey: memberKeys.me,
    queryFn: memberApi.getMe,
    enabled: isAuthenticated,
  })
}

// 닉네임 수정 — 성공 시 캐시된 내 정보를 응답으로 즉시 갱신.
export function useUpdateNickname() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (nickname: string) => memberApi.updateNickname(nickname),
    onSuccess: (updated: Member) => {
      queryClient.setQueryData(memberKeys.me, updated)
    },
  })
}

// 회원 탈퇴 — 성공 시 로컬 세션·캐시 정리(로그아웃). 화면 이동은 호출부에서.
export function useWithdraw() {
  const signOut = useAuthStore((s) => s.signOut)
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => memberApi.withdraw(),
    onSuccess: () => {
      signOut()
      queryClient.clear()
    },
  })
}
