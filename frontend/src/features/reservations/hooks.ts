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
