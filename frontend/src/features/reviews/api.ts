// 병원 후기 API. 목록은 미인증도 조회 가능하고, 내 후기 조회·작성·수정·삭제는 보호자 인증이 필요하다.
import { http } from '@/lib/api/client'
import type { MyReview, Review, ReviewInput, ReviewPage } from './types'

export const reviewApi = {
  // 병원별 후기 목록. page는 1-base(백엔드 @Min(1)), size는 최대 100.
  listByHospital: (hospitalId: number, page: number, size: number) =>
    http.get<ReviewPage>(
      `/hospitals/${hospitalId}/reviews?page=${page}&size=${size}`,
    ),

  // 이 예약에 내가 쓴 후기와 지금 작성할 수 있는지 여부(SA §8-3, PR #190).
  // 예약 본인만 조회할 수 있고(아니면 403 REVIEW_002), 목록은 익명이라 이 경로가 유일한 정본이다.
  getMine: (reservationId: number) =>
    http.get<MyReview>(`/reservations/${reservationId}/review`),

  // 결제 완료(PAID·OFFLINE_PAID) 예약의 본인만, 예약당 1회. 중복이면 409 REVIEW_004.
  create: (reservationId: number, input: ReviewInput) =>
    http.post<Review>(`/reservations/${reservationId}/reviews`, input),

  // 작성자만 가능(아니면 403 REVIEW_006). 수정 기한 제한은 없다.
  update: (reviewId: number, input: ReviewInput) =>
    http.put<Review>(`/reviews/${reviewId}`, input),

  // 삭제해도 예약의 후기 작성 기회(reservations.reviewed_at)는 복구되지 않는다 — 재작성 불가.
  remove: (reviewId: number) => http.delete<void>(`/reviews/${reviewId}`),
}
