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
      // 저장 응답이 준 토큰으로 곧바로 청구한다 — 그 사이 다른 직원이 초안을 바꾸면 서버가 409로 막는다.
      const saved = await staffPaymentApi.saveItemDrafts(reservationId, items)
      return staffPaymentApi.charge(reservationId, saved.draftToken)
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

// 정정 재청구(SA §9-4). useChargePayment와 같은 저장→청구 순서 계약이며 마지막 호출만
// correctionCharge다. 활성 결제가 REFUNDED일 때만 서버가 성립시킨다(그 외 409). 성공하면 기존
// 환불 결제를 대체하는 새 결제가 생기므로 결제 내역을 무효화해 타임라인을 다시 불러온다.
export function useCorrectionCharge(reservationId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (items: PaymentItemInput[]) => {
      const saved = await staffPaymentApi.saveItemDrafts(reservationId, items)
      return staffPaymentApi.correctionCharge(reservationId, saved.draftToken)
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
