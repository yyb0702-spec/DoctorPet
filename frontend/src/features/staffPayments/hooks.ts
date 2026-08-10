// 병원 스태프 결제 쿼리·mutation 훅.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { staffPaymentApi } from './api'

export const staffPaymentKeys = {
  reservationPayments: (reservationId: number) =>
    ['staff', 'reservations', reservationId, 'payments'] as const,
  dashboard: (page: number, size: number) =>
    ['staff', 'payments', 'dashboard', page, size] as const,
}

export function useHospitalPayments(page = 0, size = 20) {
  return useQuery({
    queryKey: staffPaymentKeys.dashboard(page, size),
    queryFn: () => staffPaymentApi.listHospitalPayments(page, size),
  })
}

export function useStaffReservationPayments(reservationId: number) {
  return useQuery({
    queryKey: staffPaymentKeys.reservationPayments(reservationId),
    queryFn: () => staffPaymentApi.getPayments(reservationId),
    enabled: Number.isFinite(reservationId),
  })
}

export function useChargePayment(reservationId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (amount: number) =>
      staffPaymentApi.charge(reservationId, amount),
    onSuccess: () =>
      queryClient.invalidateQueries({
        queryKey: staffPaymentKeys.reservationPayments(reservationId),
      }),
  })
}

export function useSettleOffline(reservationId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (paymentId: number) => staffPaymentApi.settleOffline(paymentId),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: staffPaymentKeys.reservationPayments(reservationId),
      })
      // 대시보드 목록에서 호출된 경우도 있어 함께 무효화한다(예약 상세에서만 쓰는 경우는 no-op).
      queryClient.invalidateQueries({ queryKey: ['staff', 'payments', 'dashboard'] })
    },
  })
}

export function useRefundPayment(reservationId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ paymentId, reason }: { paymentId: number; reason: string }) =>
      staffPaymentApi.refund(paymentId, reason),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: staffPaymentKeys.reservationPayments(reservationId),
      })
      queryClient.invalidateQueries({ queryKey: ['staff', 'payments', 'dashboard'] })
    },
  })
}
