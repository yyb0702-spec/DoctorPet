// 백엔드 공통 응답 계약 (SA §7). 성공 code="SUCCESS", 실패 시 data=null.
export interface ApiResponse<T> {
  code: string
  message: string
  data: T | null
}

// 페이지네이션 응답 (Spring Data Page 형태). 검색 등에서 사용.
export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  first: boolean
  last: boolean
}
