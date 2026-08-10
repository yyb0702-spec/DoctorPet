// 병원 스태프 — 운영 대시보드. 승인 대기 건수만 정확히 낼 수 있다
// (목록 API에 날짜·복수상태 필터가 없어 "오늘 예약" 류 집계는 이번 범위에서 뺀다).
import { Link } from 'react-router-dom'
import { useStaffReservations } from '@/features/staffReservations/hooks'
import { ReservationStatusBadge } from '@/components/common/StatusBadge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { EmptyState, ErrorState, PageLoader } from '@/components/common/States'
import { ReservationStatus } from '@/types/enums'

function fmt(iso: string): string {
  return new Date(iso).toLocaleString('ko-KR')
}

export function StaffDashboardPage() {
  const pending = useStaffReservations({
    status: ReservationStatus.REQUESTED,
    page: 0,
    size: 3,
  })

  return (
    <div className="space-y-5">
      <h1 className="text-2xl font-bold">운영 대시보드</h1>

      <Card>
        <CardHeader>
          <CardTitle>승인 대기</CardTitle>
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
                <div className="space-y-2">
                  {pending.data.content.map((item) => (
                    <div
                      key={item.reservationId}
                      className="flex items-center justify-between rounded-md border p-3 text-sm"
                    >
                      <div>
                        <span className="font-medium">{item.petName}</span>
                        <span className="ml-2 text-muted-foreground">
                          {fmt(item.reservedAt)}
                        </span>
                      </div>
                      <ReservationStatusBadge status={item.reservationStatus} />
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
