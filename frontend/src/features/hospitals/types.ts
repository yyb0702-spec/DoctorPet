// 병원 도메인 타입.
// 상세·검색·슬롯 모두 백엔드 실제 필드 기준(실연동).
import type {
  CapabilityValue,
  PartnershipStatus,
  PetSpecies,
} from '@/types/enums'

export type BusinessStatus = 'OPEN' | 'CLOSED_TEMP' | 'CLOSED'

export type DayOfWeek =
  | 'MONDAY'
  | 'TUESDAY'
  | 'WEDNESDAY'
  | 'THURSDAY'
  | 'FRIDAY'
  | 'SATURDAY'
  | 'SUNDAY'

export interface BusinessHour {
  dayOfWeek: DayOfWeek
  closed: boolean
  openTime: string | null // "HH:mm"
  closeTime: string | null // "HH:mm"
}

// GET /api/hospitals/{hospitalId} (실연동)
export interface HospitalDetail {
  hospitalId: number
  name: string
  address: string
  phoneNumber: string | null
  businessStatus: BusinessStatus
  partnershipStatus: PartnershipStatus
  partnershipNotice: string | null // 비제휴 안내문(제휴면 null)
  // 아래 상세 필드들은 비제휴 병원이면 모두 null.
  openNow: boolean | null
  surgeryAvailable: boolean | null
  hospitalizationAvailable: boolean | null
  nightCare: boolean | null
  emergency: boolean | null
  businessHours: BusinessHour[] | null
  capabilities: CapabilityValue[] | null
  // 제휴 병원 응답 지표(PR #165). 비제휴거나 집계할 예약이 없으면 null이라 표시하지 않는다.
  reservationResponseRate: number | null // 예약 응답률(%), 0~100
  averageApprovalMinutes: number | null // 평균 승인 소요(분)
}

// GET /api/hospitals (검색, 실연동). HospitalSearchResponse 실제 필드 기준.
// 검색 응답에는 진료역량 태그(capabilities)가 없다 — 상세에서만 제공된다.
export interface HospitalSummary {
  hospitalId: number
  name: string
  address: string
  distanceKm: number | null // 좌표 미제공 시 null
  businessStatus: BusinessStatus
  partnershipStatus: PartnershipStatus
  reservationAvailable: boolean // 제휴 + 영업중일 때만 true
  partnershipBadge: string | null // "제휴 전 병원" | null
  openNow: boolean | null // 제휴 병원만, 그 외 null
}

// 검색 쿼리 파라미터 (HospitalController.searchHospitals 기준).
export interface HospitalSearchParams {
  keyword?: string
  region?: string
  latitude?: number
  longitude?: number
  radiusKm?: number
  requiredCapabilities?: CapabilityValue[]
  supportedSpecies?: PetSpecies[] // DOG/CAT (복수)
  surgery?: boolean
  hospitalization?: boolean
  nightCare?: boolean
  emergency?: boolean
  partnerOnly?: boolean
  openNow?: boolean
  page?: number // 1-base (기본 1)
  size?: number // 기본 20, 최대 100
  sort?: string // 기본 "name"
}

// --- 예약 슬롯 (실연동) ---
// GET /api/hospitals/{hospitalId}/slots?date=yyyy-MM-dd → HospitalSlotLookupResponse

// AVAILABLE: 예약 가능 / RESERVED: 이미 점유 / LEAD_TIME_CLOSED: 리드타임 마감(임박).
export type SlotAvailabilityStatus = 'AVAILABLE' | 'RESERVED' | 'LEAD_TIME_CLOSED'

export interface Slot {
  slotId: number
  startAt: string // LocalDateTime "yyyy-MM-ddTHH:mm:ss" (타임존 없음 → 로컬 시각으로 해석)
  endAt: string
  availabilityStatus: SlotAvailabilityStatus
}

// 날짜 선택 캘린더용 — 백엔드가 오늘부터 14일치를 준다.
export interface DateAvailability {
  date: string // yyyy-MM-dd
  reservationAvailable: boolean // 그 날짜에 AVAILABLE 슬롯이 하나라도 있으면 true
}

export interface HospitalSlotLookup {
  selectedDate: string // yyyy-MM-dd
  dateAvailabilities: DateAvailability[]
  slots: Slot[] // selectedDate의 슬롯 (범위 밖이거나 비제휴면 빈 배열)
}
