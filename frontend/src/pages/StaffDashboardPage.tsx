// 병원 스태프 — 운영 대시보드. 승인 대기(날짜·시간순)와 미수금(현장 수납 필요)을 한눈에.
// 목록 API에 날짜 필터가 없어(SA §8-6) "오늘 예약" 정확 집계는 빼고, 불러온 범위 안에서 정리한다.
import { Link } from 'react-router-dom'
import { useStaffReservations } from '@/features/staffReservations/hooks'
import { groupByDate } from '@/features/staffReservations/schedule'
import { useHospitalPayments } from '@/features/staffPayments/hooks'
import { naiveTimeLabel } from '@/lib/seoulTime'
import { ReservationStatusBadge } from '@/components/common/StatusBadge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { EmptyState, ErrorState, PageLoader } from '@/components/common/States'
import { PaymentStatus, ReservationStatus } from '@/types/enums'

// 새 요청·자동 노쇼가 스태프 조작 없이도 바뀌므로 주기적으로 갱신한다(30초).
const POLL_MS = 30_000

/*
  미수금(자동 결제 실패 → 현장 수납 필요) 요약. 불러온 페이지 안에서 센다.
  `GET /api/hospital/payments`(#209)가 develop에 있어 항상 호출하며, 미수금이 없으면 카드를 숨긴다.
*/
function OutstandingCard() {
  const query = useHospitalPayments(0, 100)
  const outstanding =
    query.data?.content.filter(
      (p) => p.paymentStatus === PaymentStatus.OFFLINE_REQUIRED,
    ) ?? []

  if (query.isLoading || query.isError || outstanding.length === 0) return null

  return (
    <Card className="border-destructive">
      <CardHeader>
        <CardTitle className="text-destructive">미수금 · 현장 수납 필요</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <p className="text-sm text-muted-foreground">
          자동 결제에 실패해 현장 수납이 필요한 결제가{' '}
          <strong className="text-destructive">{outstanding.length}건</strong>{' '}
          있어요.
        </p>
        <Button variant="outline" asChild>
          <Link to="/staff/payments">결제 관리로</Link>
        </Button>
      </CardContent>
    </Card>
  )
}

export function StaffDashboardPage() {
  const pending = useStaffReservations(
    { status: ReservationStatus.REQUESTED, page: 0, size: 20 },
    { refetchInterval: POLL_MS },
  )

  // 승인 대기를 날짜별·이른 시간부터 묶는다.
  const groups = pending.data ? groupByDate(pending.data.content, true) : []

  return (
    <div className="space-y-5">
      <h1 className="text-2xl font-bold">운영 대시보드</h1>

      <OutstandingCard />

      <Card>
        <CardHeader>
          <CardTitle>승인 대기</CardTitle>
          {/* 목록 API가 requestedAt DESC로 페이지를 자르므로 이 정렬은 현재 페이지 안에서만 유효하다. */}
          <p className="text-xs text-muted-foreground">
            최근 요청 20건을 예약 시각순으로 정리했어요. 전체 큐 기준 순서는 아니에요.
          </p>
        </CardHeader>
        <CardContent className="space-y-4">
          {pending.isLoading && <PageLoader />}
          {pending.isError && <ErrorState onRetry={() => pending.refetch()} />}
          {pending.data && (
            <>
              <p className="text-3xl font-bold">
                {pending.data.totalElements}
                <span className="ml-1 text-base font-normal text-muted-foreground">
                  건
                </span>
              </p>
              {pending.data.content.length === 0 ? (
                <EmptyState message="승인 대기 중인 예약이 없어요." />
              ) : (
                <div className="space-y-4">
                  {groups.map((group) => (
                    <div key={group.date} className="space-y-2">
                      <h2 className="text-sm font-semibold text-muted-foreground">
                        {group.label}{' '}
                        <span className="font-normal">
                          · {group.items.length}건
                        </span>
                      </h2>
                      {group.items.map((item) => (
                        <div
                          key={item.reservationId}
                          className="flex items-center justify-between rounded-md border p-3 text-sm"
                        >
                          <div>
                            <span className="font-medium">{item.petName}</span>
                            <span className="ml-2 text-muted-foreground">
                              {naiveTimeLabel(item.reservedAt)}
                            </span>
                          </div>
                          <ReservationStatusBadge
                            status={item.reservationStatus}
                          />
                        </div>
                      ))}
                    </div>
                  ))}
                </div>
              )}
              <Button asChild>
                <Link to="/staff/reservations">예약 검토</Link>
              </Button>
            </>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
