// 병원 스태프 운영(진료시간·진료역량·임시휴진) 쿼리·mutation 훅.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { hospitalOpsApi } from './api'
import type { OperatingHoursUpdateRequest } from './types'
import type { CapabilityValue } from '@/types/enums'
import { hospitalKeys } from '@/features/hospitals/hooks'

export const hospitalOpsKeys = {
  operatingHours: ['hospital-ops', 'operating-hours'] as const,
  capabilities: ['hospital-ops', 'capabilities'] as const,
}

export function useOperatingHours() {
  return useQuery({
    queryKey: hospitalOpsKeys.operatingHours,
    queryFn: () => hospitalOpsApi.getOperatingHours(),
  })
}

export function useUpdateOperatingHours() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: OperatingHoursUpdateRequest) =>
      hospitalOpsApi.updateOperatingHours(body),
    onSuccess: (data) => {
      queryClient.setQueryData(hospitalOpsKeys.operatingHours, data)
      // 진료시간이 바뀌면 발행된 슬롯도 교체되므로 병원 상세·슬롯 캐시를 버린다.
      queryClient.invalidateQueries({ queryKey: hospitalKeys.all })
    },
  })
}

export function useCapabilities() {
  return useQuery({
    queryKey: hospitalOpsKeys.capabilities,
    queryFn: () => hospitalOpsApi.getCapabilities(),
  })
}

export function useUpdateCapabilities() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (capabilities: CapabilityValue[]) =>
      hospitalOpsApi.updateCapabilities(capabilities),
    onSuccess: (data) => {
      queryClient.setQueryData(hospitalOpsKeys.capabilities, data)
      // 같은 값이 보호자 병원 상세의 진료역량 태그·검색 필터로 쓰인다.
      queryClient.invalidateQueries({ queryKey: hospitalKeys.all })
    },
  })
}

export function useCreateTemporaryClosure() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (businessDate: string) =>
      hospitalOpsApi.createTemporaryClosure(businessDate),
    // 그 날의 예약 가능 슬롯이 사라지므로 보호자 쪽 슬롯 조회 캐시를 버린다.
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: hospitalKeys.all }),
  })
}

export function useCancelTemporaryClosure() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (businessDate: string) =>
      hospitalOpsApi.cancelTemporaryClosure(businessDate),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: hospitalKeys.all }),
  })
}
