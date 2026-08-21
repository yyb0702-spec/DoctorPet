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
    onSuccess: () => {
      // PUT은 미래 발효 시간표를 돌려줄 수 있지만, GET은 오늘 유효한 시간표만 돌려준다.
      // 같은 캐시에 PUT 응답을 쓰면 저장 직후 미래 시간표를 "현재 적용 중"으로 잘못 표시한다.
      queryClient.invalidateQueries({ queryKey: hospitalOpsKeys.operatingHours })
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
