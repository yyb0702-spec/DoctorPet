// 예약 상세의 후기 카드 — 결제 완료된 진료에 후기를 쓰고, 쓴 후기를 수정·삭제한다.
// 카드를 띄울지는 호출부(ReservationDetailPage)가 결제 상태로 거르고, 실제 작성 자격과 내 후기는
// 서버(GET /api/reservations/{id}/review, SA §8-3)가 정본이다 — 브라우저에 상태를 캐시하지 않는다.
import { useState } from 'react'
import {
  useCreateReview,
  useDeleteReview,
  useMyReview,
  useUpdateReview,
} from './hooks'
import { RATING_OPTIONS, REVIEW_CONTENT_MAX } from './types'
import { RatingStars } from './HospitalReviewList'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Select } from '@/components/ui/select'
import { ErrorState, PageLoader } from '@/components/common/States'
import { cn } from '@/lib/utils'
import { ApiError } from '@/lib/api/error'

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback
}

// 평점·내용 입력 폼(작성·수정 공용).
function ReviewForm({
  initialRating,
  initialContent,
  submitLabel,
  pending,
  errorText,
  onSubmit,
  onCancel,
}: {
  initialRating: number
  initialContent: string
  submitLabel: string
  pending: boolean
  errorText: string | null
  onSubmit: (rating: number, content: string) => void
  onCancel?: () => void
}) {
  const [rating, setRating] = useState(initialRating)
  const [content, setContent] = useState(initialContent)

  const trimmed = content.trim()
  const overLimit = content.length > REVIEW_CONTENT_MAX
  const canSubmit = trimmed.length > 0 && !overLimit && !pending

  return (
    <div className="space-y-3">
      <div className="space-y-1.5">
        <label htmlFor="review-rating" className="text-sm font-medium">
          평점
        </label>
        <div className="flex items-center gap-2">
          <Select
            id="review-rating"
            className="w-28"
            value={rating}
            onChange={(e) => setRating(Number(e.target.value))}
          >
            {RATING_OPTIONS.map((r) => (
              <option key={r} value={r}>
                {r.toFixed(1)}점
              </option>
            ))}
          </Select>
          <RatingStars rating={rating} />
        </div>
      </div>

      <div className="space-y-1.5">
        <label htmlFor="review-content" className="text-sm font-medium">
          후기
        </label>
        <textarea
          id="review-content"
          value={content}
          onChange={(e) => setContent(e.target.value)}
          rows={4}
          placeholder="진료는 어땠는지 다른 보호자에게 알려주세요."
          className="w-full resize-none rounded-md border bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        />
        <p
          className={cn(
            'text-right text-xs',
            overLimit ? 'text-destructive' : 'text-muted-foreground',
          )}
        >
          {content.length} / {REVIEW_CONTENT_MAX}
        </p>
      </div>

      {errorText && <p className="text-sm text-destructive">{errorText}</p>}

      <div className="flex gap-2">
        {onCancel && (
          <Button variant="outline" disabled={pending} onClick={onCancel}>
            취소
          </Button>
        )}
        <Button
          className="flex-1"
          disabled={!canSubmit}
          onClick={() => onSubmit(rating, trimmed)}
        >
          {pending ? '저장 중…' : submitLabel}
        </Button>
      </div>
    </div>
  )
}

// 예약이 바뀌면 편집·요청 상태를 남기지 않고 통째로 다시 만든다. 같은 인스턴스가 재사용되면
// 이전 예약의 후기와 수정·삭제 버튼이 남아, 서버는 같은 작성자의 후기라 요청을 받아들이므로
// 다른 예약의 후기를 실제로 고치거나 지울 수 있다(PR #189 리뷰 P1).
export function ReservationReviewCard({
  reservationId,
}: {
  reservationId: number
}) {
  return <ReviewCardBody key={reservationId} reservationId={reservationId} />
}

function ReviewCardBody({ reservationId }: { reservationId: number }) {
  const [editing, setEditing] = useState(false)

  const myReviewQuery = useMyReview(reservationId)
  const create = useCreateReview(reservationId)
  const update = useUpdateReview()
  const remove = useDeleteReview()

  if (myReviewQuery.isPending) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>후기</CardTitle>
        </CardHeader>
        <CardContent>
          <PageLoader label="후기 불러오는 중…" />
        </CardContent>
      </Card>
    )
  }

  if (myReviewQuery.isError || !myReviewQuery.data) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>후기</CardTitle>
        </CardHeader>
        <CardContent>
          <ErrorState onRetry={() => myReviewQuery.refetch()} />
        </CardContent>
      </Card>
    )
  }

  const { review: myReview, reviewable } = myReviewQuery.data

  if (myReview && !editing) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>내가 남긴 후기</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="flex items-center gap-2">
            <RatingStars rating={myReview.rating} />
            <span className="text-sm font-medium">
              {myReview.rating.toFixed(1)}
            </span>
          </div>
          <p className="whitespace-pre-wrap break-words text-sm">
            {myReview.content}
          </p>
          {remove.isError && (
            <p className="text-sm text-destructive">
              {errorMessage(remove.error, '후기 삭제에 실패했습니다.')}
            </p>
          )}
          <div className="flex gap-2">
            <Button
              size="sm"
              variant="outline"
              disabled={remove.isPending}
              onClick={() => setEditing(true)}
            >
              수정
            </Button>
            <Button
              size="sm"
              variant="destructive"
              disabled={remove.isPending}
              onClick={() => remove.mutate(myReview.reviewId)}
            >
              {remove.isPending ? '삭제 중…' : '삭제'}
            </Button>
          </div>
          {/* 삭제하면 예약의 작성 기회(reviewed_at)가 복구되지 않아 다시 쓸 수 없다 — 미리 알린다. */}
          <p className="text-xs text-muted-foreground">
            삭제한 후기는 되돌릴 수 없고, 이 진료에 후기를 다시 쓸 수 없습니다.
          </p>
        </CardContent>
      </Card>
    )
  }

  if (myReview && editing) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>후기 수정</CardTitle>
        </CardHeader>
        <CardContent>
          <ReviewForm
            initialRating={myReview.rating}
            initialContent={myReview.content}
            submitLabel="수정 저장"
            pending={update.isPending}
            errorText={
              update.isError
                ? errorMessage(update.error, '후기 수정에 실패했습니다.')
                : null
            }
            onCancel={() => {
              update.reset()
              setEditing(false)
            }}
            onSubmit={(rating, content) =>
              update.mutate(
                { reviewId: myReview.reviewId, input: { rating, content } },
                { onSuccess: () => setEditing(false) },
              )
            }
          />
        </CardContent>
      </Card>
    )
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>후기 작성</CardTitle>
      </CardHeader>
      <CardContent>
        {reviewable ? (
          <ReviewForm
            initialRating={5}
            initialContent=""
            submitLabel="후기 등록"
            pending={create.isPending}
            errorText={
              create.isError
                ? errorMessage(create.error, '후기 등록에 실패했습니다.')
                : null
            }
            onSubmit={(rating, content) => create.mutate({ rating, content })}
          />
        ) : (
          /* 후기가 없는데 작성도 불가하면 작성 기회를 이미 썼다는 뜻이다(쓴 뒤 지운 경우).
             환불 뒤 정정 재청구로 자격이 돌아오면 서버가 reviewable을 다시 true로 준다. */
          <p className="text-sm text-muted-foreground">
            이 진료의 후기 작성 기회는 이미 사용했습니다. 삭제한 후기는 다시 쓸
            수 없습니다.
          </p>
        )}
      </CardContent>
    </Card>
  )
}
