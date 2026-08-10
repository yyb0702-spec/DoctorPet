// 병원 스태프 슬롯 관리 타입 (SA §9-9, /api/hospital/slots). 보호자용 조회와 달리 CLOSED도 그대로 온다.
export type StaffSlotStatus = 'OPEN' | 'RESERVED' | 'CLOSED'

export interface StaffSlot {
  slotId: number
  startAt: string
  endAt: string
  status: StaffSlotStatus
}

export interface CloseDaySummary {
  closed: number
  skippedDueToReservation: number
}
