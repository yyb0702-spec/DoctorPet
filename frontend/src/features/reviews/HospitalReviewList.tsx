// 병원 상세의 보호자 후기 목록 — 평균 평점 + 후기 카드 + 페이지네이션.
import { useState } from 'react'
import { Star } from 'lucide-react'
import { useHospitalReviews } from './hooks'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { EmptyState, ErrorState, PageLoader } from '@/components/common/States'

const PAGE_SIZE = 5

// 평점을 별 5개로 표시한다. 0.5 단위라 반 칸은 너비를 절반만 칠해 표현한다.
export function RatingStars({
  rating,
  className,
}: {
  rating: number
  className?: string
}) {
  return (
    <span className={className} aria-label={`평점 ${rating}점 (5점 만점)`}>
      <span className="relative inline-flex align-middle">
        {/* 바탕(빈 별) */}
        <span className="inline-flex">
          {[0, 1, 2, 3, 4].map((i) => (
            <Star key={i} className="h-4 w-4 text-muted-foreground/30" />
          ))}
        </span>
        {/* 채운 별을 평점 비율만큼만 잘라 덮는다. */}
        <span
          className="absolute left-0 top-0 inline-flex overflow-hidden"
          style={{ width: `${(rating / 5) * 100}%` }}
        >
          {[0, 1, 2, 3, 4].map((i) => (
            <Star
              key={i}
              className="h-4 w-4 shrink-0 fill-amber-400 text-amber-400"
            />
          ))}
        </span>
      </span>
    </span>
  )
}

function reviewDateLabel(iso: string): string {
  return new Date(iso).toLocaleDateString('ko-KR', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  })
}

interface HospitalReviewListProps {
  hospitalId: number
  // 병원 상세 응답의 집계값. 후기가 없으면 averageRating은 null이다.
  averageRating: number | null
  reviewCount: number
}

// 병원이 바뀌면 페이지 상태를 남기지 않고 통째로 다시 만든다. /hospitals/:hospitalId 사이를
// 이동하면 같은 인스턴스가 재사용되어, 이전 병원에서 보던 3페이지를 새 병원에 그대로 요청해
// 후기가 있는데도 빈 목록으로 보일 수 있다(PR #189 리뷰 P2).
export function HospitalReviewList(props: HospitalReviewListProps) {
  return <ReviewListBody key={props.hospitalId} {...props} />
}

function ReviewListBody({
  hospitalId,
  averageRating,
  reviewCount,
}: HospitalReviewListProps) {
  const [page, setPage] = useState(1)
  const { data, isLoading, isError, refetch, isFetching } = useHospitalReviews(
    hospitalId,
    page,
    PAGE_SIZE,
  )
  const reviews = data?.content ?? []

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex flex-wrap items-center gap-2">
          보호자 후기
          {averageRating != null && (
            <span className="flex items-center gap-1.5 text-sm font-normal">
              <RatingStars rating={averageRating} />
              <span className="font-semibold">{averageRating.toFixed(1)}</span>
              <span className="text-muted-foreground">({reviewCount})</span>
            </span>
          )}
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        {isLoading && <PageLoader label="후기 불러오는 중…" />}
        {isError && <ErrorState onRetry={() => refetch()} />}
        {data && reviews.length === 0 && (
          <EmptyState message="아직 등록된 후기가 없습니다." />
        )}

        {reviews.map((r) => (
          <div
            key={r.reviewId}
            className="space-y-1 border-b pb-3 last:border-b-0 last:pb-0"
          >
            <div className="flex items-center gap-2">
              <RatingStars rating={r.rating} />
              <span className="text-sm font-medium">{r.rating.toFixed(1)}</span>
              <span className="text-xs text-muted-foreground">
                {reviewDateLabel(r.createdAt)}
                {/* 수정된 후기는 그 사실만 알린다(수정 시각은 별도로 노출하지 않음). */}
                {r.updatedAt !== r.createdAt && ' · 수정됨'}
              </span>
            </div>
            {/* 후기는 사용자 입력이라 줄바꿈을 살리고 긴 단어는 강제 개행한다. */}
            <p className="whitespace-pre-wrap break-words text-sm">{r.content}</p>
          </div>
        ))}

        {data && data.totalPages > 1 && (
          <div className="flex items-center justify-center gap-3 pt-1">
            <Button
              size="sm"
              variant="outline"
              disabled={data.first || isFetching}
              onClick={() => setPage((p) => p - 1)}
            >
              이전
            </Button>
            <span className="text-sm text-muted-foreground">
              {data.page} / {data.totalPages}
            </span>
            <Button
              size="sm"
              variant="outline"
              disabled={data.last || isFetching}
              onClick={() => setPage((p) => p + 1)}
            >
              다음
            </Button>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
