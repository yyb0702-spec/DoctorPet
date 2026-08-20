// 병원 스태프 — 운영 대시보드. 승인 대기(날짜·시간순)와 미수금(현장 수납 필요)을 한눈에.
// 목록 API에 날짜 필터가 없어(SA §8-6) "오늘 예약" 정확 집계는 빼고, 불러온 범위 안에서 정리한다.
import { Link } from 'react-router-dom'
import { useStaffReservations } from '@/features/staffReservations/hooks'
import { groupByDate } from '@/features/staffReservations/schedule'
import { useHospitalPayments } from '@/features/staffPayments/hooks'
import { HOSPITAL_OPS_BACKEND_READY } from '@/app/featureFlags'
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

  이 카드는 `GET /api/hospital/payments`에 의존하는데 그 목록 API가 아직 develop에 없다 —
  그래서 저장소가 `HOSPITAL_OPS_BACKEND_READY=false`로 `/staff/payments` 라우트·메뉴를 막고 있다.
  플래그를 무시하고 호출하면 실 환경에서 매번 404가 나고, 오류를 숨긴 탓에 카드가 조용히 사라지며
  CTA는 등록되지 않은 경로를 가리킨다(PR #194 리뷰 P1). 그래서 호출부터 같은 플래그로 막고,
  API·라우트가 준비되면 플래그 한 곳만 켜서 함께 살아나게 한다.
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

      {/* 결제 목록 API·라우트가 준비될 때까지 호출 자체를 하지 않는다(위 주석 참고). */}
      {HOSPITAL_OPS_BACKEND_READY && <OutstandingCard />}

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
