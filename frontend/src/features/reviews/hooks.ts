// 병원 후기 쿼리·mutation 훅.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { reviewApi } from './api'
import type { ReviewInput } from './types'
import {
  clearMyReview,
  markMyReviewDeleted,
  saveMyReview,
} from './myReviewCache'
import { hospitalKeys } from '@/features/hospitals/hooks'
import { ApiError } from '@/lib/api/error'

export const reviewKeys = {
  all: ['reviews'] as const,
  hospital: (hospitalId: number, page: number) =>
    ['reviews', 'hospital', hospitalId, page] as const,
}

export function useHospitalReviews(
  hospitalId: number,
  page: number,
  size = 5,
) {
  return useQuery({
    queryKey: reviewKeys.hospital(hospitalId, page),
    queryFn: () => reviewApi.listByHospital(hospitalId, page, size),
    enabled: Number.isFinite(hospitalId),
  })
}

// 후기가 바뀌면 목록과 병원 상세(평균 평점·후기 수 집계)를 함께 새로 받는다.
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
    onSuccess: (review) => {
      // 서버가 내 후기를 되찾아줄 경로가 없어 여기서 캐시해 둔다(myReviewCache 주석 참고).
      saveMyReview(review)
      invalidate()
    },
  })
}

export function useUpdateReview(reservationId: number) {
  const invalidate = useInvalidateReviewViews()
  return useMutation({
    mutationFn: ({ reviewId, input }: { reviewId: number; input: ReviewInput }) =>
      reviewApi.update(reviewId, input),
    onSuccess: (review) => {
      saveMyReview(review)
      invalidate()
    },
    onError: (error) => {
      // 캐시가 낡아 서버에 없는/남의 후기를 가리켰다면 캐시를 버려 작성 폼으로 되돌린다.
      if (isStaleCacheError(error)) clearMyReview(reservationId)
    },
  })
}

export function useDeleteReview(reservationId: number) {
  const invalidate = useInvalidateReviewViews()
  return useMutation({
    mutationFn: (reviewId: number) => reviewApi.remove(reviewId),
    onSuccess: () => {
      // 지워도 작성 기회는 돌아오지 않으므로 표식을 남긴다(재작성 폼을 막는다).
      markMyReviewDeleted(reservationId)
      invalidate()
    },
    onError: (error) => {
      if (isStaleCacheError(error)) clearMyReview(reservationId)
    },
  })
}

// REVIEW_005(후기 없음)·REVIEW_006(작성자 아님)은 캐시가 실제 상태와 어긋났다는 신호다
// — 환불로 후기가 지워졌거나 다른 계정으로 로그인한 경우. 네트워크 오류와 구분해야 하므로
// 상태코드가 아니라 백엔드 ErrorCode로 판정한다.
const STALE_CACHE_CODES = new Set(['REVIEW_005', 'REVIEW_006'])

function isStaleCacheError(error: unknown): boolean {
  return error instanceof ApiError && STALE_CACHE_CODES.has(error.code)
}
