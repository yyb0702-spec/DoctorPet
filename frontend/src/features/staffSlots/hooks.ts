// 병원 스태프 슬롯 관리 쿼리·mutation 훅.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { staffSlotApi } from './api'

export const staffSlotKeys = {
  list: (date: string) => ['staff', 'slots', date] as const,
}

export function useStaffSlots(date: string) {
  return useQuery({
    queryKey: staffSlotKeys.list(date),
    queryFn: () => staffSlotApi.list(date),
    enabled: Boolean(date),
  })
}

export function useCloseSlot(date: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (slotId: number) => staffSlotApi.close(slotId),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: staffSlotKeys.list(date) }),
  })
}

export function useReopenSlot(date: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (slotId: number) => staffSlotApi.reopen(slotId),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: staffSlotKeys.list(date) }),
  })
}

export function useCloseDay(date: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => staffSlotApi.closeDay(date),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: staffSlotKeys.list(date) }),
  })
}
