// 병원 스태프 슬롯 관리 API (ROLE_HOSPITAL_STAFF).
import { http } from '@/lib/api/client'
import type { CloseDaySummary, StaffSlot } from './types'

export const staffSlotApi = {
  list: (date: string) => http.get<StaffSlot[]>(`/hospital/slots?date=${date}`),
  close: (slotId: number) => http.patch<void>(`/hospital/slots/${slotId}/close`),
  reopen: (slotId: number) => http.patch<void>(`/hospital/slots/${slotId}/reopen`),
  closeDay: (date: string) =>
    http.patch<CloseDaySummary>(`/hospital/slots/close-day?date=${date}`),
}
