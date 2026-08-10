// 결제수단·결제 내역 훅.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { paymentApi } from './api'
import { useAuthStore } from '@/lib/auth/authStore'

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
