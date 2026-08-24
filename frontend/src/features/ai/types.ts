// AI 증상 상담 타입 (실연동, POST /api/ai/consultations, PRD §7 구조화 5필드).
import type { HospitalSummary } from '@/features/hospitals/types'
import type { PetSpecies } from '@/types/enums'

// 응급도 (UrgencyLevel).
export type UrgencyLevel = 'LOW' | 'MODERATE' | 'HIGH'

// 요청. species 필수, 위경도는 함께 있거나 함께 없어야 한다(백엔드 AssertTrue).
export interface AiConsultationInput {
  symptomText: string // 필수, 최대 1000자
  species: PetSpecies
  region?: string
  latitude?: number
  longitude?: number
}

// 구조화 출력 5필드 (AiStructuredResult).
export interface AiStructuredResult {
  possibleFocusAreas: string[] // 의심 부위/계통
  requiredCapabilities: string[] // 필요 진료역량(CapabilityValue 코드)
  urgencyLevel: UrgencyLevel
  preVisitCheckpoints: string[] // 방문 전 확인 사항
  recommendVetVisit: boolean // 병원 방문 권장 여부
}

// AI 추천 근거 한 조각 (AiRecommendationEvidenceResult).
export interface AiRecommendationEvidence {
  type:
    | 'SUPPORTED_SPECIES'
    | 'CAPABILITY'
    | 'DISTANCE_KM'
    | 'BUSINESS_STATUS'
    | 'OPEN_NOW'
    | 'AVERAGE_RATING'
    | 'REVIEW_COUNT'
    | 'POSITIVE_REVIEW_COUNT'
    | 'NEUTRAL_REVIEW_COUNT'
    | 'NEGATIVE_REVIEW_COUNT'
    | 'REVIEW_EXCERPT_ID'
  value: string
}

// AI가 선별한 맞춤 추천 병원 한 건 (AiHospitalRecommendationResponse). hospital은 검색과 동일 계약,
// recommendationScore·recommendationReason·evidence로 "왜 추천했는지"를 함께 준다.
export interface AiHospitalRecommendation {
  hospital: HospitalSummary
  recommendationScore: number
  recommendationReason: string
  evidence: AiRecommendationEvidence[]
}

// 응답 (AiConsultationResponse). hospitals는 병원 검색과 동일 계약이라 HospitalSummary 재사용.
export interface AiConsultationResult {
  structured: AiStructuredResult
  hospitals: HospitalSummary[]
  // AI가 선별한 맞춤 추천 병원(이유·근거 포함). 위치/지역이 없어 추천을 못 하면 빈 배열이다.
  recommendations: AiHospitalRecommendation[]
  disclaimer: string
  message: string
  fallback: boolean // 검색 Tool 실패 등으로 축소 응답인지
  locationRequired: boolean // 추천에 위치/지역이 반드시 필요한데 없음(추천 불가 신호)
  locationRecommended: boolean // 위치 입력 권장 신호(있으면 더 정확)
}
