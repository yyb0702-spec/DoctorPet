// 병원 스태프 결제 쿼리·mutation 훅.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { staffPaymentApi } from './api'
import type { PaymentItemInput } from './types'

export const staffPaymentKeys = {
  reservationPayments: (reservationId: number) =>
    ['staff', 'reservations', reservationId, 'payments'] as const,
  dashboard: (page: number, size: number) =>
    ['staff', 'payments', 'dashboard', page, size] as const,
  itemDrafts: (reservationId: number) =>
    ['staff', 'reservations', reservationId, 'payment-items'] as const,
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

export function useItemDrafts(reservationId: number) {
  return useQuery({
    queryKey: staffPaymentKeys.itemDrafts(reservationId),
    queryFn: () => staffPaymentApi.getItemDrafts(reservationId),
    enabled: Number.isFinite(reservationId),
  })
}

// 청구 항목 초안을 저장한 뒤 곧바로 청구한다. 서버 계약이 "저장된 초안의 합계로 청구"이므로
// 두 요청의 순서가 곧 계약이다(SA §9-4) — 저장이 실패하면 청구하지 않는다.
// 저장만 성공하고 청구가 실패해도 초안은 남으므로, 스태프는 같은 화면에서 다시 청구할 수 있다.
export function useChargePayment(reservationId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (items: PaymentItemInput[]) => {
      await staffPaymentApi.saveItemDrafts(reservationId, items)
      return staffPaymentApi.charge(reservationId)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: staffPaymentKeys.reservationPayments(reservationId),
      })
      queryClient.invalidateQueries({
        queryKey: staffPaymentKeys.itemDrafts(reservationId),
      })
    },
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
