// 병원 스태프 운영 계약(진료시간·진료역량·임시휴진). SA §8-8, /api/hospital/**.
import type { CapabilityValue } from '@/types/enums'

/*
  슬롯 발행창 — 백엔드가 오늘부터 이 일수만큼 뒤까지 슬롯을 발행해 둔다
  (HospitalSlotGenerationScheduler.PUBLISHED_RANGE_LAST_DAY_OFFSET,
  HospitalOperatingHoursApplicationService의 today.plusDays(13)와 같은 값).
  진료시간·임시휴진 화면이 각자 하드코딩하면 서로 어긋나므로 여기 한 번만 둔다.
*/
export const SLOT_PUBLICATION_DAYS = 13

// 백엔드 java.time.DayOfWeek 이름을 그대로 쓴다.
export const DayOfWeek = {
  MONDAY: 'MONDAY',
  TUESDAY: 'TUESDAY',
  WEDNESDAY: 'WEDNESDAY',
  THURSDAY: 'THURSDAY',
  FRIDAY: 'FRIDAY',
  SATURDAY: 'SATURDAY',
  SUNDAY: 'SUNDAY',
} as const
export type DayOfWeek = (typeof DayOfWeek)[keyof typeof DayOfWeek]

// 요청 days는 7개 전부 보내야 한다(@Size(min=7,max=7) + 요일 중복·누락 검사).
export const DAY_ORDER: DayOfWeek[] = [
  DayOfWeek.MONDAY,
  DayOfWeek.TUESDAY,
  DayOfWeek.WEDNESDAY,
  DayOfWeek.THURSDAY,
  DayOfWeek.FRIDAY,
  DayOfWeek.SATURDAY,
  DayOfWeek.SUNDAY,
]

export const DAY_LABEL: Record<DayOfWeek, string> = {
  MONDAY: '월요일',
  TUESDAY: '화요일',
  WEDNESDAY: '수요일',
  THURSDAY: '목요일',
  FRIDAY: '금요일',
  SATURDAY: '토요일',
  SUNDAY: '일요일',
}

// 시각은 "HH:mm"(백엔드 LocalTime). 초까지 오는 경우가 있어 화면에서는 앞 5자만 쓴다.
export interface OperatingPeriod {
  startTime: string
  endTime: string
}

export interface DailyOperatingHours {
  dayOfWeek: DayOfWeek
  // 빈 배열이면 그 요일은 휴무다.
  periods: OperatingPeriod[]
}

export interface OperatingHours {
  scheduleId: number
  // 예정 시간표 수정의 낙관적 비교 기준. 서버는 이 값이 달라지면 409을 돌려준다.
  updatedAt: string
  effectiveFrom: string
  days: DailyOperatingHours[]
}

// 요청한 발효일과 서버가 확정한 발효일은 다를 수 있다(발행창 안에 예약이 있으면 뒤로 밀린다).
export interface OperatingHoursUpdateRequest {
  desiredEffectiveFrom: string
  saveMode: 'CREATE' | 'UPDATE'
  targetScheduleId?: number
  expectedUpdatedAt?: string
  days: DailyOperatingHours[]
}

export interface HospitalCapabilities {
  capabilities: CapabilityValue[]
}

export interface TemporaryClosure {
  businessDate: string
}
