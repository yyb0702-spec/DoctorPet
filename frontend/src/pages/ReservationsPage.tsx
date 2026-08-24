// 내 예약 목록 (실연동, PR #68). 페이지네이션 + 상태 필터 + progressStatus 배지.
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useReservations } from '@/features/reservations/hooks'
import type { ReservationListParams } from '@/features/reservations/types'
import { usePets } from '@/features/pets/hooks'
import { PetAvatar } from '@/features/pets/PetAvatar'
import { ReservationProgressBadge } from '@/components/common/StatusBadge'
import { Card, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { EmptyState, ErrorState, PageLoader } from '@/components/common/States'
import { ReservationStatus } from '@/types/enums'
import { groupByDateKey, naiveTimeLabel } from '@/lib/seoulTime'

const PAGE_SIZE = 20

// 상태 필터 칩(백엔드 status 파라미터는 ReservationStatus 값).
const STATUS_FILTERS = [
  { value: undefined, label: '전체' },
  { value: ReservationStatus.REQUESTED, label: '승인 대기' },
  { value: ReservationStatus.CONFIRMED, label: '예약 확정' },
  { value: ReservationStatus.TREATMENT_COMPLETED, label: '진료 완료' },
  { value: ReservationStatus.CANCELED, label: '취소' },
] as const

// 오프셋 없는 시각 문자열에서 "M.D"를 그대로 읽는다. new Date로 파싱하면 브라우저 로컬 타임존으로
// 밀려 그룹 헤더(날짜 부분 슬라이스)와 어긋나므로, 카드도 같은 문자열 기준으로 표시한다.
function shortDate(dateTime: string): string {
  const [, month, day] = dateTime.slice(0, 10).split('-').map(Number)
  return `${month}.${day}`
}

export function ReservationsPage() {
  const [params, setParams] = useState<ReservationListParams>({
    page: 0,
    size: PAGE_SIZE,
  })
  const { data, isLoading, isError, refetch, isFetching } =
    useReservations(params)
  // 예약은 이름만 스냅샷으로 보관하므로, 사진은 내 활성 프로필 목록에서 현재 값을 결합한다.
  // 삭제된 프로필·아직 불러오는 경우에는 PetAvatar가 자리표시자를 보여준다.
  const petsQuery = usePets()

  const content = data?.content ?? []
  const petsById = new Map(
    (petsQuery.data ?? []).map((pet) => [pet.petId, pet]),
  )
  // 불러온 페이지 안에서만 날짜별로 묶어 최근 날짜부터 보여준다(오늘·내일·날짜 헤더).
  // 페이지 경계를 넘는 전체 순서는 서버 기본 정렬(reservedAt,desc)이 보장한다 — sort를 바꾸면
  // 여기 표시 순서가 서버 페이징과 어긋날 수 있다.
  const dateGroups = groupByDateKey(
    content,
    (r) => r.reservedAt,
    /* ascending */ false,
  )

  const setStatus = (status: string | undefined) =>
    setParams((p) => ({ ...p, status, page: 0 }))

  const goToPage = (next: number) => setParams((p) => ({ ...p, page: next }))

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">내 예약</h1>

      {/* 상태 필터 */}
      <div className="flex flex-wrap gap-2">
        {STATUS_FILTERS.map((f) => (
          <Button
            key={f.label}
            size="sm"
            variant={params.status === f.value ? 'default' : 'outline'}
            onClick={() => setStatus(f.value)}
          >
            {f.label}
          </Button>
        ))}
      </div>

      {isLoading && <PageLoader />}
      {isError && <ErrorState onRetry={() => refetch()} />}
      {data && content.length === 0 && (
        <EmptyState message="예약 내역이 없습니다." />
      )}

      <div className="space-y-5">
        {dateGroups.map((group) => (
          <div key={group.date} className="space-y-2">
            <h2 className="text-sm font-semibold text-muted-foreground">
              {group.label}
            </h2>
            <div className="grid gap-3">
              {group.items.map((r) => (
                <Card key={r.reservationId}>
                  <CardContent className="flex items-center gap-4 p-5">
                    <div className="flex flex-col items-center rounded-lg bg-primary/5 px-3 py-2 text-center">
                      <span className="text-lg font-bold text-primary">
                        {shortDate(r.reservedAt)}
                      </span>
                      <span className="text-xs text-muted-foreground">
                        {naiveTimeLabel(r.reservedAt)}
                      </span>
                    </div>
                    <div className="flex-1 space-y-0.5">
                      <p className="font-semibold">{r.hospitalName}</p>
                      <div className="flex items-center gap-2 text-sm text-muted-foreground">
                        <PetAvatar
                          name={r.petName}
                          imageUrl={petsById.get(r.petId)?.imageUrl}
                          className="h-7 w-7"
                        />
                        <span>{r.petName}</span>
                      </div>
                    </div>
                    <div className="flex flex-col items-end gap-2">
                      <ReservationProgressBadge
                        progressStatus={r.progressStatus}
                        paymentStatus={r.paymentStatus}
                      />
                      <Button asChild size="sm" variant="outline">
                        <Link to={`/reservations/${r.reservationId}`}>
                          상세 보기
                        </Link>
                      </Button>
                    </div>
                  </CardContent>
                </Card>
              ))}
            </div>
          </div>
        ))}
      </div>

      {/* 페이지네이션 (0-base) */}
      {data && data.totalPages > 1 && (
        <div className="flex items-center justify-center gap-3 pt-2">
          <Button
            variant="outline"
            size="sm"
            disabled={data.first || isFetching}
            onClick={() => goToPage((params.page ?? 0) - 1)}
          >
            이전
          </Button>
          <span className="text-sm text-muted-foreground">
            {data.page + 1} / {data.totalPages}
          </span>
          <Button
            variant="outline"
            size="sm"
            disabled={data.last || isFetching}
            onClick={() => goToPage((params.page ?? 0) + 1)}
          >
            다음
          </Button>
        </div>
      )}
    </div>
  )
}
