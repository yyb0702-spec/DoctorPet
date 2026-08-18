// 병원 스태프 — 예약 요청 검토 + 상태별 큐 (SA §8-6).
import { useState } from 'react'
import { Link } from 'react-router-dom'
import {
  useApproveReservation,
  useCheckInReservation,
  useCompleteTreatment,
  useConfirmNoShow,
  useRejectReservation,
  useRestoreNoShow,
  useStaffReservationCounts,
  useStaffReservations,
  useStartTreatment,
} from '@/features/staffReservations/hooks'
import { REJECT_REASONS } from '@/features/staffReservations/types'
import type { StaffReservationListItem } from '@/features/staffReservations/types'
import {
  groupByDate,
  isOverdue,
  isUpcomingStatus,
} from '@/features/staffReservations/schedule'
import { ReservationStatusBadge } from '@/components/common/StatusBadge'
import { ReasonPrompt } from '@/components/common/ReasonPrompt'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { EmptyState, ErrorState, PageLoader } from '@/components/common/States'
import { cn } from '@/lib/utils'
import { ApiError } from '@/lib/api/error'
import { ReservationStatus } from '@/types/enums'

// 새 요청·자동 노쇼가 스태프 조작 없이도 바뀌므로 주기적으로 갱신한다(30초).
const POLL_MS = 30_000

const TABS: ReservationStatus[] = [
  ReservationStatus.REQUESTED,
  ReservationStatus.CONFIRMED,
  ReservationStatus.NO_SHOW_PENDING,
  ReservationStatus.CHECKED_IN,
  ReservationStatus.IN_TREATMENT,
  ReservationStatus.TREATMENT_COMPLETED,
  ReservationStatus.NO_SHOW,
  ReservationStatus.REJECTED,
  ReservationStatus.CANCELED,
]

const TAB_LABEL: Record<ReservationStatus, string> = {
  REQUESTED: '요청',
  CONFIRMED: '확정',
  NO_SHOW_PENDING: '노쇼 확인중',
  CHECKED_IN: '내원',
  IN_TREATMENT: '진료중',
  TREATMENT_COMPLETED: '진료완료',
  NO_SHOW: '노쇼',
  REJECTED: '거절',
  CANCELED: '취소',
}

function fmt(iso: string): string {
  return new Date(iso).toLocaleString('ko-KR')
}

function errorMessage(error: unknown): string {
  return error instanceof ApiError ? error.message : '처리에 실패했습니다.'
}

function ReservationDetailLink({ item }: { item: StaffReservationListItem }) {
  return (
    <Button size="sm" variant="outline" asChild>
      <Link to={`/staff/reservations/${item.reservationId}`} state={{ item }}>
        상세·채팅
      </Link>
    </Button>
  )
}

export function RowActions({ item }: { item: StaffReservationListItem }) {
  const approve = useApproveReservation()
  const reject = useRejectReservation()
  const checkIn = useCheckInReservation()
  const start = useStartTreatment()
  const complete = useCompleteTreatment()
  const confirmNoShow = useConfirmNoShow()
  const restoreNoShow = useRestoreNoShow()

  switch (item.reservationStatus) {
    case ReservationStatus.REQUESTED:
      return (
        <div className="flex flex-wrap items-start gap-2">
          <ReservationDetailLink item={item} />
          <Button
            size="sm"
            disabled={approve.isPending}
            onClick={() => approve.mutate(item.reservationId)}
          >
            승인
          </Button>
          <ReasonPrompt
            triggerLabel="거절"
            triggerVariant="destructive"
            confirmLabel="거절 확정"
            options={REJECT_REASONS}
            pending={reject.isPending}
            errorMessage={reject.isError ? errorMessage(reject.error) : undefined}
            onConfirm={(rejectReason) =>
              reject.mutate({ reservationId: item.reservationId, rejectReason })
            }
          />
        </div>
      )
    // NO_SHOW_PENDING은 예약시각 경과~자동 노쇼 확정 사이 유예 상태로, 백엔드가
    // CONFIRMED와 동일하게 내원 확인·노쇼 확정을 허용한다(SA §8-6, AWAITING_ARRIVAL_STATUSES).
    case ReservationStatus.CONFIRMED:
    case ReservationStatus.NO_SHOW_PENDING:
      return (
        <div className="flex flex-wrap items-start gap-2">
          <ReservationDetailLink item={item} />
          <Button
            size="sm"
            disabled={checkIn.isPending}
            onClick={() => checkIn.mutate(item.reservationId)}
          >
            내원 확인
          </Button>
          <ReasonPrompt
            triggerLabel="노쇼 확정"
            triggerVariant="destructive"
            confirmLabel="노쇼 확정"
            maxLength={255}
            pending={confirmNoShow.isPending}
            errorMessage={
              confirmNoShow.isError ? errorMessage(confirmNoShow.error) : undefined
            }
            onConfirm={(reason) =>
              confirmNoShow.mutate({ reservationId: item.reservationId, reason })
            }
          />
        </div>
      )
    case ReservationStatus.CHECKED_IN:
      return (
        <div className="flex flex-wrap items-start gap-2">
          <ReservationDetailLink item={item} />
          <Button
            size="sm"
            disabled={start.isPending}
            onClick={() => start.mutate(item.reservationId)}
          >
            진료 시작
          </Button>
        </div>
      )
    case ReservationStatus.IN_TREATMENT:
      return (
        <div className="flex flex-wrap items-start gap-2">
          <ReservationDetailLink item={item} />
          <Button
            size="sm"
            disabled={complete.isPending}
            onClick={() => complete.mutate(item.reservationId)}
          >
            진료 완료
          </Button>
        </div>
      )
    case ReservationStatus.TREATMENT_COMPLETED:
      return (
        <Button size="sm" variant="outline" asChild>
          <Link
            to={`/staff/reservations/${item.reservationId}`}
            state={{ item }}
          >
            진료비 청구
          </Link>
        </Button>
      )
    case ReservationStatus.NO_SHOW:
      return (
        <div className="flex flex-wrap items-start gap-2">
          <ReservationDetailLink item={item} />
          <ReasonPrompt
            triggerLabel="노쇼 정정"
            confirmLabel="정정"
            maxLength={255}
            pending={restoreNoShow.isPending}
            errorMessage={
              restoreNoShow.isError ? errorMessage(restoreNoShow.error) : undefined
            }
            onConfirm={(reason) =>
              restoreNoShow.mutate({ reservationId: item.reservationId, reason })
            }
          />
        </div>
      )
    default:
      return <ReservationDetailLink item={item} />
  }
}

function ReservationCard({ item }: { item: StaffReservationListItem }) {
  const overdue = isOverdue(item)
  return (
    <Card className={cn(overdue && 'border-destructive')}>
      <CardContent className="flex flex-wrap items-center justify-between gap-3 p-4">
        <div className="space-y-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-semibold">{item.petName}</span>
            <ReservationStatusBadge status={item.reservationStatus} />
            {overdue && (
              <span className="rounded bg-destructive/10 px-1.5 py-0.5 text-xs font-medium text-destructive">
                지연 · 자동 노쇼 임박
              </span>
            )}
          </div>
          <p className="text-sm text-muted-foreground">{fmt(item.reservedAt)}</p>
          <p className="text-xs text-muted-foreground">
            전체 예약 {item.reservationHistory.totalReservationCount} · 진료완료{' '}
            {item.reservationHistory.completedCount} · 취소{' '}
            {item.reservationHistory.cancelCount} ·{' '}
            <span
              className={
                item.reservationHistory.noShowCount > 0
                  ? 'font-semibold text-destructive'
                  : undefined
              }
            >
              노쇼 {item.reservationHistory.noShowCount}회
            </span>{' '}
            (전 병원 기준)
          </p>
          {item.rejectionReason && (
            <p className="text-xs text-muted-foreground">
              거절 사유: {item.rejectionReason}
            </p>
          )}
        </div>
        <RowActions item={item} />
      </CardContent>
    </Card>
  )
}

export function StaffReservationQueuePage() {
  const [status, setStatus] = useState<ReservationStatus>(
    ReservationStatus.REQUESTED,
  )
  const [page, setPage] = useState(0)
  const query = useStaffReservations(
    { status, page, size: 20 },
    { refetchInterval: POLL_MS },
  )
  const counts = useStaffReservationCounts(TABS, { refetchInterval: POLL_MS })

  const handleTab = (next: ReservationStatus) => {
    setStatus(next)
    setPage(0)
  }

  // 불러온 페이지를 예약 시각으로 날짜별 그룹핑한다(예정 상태는 이른 시간부터, 이력은 최근부터).
  const groups = query.data
    ? groupByDate(query.data.content, isUpcomingStatus(status))
    : []

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">예약 관리</h1>
      <div className="flex flex-wrap gap-1 border-b pb-2">
        {TABS.map((t) => {
          const count = counts[t]
          return (
            <Button
              key={t}
              size="sm"
              variant={t === status ? 'default' : 'ghost'}
              onClick={() => handleTab(t)}
            >
              {TAB_LABEL[t]}
              {count ? (
                <span className="ml-1 rounded-full bg-muted px-1.5 text-xs tabular-nums">
                  {count}
                </span>
              ) : null}
            </Button>
          )
        })}
      </div>

      {query.isLoading && <PageLoader />}
      {query.isError && <ErrorState onRetry={() => query.refetch()} />}
      {query.data && query.data.content.length === 0 && (
        <EmptyState message="해당 상태의 예약이 없어요." />
      )}

      <div className="space-y-5">
        {groups.map((group) => (
          <div key={group.date} className="space-y-2">
            <h2 className="text-sm font-semibold text-muted-foreground">
              {group.label}{' '}
              <span className="font-normal">· {group.items.length}건</span>
            </h2>
            {group.items.map((item) => (
              <ReservationCard key={item.reservationId} item={item} />
            ))}
          </div>
        ))}
      </div>

      {query.data && query.data.totalPages > 1 && (
        <div className="flex items-center justify-center gap-2 pt-2">
          <Button
            size="sm"
            variant="outline"
            disabled={query.data.first}
            onClick={() => setPage((p) => Math.max(0, p - 1))}
          >
            이전
          </Button>
          <span className="text-sm text-muted-foreground">
            {query.data.page + 1} / {query.data.totalPages}
          </span>
          <Button
            size="sm"
            variant="outline"
            disabled={query.data.last}
            onClick={() => setPage((p) => p + 1)}
          >
            다음
          </Button>
        </div>
      )}
    </div>
  )
}
