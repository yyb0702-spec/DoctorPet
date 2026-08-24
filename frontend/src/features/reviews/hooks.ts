// 병원 후기 쿼리·mutation 훅.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { reviewApi } from './api'
import type { ReviewInput } from './types'
import { hospitalKeys } from '@/features/hospitals/hooks'

export const reviewKeys = {
  all: ['reviews'] as const,
  // 조회 파라미터를 모두 키에 넣는다 — size가 빠지면 같은 병원·페이지를 다른 크기로 부르는
  // 호출이 생겼을 때 이전 크기의 응답을 그대로 재사용한다(PR #189 리뷰 P2).
  hospital: (hospitalId: number, page: number, size: number) =>
    ['reviews', 'hospital', hospitalId, page, size] as const,
  mine: (reservationId: number) => ['reviews', 'mine', reservationId] as const,
}

export function useHospitalReviews(
  hospitalId: number,
  page: number,
  size = 5,
) {
  return useQuery({
    queryKey: reviewKeys.hospital(hospitalId, page, size),
    queryFn: () => reviewApi.listByHospital(hospitalId, page, size),
    enabled: Number.isFinite(hospitalId),
  })
}

// 이 예약의 내 후기 상태. 서버가 정본이라 화면 상태를 브라우저에 따로 캐시하지 않는다.
export function useMyReview(reservationId: number) {
  return useQuery({
    queryKey: reviewKeys.mine(reservationId),
    queryFn: () => reviewApi.getMine(reservationId),
    enabled: Number.isFinite(reservationId),
  })
}

// 후기가 바뀌면 내 후기 상태·목록과 병원 상세(평균 평점·후기 수 집계)를 함께 새로 받는다.
// 실패했을 때도 새로 받는다 — 서버가 거절한 이유(이미 작성했다·자격이 사라졌다)가 곧 화면 상태다.
function useInvalidateReviewViews() {
  const queryClient = useQueryClient()
  return () => {
    queryClient.invalidateQueries({ queryKey: reviewKeys.all })
    queryClient.invalidateQueries({ queryKey: hospitalKeys.all })
  }
}

export function useCreateReview(reservationId: number) {
  const invalidate = useInvalidateReviewViews()
  return useMutation({
    mutationFn: (input: ReviewInput) => reviewApi.create(reservationId, input),
    onSettled: invalidate,
  })
}

export function useUpdateReview() {
  const invalidate = useInvalidateReviewViews()
  return useMutation({
    mutationFn: ({ reviewId, input }: { reviewId: number; input: ReviewInput }) =>
      reviewApi.update(reviewId, input),
    onSettled: invalidate,
  })
}

export function useDeleteReview() {
  const invalidate = useInvalidateReviewViews()
  return useMutation({
    mutationFn: (reviewId: number) => reviewApi.remove(reviewId),
    onSettled: invalidate,
  })
}
