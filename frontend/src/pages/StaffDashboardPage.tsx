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
  `GET /api/hospital/payments`(#209)가 develop에 있어 항상 호출한다. 로딩·미수금 0건이면 카드를
  숨기되, 조회 실패는 숨기지 않고 재시도 배너를 띄운다 — 실장애로 미수금 경고가 조용히 사라지면
  현장 수납을 놓치기 때문이다(플래그 시절 옛 주석이 지적했던 '오류를 숨긴 탓에 카드가 조용히 사라짐').
*/
function OutstandingCard() {
  // 미수금은 열어 둔 대시보드에도 새로 생기므로 30초마다 갱신한다(승인 대기 쿼리와 같은 주기).
  const query = useHospitalPayments(0, 100, { refetchInterval: POLL_MS })
  const outstanding =
    query.data?.content.filter(
      (p) => p.paymentStatus === PaymentStatus.OFFLINE_REQUIRED,
    ) ?? []
  // 병원 결제 API의 최대 size는 100이라 활성 결제가 100건을 넘으면 뒤 페이지 미수금이 집계에서 빠진다.
  // 상태별 재조회가 없어 전량 집계는 불가하므로, 잘렸을 때 카드에 범위를 명시해 오해를 막는다.
  const truncated = query.data ? !query.data.last : false

  if (query.isLoading) return null

  if (query.isError) {
    return (
      <Card className="border-destructive/50">
        <CardContent className="flex flex-wrap items-center justify-between gap-3 p-4">
          <p className="text-sm text-muted-foreground">
            미수금 현황을 불러오지 못했어요.
          </p>
          <Button size="sm" variant="outline" onClick={() => query.refetch()}>
            다시 시도
          </Button>
        </CardContent>
      </Card>
    )
  }

  if (outstanding.length === 0) return null

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
        {truncated && (
          <p className="text-xs text-muted-foreground">
            활성 결제가 100건을 넘어 최근 100건 기준으로 집계했어요. 전체는 결제
            관리에서 확인하세요.
          </p>
        )}
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
