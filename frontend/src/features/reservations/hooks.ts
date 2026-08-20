// 예약 쿼리·mutation 훅.
import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
import { reservationApi } from './api'
import type { ReservationListParams, ReservationRequestInput } from './types'
import { useAuthStore } from '@/lib/auth/authStore'

export const reservationKeys = {
  all: ['reservations'] as const,
  list: (params: ReservationListParams) =>
    ['reservations', 'list', params] as const,
  detail: (id: number) => ['reservations', id] as const,
}

export function useReservations(params: ReservationListParams = {}) {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  return useQuery({
    queryKey: reservationKeys.list(params),
    queryFn: () => reservationApi.list(params),
    enabled: isAuthenticated,
    placeholderData: keepPreviousData,
  })
}

export function useReservationDetail(reservationId: number) {
  return useQuery({
    queryKey: reservationKeys.detail(reservationId),
    queryFn: () => reservationApi.getDetail(reservationId),
    enabled: Number.isFinite(reservationId),
  })
}

export function useCreateReservation() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: ReservationRequestInput) =>
      reservationApi.create(input),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: reservationKeys.all }),
  })
}

/*
  예약 결제수단 재지정. 성공/실패 모두 결제 내역을 다시 불러온다 — 실패가 RESERVATION_019(청구가
  먼저 커밋됨)라면 화면에 아직 "결제 없음"으로 남아 있어서, 다시 읽어야 재지정 UI가 닫힌다.
  결제 내역 키는 paymentKeys.reservationPayments(id)와 같은 배열을 직접 적는다 —
  payments/hooks가 이미 reservationKeys를 import하므로 역방향 import는 순환이 된다.
*/
export function useUpdateReservationPaymentMethod(reservationId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (paymentMethodId: number) =>
      reservationApi.updatePaymentMethod(reservationId, paymentMethodId),
    onSettled: () => {
      queryClient.invalidateQueries({
        queryKey: reservationKeys.detail(reservationId),
      })
      queryClient.invalidateQueries({
        queryKey: ['reservations', reservationId, 'payments'],
      })
    },
  })
}

export function useCancelReservation() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (reservationId: number) => reservationApi.cancel(reservationId),
    onSuccess: (_data, reservationId) => {
      queryClient.invalidateQueries({ queryKey: reservationKeys.all })
      queryClient.invalidateQueries({
        queryKey: reservationKeys.detail(reservationId),
      })
    },
  })
}
