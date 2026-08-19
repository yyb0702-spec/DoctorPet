// 예약 상세의 후기 카드 — 결제 완료된 진료에 후기를 쓰고, 쓴 후기를 수정·삭제한다.
// 노출 조건은 호출부(ReservationDetailPage)가 결제 상태로 판단한다.
import { useState } from 'react'
import { useCreateReview, useDeleteReview, useUpdateReview } from './hooks'
import { getMyReview, isReviewOpportunityUsed } from './myReviewCache'
import { RATING_OPTIONS, REVIEW_CONTENT_MAX } from './types'
import { RatingStars } from './HospitalReviewList'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Select } from '@/components/ui/select'
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

export function ReservationReviewCard({
  reservationId,
}: {
  reservationId: number
}) {
  // 내 후기는 서버에서 되찾을 수 없어 캐시로 판단한다(myReviewCache 주석 참고).
  // 캐시가 낡으면 mutation 에러 처리에서 정리되므로, 그 변화를 반영하려고 상태로 들고 있는다.
  const [myReview, setMyReview] = useState(() => getMyReview(reservationId))
  const [deletedByMe, setDeletedByMe] = useState(() =>
    isReviewOpportunityUsed(reservationId),
  )
  const [editing, setEditing] = useState(false)

  const create = useCreateReview(reservationId)
  const update = useUpdateReview(reservationId)
  const remove = useDeleteReview(reservationId)

  // 캐시를 갱신·정리한 뒤 화면 상태를 다시 읽어 맞춘다.
  const syncFromCache = () => {
    setMyReview(getMyReview(reservationId))
    setDeletedByMe(isReviewOpportunityUsed(reservationId))
  }

  // 이미 작성한 예약인데 캐시가 없는 기기에서 작성하면 서버가 409로 막는다 — 이 경우
  // "작성 기회를 이미 썼다"는 사실만 알려주고 폼을 닫는다(수정할 후기를 특정할 수 없다).
  // 내가 이 브라우저에서 지웠다면 서버에 물어볼 필요 없이 같은 안내를 바로 보여준다.
  const alreadyReviewed =
    deletedByMe ||
    (create.error instanceof ApiError && create.error.code === 'REVIEW_004')

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
              onClick={() =>
                remove.mutate(myReview.reviewId, { onSettled: syncFromCache })
              }
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
                {
                  onSuccess: () => setEditing(false),
                  onSettled: syncFromCache,
                },
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
        {alreadyReviewed ? (
          <p className="text-sm text-muted-foreground">
            이 진료의 후기는 이미 작성했습니다. 후기를 쓴 기기에서 수정·삭제할 수
            있습니다.
          </p>
        ) : (
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
            onSubmit={(rating, content) =>
              create.mutate({ rating, content }, { onSettled: syncFromCache })
            }
          />
        )}
      </CardContent>
    </Card>
  )
}
