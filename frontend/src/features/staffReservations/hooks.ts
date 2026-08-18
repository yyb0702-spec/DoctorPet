// 병원 스태프 예약 운영 쿼리·mutation 훅.
import {
  keepPreviousData,
  useMutation,
  useQueries,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
import { staffReservationApi } from './api'
import type { StaffReservationListParams } from './types'
import type { ReservationStatus } from '@/types/enums'

export const staffReservationKeys = {
  all: ['staff', 'reservations'] as const,
  list: (params: StaffReservationListParams) =>
    ['staff', 'reservations', 'list', params] as const,
}

interface StaffQueryOptions {
  // 스태프에겐 실시간 알림(SSE)이 없어(백엔드 제약) 새 요청·자동 노쇼를 폴링으로 반영한다.
  refetchInterval?: number
}

export function useStaffReservations(
  params: StaffReservationListParams = {},
  options: StaffQueryOptions = {},
) {
  return useQuery({
    queryKey: staffReservationKeys.list(params),
    queryFn: () => staffReservationApi.list(params),
    placeholderData: keepPreviousData,
    refetchInterval: options.refetchInterval,
  })
}

// 상태 탭별 건수(totalElements)만 가볍게 집계한다(size:1). 탭 배지·triage용.
export function useStaffReservationCounts(
  statuses: ReservationStatus[],
  options: StaffQueryOptions = {},
): Partial<Record<ReservationStatus, number>> {
  const results = useQueries({
    queries: statuses.map((status) => ({
      queryKey: staffReservationKeys.list({ status, page: 0, size: 1 }),
      queryFn: () => staffReservationApi.list({ status, page: 0, size: 1 }),
      refetchInterval: options.refetchInterval,
    })),
  })
  const counts: Partial<Record<ReservationStatus, number>> = {}
  statuses.forEach((status, i) => {
    counts[status] = results[i].data?.totalElements
  })
  return counts
}

// 승인·거절·체크인·진료 전이·노쇼는 상태 탭(요청/확정/내원 등)을 넘나들며 목록을
// 바꾸므로, 개별 쿼리키가 아니라 staff 예약 목록 전체를 무효화한다.
function useInvalidateStaffReservations() {
  const queryClient = useQueryClient()
  return () => queryClient.invalidateQueries({ queryKey: staffReservationKeys.all })
}

export function useApproveReservation() {
  const invalidate = useInvalidateStaffReservations()
  return useMutation({
    mutationFn: (reservationId: number) =>
      staffReservationApi.approve(reservationId),
    onSuccess: invalidate,
  })
}

export function useRejectReservation() {
  const invalidate = useInvalidateStaffReservations()
  return useMutation({
    mutationFn: ({
      reservationId,
      rejectReason,
    }: {
      reservationId: number
      rejectReason: string
    }) => staffReservationApi.reject(reservationId, rejectReason),
    onSuccess: invalidate,
  })
}

export function useCheckInReservation() {
  const invalidate = useInvalidateStaffReservations()
  return useMutation({
    mutationFn: (reservationId: number) =>
      staffReservationApi.checkIn(reservationId),
    onSuccess: invalidate,
  })
}

export function useStartTreatment() {
  const invalidate = useInvalidateStaffReservations()
  return useMutation({
    mutationFn: (reservationId: number) =>
      staffReservationApi.startTreatment(reservationId),
    onSuccess: invalidate,
  })
}

export function useCompleteTreatment() {
  const invalidate = useInvalidateStaffReservations()
  return useMutation({
    mutationFn: (reservationId: number) =>
      staffReservationApi.completeTreatment(reservationId),
    onSuccess: invalidate,
  })
}

export function useConfirmNoShow() {
  const invalidate = useInvalidateStaffReservations()
  return useMutation({
    mutationFn: ({
      reservationId,
      reason,
    }: {
      reservationId: number
      reason: string
    }) => staffReservationApi.confirmNoShow(reservationId, reason),
    onSuccess: invalidate,
  })
}

export function useRestoreNoShow() {
  const invalidate = useInvalidateStaffReservations()
  return useMutation({
    mutationFn: ({
      reservationId,
      reason,
    }: {
      reservationId: number
      reason: string
    }) => staffReservationApi.restoreNoShow(reservationId, reason),
    onSuccess: invalidate,
  })
}
