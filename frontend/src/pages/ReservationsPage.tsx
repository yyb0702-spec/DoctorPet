// 내 예약 목록 (실연동, PR #68). 페이지네이션 + 상태 필터 + progressStatus 배지.
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useReservations } from '@/features/reservations/hooks'
import type { ReservationListParams } from '@/features/reservations/types'
import { ReservationProgressBadge } from '@/components/common/StatusBadge'
import { Card, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { EmptyState, ErrorState, PageLoader } from '@/components/common/States'
import { ReservationStatus } from '@/types/enums'

const PAGE_SIZE = 20

// 상태 필터 칩(백엔드 status 파라미터는 ReservationStatus 값).
const STATUS_FILTERS = [
  { value: undefined, label: '전체' },
  { value: ReservationStatus.REQUESTED, label: '승인 대기' },
  { value: ReservationStatus.CONFIRMED, label: '예약 확정' },
  { value: ReservationStatus.TREATMENT_COMPLETED, label: '진료 완료' },
  { value: ReservationStatus.CANCELED, label: '취소' },
] as const

function shortDate(iso: string): string {
  const d = new Date(iso)
  return `${d.getMonth() + 1}.${d.getDate()}`
}

function timeLabel(iso: string): string {
  return new Date(iso).toLocaleString('ko-KR', {
    hour: '2-digit',
    minute: '2-digit',
  })
}

export function ReservationsPage() {
  const [params, setParams] = useState<ReservationListParams>({
    page: 0,
    size: PAGE_SIZE,
  })
  const { data, isLoading, isError, refetch, isFetching } =
    useReservations(params)

  const content = data?.content ?? []

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

      <div className="grid gap-3">
        {content.map((r) => (
          <Card key={r.reservationId}>
            <CardContent className="flex items-center gap-4 p-5">
              <div className="flex flex-col items-center rounded-lg bg-primary/5 px-3 py-2 text-center">
                <span className="text-lg font-bold text-primary">
                  {shortDate(r.reservedAt)}
                </span>
                <span className="text-xs text-muted-foreground">
                  {timeLabel(r.reservedAt)}
                </span>
              </div>
              <div className="flex-1 space-y-0.5">
                <p className="font-semibold">{r.hospitalName}</p>
                <p className="text-sm text-muted-foreground">{r.petName}</p>
              </div>
              <div className="flex flex-col items-end gap-2">
                <ReservationProgressBadge
                  progressStatus={r.progressStatus}
                  paymentStatus={r.paymentStatus}
                />
                <Button asChild size="sm" variant="outline">
                  <Link to={`/reservations/${r.reservationId}`}>상세 보기</Link>
                </Button>
              </div>
            </CardContent>
          </Card>
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
