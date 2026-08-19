// 예약 대기열 쿼리·mutation 훅.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { waitlistApi } from './api'
import type { Waitlist, WaitlistAcceptInput } from './types'
import { WaitlistStatus } from './types'
import { reservationKeys } from '@/features/reservations/hooks'
import { useAuthStore } from '@/lib/auth/authStore'

export const waitlistKeys = {
  all: ['waitlists'] as const,
  list: () => ['waitlists', 'list'] as const,
  detail: (id: number) => ['waitlists', id] as const,
}

// 목록 폴링 — WAITING→OFFERED 전이(배치 승급)와 offerExpiresAt 만료를 화면에 반영하기 위해
// 주기적으로 재조회한다. SSE 알림과 별개의 안전망.
const POLL_INTERVAL_MS = 15_000

// 활성(WAITING·OFFERED) 항목이 있을 때만 의미가 있는 전이라, 종료 상태만 남으면 폴링을 멈춘다.
function hasActiveWaitlist(data: Waitlist[] | undefined): boolean {
  return (data ?? []).some(
    (w) =>
      w.status === WaitlistStatus.WAITING ||
      w.status === WaitlistStatus.OFFERED,
  )
}

export function useMyWaitlists() {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  return useQuery({
    queryKey: waitlistKeys.list(),
    queryFn: () => waitlistApi.list(),
    enabled: isAuthenticated,
    refetchInterval: (query) =>
      hasActiveWaitlist(query.state.data) ? POLL_INTERVAL_MS : false,
  })
}

export function useWaitlistDetail(waitlistId: number) {
  return useQuery({
    queryKey: waitlistKeys.detail(waitlistId),
    queryFn: () => waitlistApi.getDetail(waitlistId),
    enabled: Number.isFinite(waitlistId),
  })
}

export function useRegisterWaitlist() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (slotId: number) => waitlistApi.register(slotId),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: waitlistKeys.all }),
  })
}

export function useCancelWaitlist() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (waitlistId: number) => waitlistApi.cancel(waitlistId),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: waitlistKeys.all }),
  })
}

export function useAcceptWaitlist() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({
      waitlistId,
      input,
    }: {
      waitlistId: number
      input: WaitlistAcceptInput
    }) => waitlistApi.accept(waitlistId, input),
    onSuccess: () => {
      // 수락은 예약을 생성하므로 대기열과 예약 목록 캐시를 함께 무효화한다.
      queryClient.invalidateQueries({ queryKey: waitlistKeys.all })
      queryClient.invalidateQueries({ queryKey: reservationKeys.all })
    },
  })
}

export function useRejectWaitlist() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (waitlistId: number) => waitlistApi.reject(waitlistId),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: waitlistKeys.all }),
  })
}
