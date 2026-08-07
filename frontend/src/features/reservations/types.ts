// 예약 도메인 타입.
import type {
  PaymentStatus,
  ReservationProgressStatus,
  ReservationStatus,
} from '@/types/enums'

// POST /api/reservations 요청 (실연동).
// 주의: 백엔드는 SA §8-5와 달리 스냅샷(petNameSnapshot·petSpeciesSnapshot)도 필수로 받는다.
export interface ReservationRequestInput {
  petId: number
  slotId: number
  paymentMethodId: number
  petNameSnapshot: string
  petSpeciesSnapshot: string // "DOG" / "CAT" 문자열 스냅샷(enum 아님)
  visitReason?: string // 방문 사유(선택). 병원 스태프 화면에 그대로 노출된다.
}

// ReservationResponse (실연동, 요청 성공 응답).
export interface ReservationResponse {
  reservationId: number
  petId: number
  hospitalId: number
  slotId: number
  status: ReservationStatus
  requestedAt: string
}

// --- 예약 목록/상세 (실연동, PR #68) ---

// GET /api/reservations 쿼리 파라미터.
export interface ReservationListParams {
  status?: string
  from?: string // yyyy-MM-dd
  to?: string // yyyy-MM-dd
  page?: number // 0-base (기본 0)
  size?: number // 기본 20
  sort?: string // 기본 "reservedAt,desc"
}

// ReservationListItemResponse.
export interface ReservationListItem {
  reservationId: number
  hospitalId: number
  hospitalName: string
  petId: number
  petName: string
  reservedAt: string // 슬롯 시작 일시
  reservationStatus: ReservationStatus
  paymentStatus: PaymentStatus | null // 문자열(enum name) | null
  progressStatus: ReservationProgressStatus
}

// ReservationDetailResponse (중첩 구조).
export interface ReservationDetail {
  reservationId: number
  reservationStatus: ReservationStatus
  paymentStatus: PaymentStatus | null
  progressStatus: ReservationProgressStatus
  hospital: {
    hospitalId: number
    name: string
    address: string
    phoneNumber: string | null
  }
  petSnapshot: {
    petId: number
    name: string
    species: string // "DOG" / "CAT"
  }
  slot: {
    slotId: number
    startAt: string
    endAt: string
  }
  rejectionReason: string | null
  visitReason: string | null
  createdAt: string
  updatedAt: string
}
