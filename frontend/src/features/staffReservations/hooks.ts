// 병원 스태프 예약 운영 쿼리·mutation 훅.
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { staffReservationApi } from './api'
import type { StaffReservationListParams } from './types'

export const staffReservationKeys = {
  all: ['staff', 'reservations'] as const,
  list: (params: StaffReservationListParams) =>
    ['staff', 'reservations', 'list', params] as const,
}

export function useStaffReservations(params: StaffReservationListParams = {}) {
  return useQuery({
    queryKey: staffReservationKeys.list(params),
    queryFn: () => staffReservationApi.list(params),
    placeholderData: keepPreviousData,
  })
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
