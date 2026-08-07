// 알림 조회를 캡슐화하는 단일 훅 (프론트 스택 문서 §5).
// 실시간 갱신은 SSE(notificationSse)가 주 채널이고, 화면은 이 훅 인터페이스만 바라보므로
// 구현체가 바뀌어도(SSE↔폴링↔STOMP) 화면 코드는 손대지 않는다.
import { useEffect } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuthStore } from '@/lib/auth/authStore'
import { notificationApi } from './api'
import { subscribeToNotifications } from './notificationSse'

// SSE가 완전히 막히는 환경(사내망 프록시·확장 프로그램 등)에 대비한 안전망.
// 실패 횟수를 추적해 조건부로 켜는 대신, 연결 성공 여부와 무관하게 항상 낮은 빈도로 병행한다 —
// 로직이 더 단순하고, react-query가 중복 refetch를 알아서 처리한다.
const SAFETY_POLL_INTERVAL_MS = 120_000
// 벨 드롭다운은 최신 1페이지(20건)만 본다. 안읽음 배지는 이 범위 기준.
const BELL_PAGE_SIZE = 20

export const notificationKeys = {
  all: ['notifications'] as const,
}

export function useNotifications() {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  const queryClient = useQueryClient()

  const query = useQuery({
    queryKey: notificationKeys.all,
    queryFn: () => notificationApi.list({ page: 0, size: BELL_PAGE_SIZE }),
    enabled: isAuthenticated,
    refetchInterval: isAuthenticated ? SAFETY_POLL_INTERVAL_MS : false,
    // 알림 조회가 실패해도 화면을 막지 않는다 — 재시도 없이 빈 목록.
    retry: false,
  })

  useEffect(() => {
    if (!isAuthenticated) return
    return subscribeToNotifications(() => {
      queryClient.invalidateQueries({ queryKey: notificationKeys.all })
    })
  }, [isAuthenticated, queryClient])

  const markRead = useMutation({
    mutationFn: (id: number) => notificationApi.markRead(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: notificationKeys.all })
    },
  })

  const notifications = query.data?.content ?? []
  const unreadCount = notifications.filter((n) => !n.isRead).length

  return {
    notifications,
    unreadCount,
    isLoading: query.isLoading,
    isError: query.isError,
    refetch: query.refetch,
    markRead: markRead.mutate,
  }
}
