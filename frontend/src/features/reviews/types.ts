// 병원 후기 도메인 타입 (백엔드 review 도메인 계약 기준, 이슈 #114·PR #129).
// rating은 BigDecimal이지만 JSON에는 숫자로 내려온다(실연동 확인: 4.5).

// 병원별 후기 목록 항목. 작성자 식별 정보가 없다(익명) — 내 후기를 목록에서 골라낼 수 없다.
export interface HospitalReviewItem {
  reviewId: number
  rating: number
  content: string
  createdAt: string
  updatedAt: string
}

// GET /api/hospitals/{hospitalId}/reviews (page 1-base).
export interface ReviewPage {
  content: HospitalReviewItem[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  first: boolean
  last: boolean
}

// 작성·수정 요청. 평점은 1.0~5.0의 0.5 단위, 내용은 300자 이하(둘 다 필수).
export interface ReviewInput {
  rating: number
  content: string
}

// 작성·수정 응답(ReviewResponse). 목록 항목과 달리 소유 정보까지 포함한다.
export interface Review {
  reviewId: number
  reservationId: number
  hospitalId: number
  memberId: number
  rating: number
  content: string
  createdAt: string
  updatedAt: string
}

// 평점 선택지 — 백엔드가 0.5 단위만 허용하므로(@AssertTrue) 화면에서도 그 값만 고르게 한다.
export const RATING_OPTIONS = [5, 4.5, 4, 3.5, 3, 2.5, 2, 1.5, 1] as const

export const REVIEW_CONTENT_MAX = 300
