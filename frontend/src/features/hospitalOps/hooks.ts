// 병원 스태프 운영(진료시간·진료역량·임시휴진) 쿼리·mutation 훅.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { hospitalOpsApi } from './api'
import type { OperatingHours, OperatingHoursUpdateRequest } from './types'
import type { CapabilityValue } from '@/types/enums'
import { hospitalKeys } from '@/features/hospitals/hooks'

export const hospitalOpsKeys = {
  operatingHours: ['hospital-ops', 'operating-hours'] as const,
  scheduledOperatingHours: [
    'hospital-ops',
    'operating-hours',
    'scheduled',
  ] as const,
  capabilities: ['hospital-ops', 'capabilities'] as const,
}

export function useOperatingHours() {
  return useQuery({
    queryKey: hospitalOpsKeys.operatingHours,
    queryFn: () => hospitalOpsApi.getOperatingHours(),
  })
}

// 현재 GET에는 미래 정책을 섞지 않는다. 목록을 별도 캐시로 두어 재진입 시에도 예정 시간표를
// 서버에서 다시 읽고, 현재 시간표를 편집하다 같은 발효일을 덮어쓰지 않게 한다.
export function useScheduledOperatingHours() {
  return useQuery({
    queryKey: hospitalOpsKeys.scheduledOperatingHours,
    queryFn: () => hospitalOpsApi.getScheduledOperatingHours(),
  })
}

export function useUpdateOperatingHours() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: OperatingHoursUpdateRequest) =>
      hospitalOpsApi.updateOperatingHours(body),
    onSuccess: (savedSchedule) => {
      // PUT은 미래 발효 시간표를 돌려준다. 현재 시간표 캐시에는 넣지 않고, 예정 시간표 목록
      // 캐시에 같은 발효일을 교체해 저장 직후에도 재편집할 수 있게 한다.
      queryClient.setQueryData<OperatingHours[]>(
        hospitalOpsKeys.scheduledOperatingHours,
        (previous = []) =>
          [
            ...previous.filter(
              (item) => item.effectiveFrom !== savedSchedule.effectiveFrom,
            ),
            savedSchedule,
          ].sort((first, second) =>
            first.effectiveFrom.localeCompare(second.effectiveFrom),
          ),
      )
      queryClient.invalidateQueries({
        queryKey: hospitalOpsKeys.operatingHours,
      })
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
