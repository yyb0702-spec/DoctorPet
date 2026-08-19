// 병원 후기 API. 목록은 미인증도 조회 가능하고, 작성·수정·삭제는 보호자 인증이 필요하다.
// 백엔드에 "내 후기 조회"(GET) 엔드포인트가 없어, 작성 후 수정·삭제는 myReviewCache가 보조한다.
import { http } from '@/lib/api/client'
import type { Review, ReviewInput, ReviewPage } from './types'

export const reviewApi = {
  // 병원별 후기 목록. page는 1-base(백엔드 @Min(1)), size는 최대 100.
  listByHospital: (hospitalId: number, page: number, size: number) =>
    http.get<ReviewPage>(
      `/hospitals/${hospitalId}/reviews?page=${page}&size=${size}`,
    ),

  // 결제 완료(PAID·OFFLINE_PAID) 예약의 본인만, 예약당 1회. 중복이면 409 REVIEW_004.
  create: (reservationId: number, input: ReviewInput) =>
    http.post<Review>(`/reservations/${reservationId}/reviews`, input),

  // 작성자만 가능(아니면 403 REVIEW_006). 수정 기한 제한은 없다.
  update: (reviewId: number, input: ReviewInput) =>
    http.put<Review>(`/reviews/${reviewId}`, input),

  // 삭제해도 예약의 후기 작성 기회(reservations.reviewed_at)는 복구되지 않는다 — 재작성 불가.
  remove: (reviewId: number) => http.delete<void>(`/reviews/${reviewId}`),
}
