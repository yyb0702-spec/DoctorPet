// 결제수단·결제 내역 훅.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { paymentApi } from './api'
import { useAuthStore } from '@/lib/auth/authStore'
import { reservationKeys } from '@/features/reservations/hooks'

export const paymentKeys = {
  methods: ['payment-methods'] as const,
  reservationPayments: (id: number) =>
    ['reservations', id, 'payments'] as const,
}

export function usePaymentMethods() {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  return useQuery({
    queryKey: paymentKeys.methods,
    queryFn: paymentApi.listMethods,
    enabled: isAuthenticated,
  })
}

export function useRegisterPaymentMethod() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (billingKey: string) => paymentApi.registerMethod(billingKey),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: paymentKeys.methods }),
  })
}

export function useRemovePaymentMethod() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (paymentMethodId: number) =>
      paymentApi.removeMethod(paymentMethodId),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: paymentKeys.methods }),
  })
}

export function useReservationPayments(reservationId: number) {
  return useQuery({
    queryKey: paymentKeys.reservationPayments(reservationId),
    queryFn: () => paymentApi.getReservationPayments(reservationId),
    enabled: Number.isFinite(reservationId),
    // 진료 전이면 결제가 없어 빈 배열이 정상 → 재시도 없이 조용히.
    retry: false,
  })
}

// 보호자 셀프 복구(다시 결제). 성공하면 원 결제를 대체하는 새 결제가 생기므로 결제 내역을 다시 불러오고,
// 예약 상세의 paymentStatus로 그려지는 안내 배너도 함께 갱신한다.
// 주의: 2xx라고 결제 성공이 아니다 — 승인 실패도 201로 오고 status가 OFFLINE_REQUIRED다. 호출부가 판단한다.
export function useRechargePayment(reservationId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (paymentMethodId: number) =>
      paymentApi.recharge(reservationId, paymentMethodId),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: paymentKeys.reservationPayments(reservationId),
      })
      queryClient.invalidateQueries({
        queryKey: reservationKeys.detail(reservationId),
      })
    },
  })
}
