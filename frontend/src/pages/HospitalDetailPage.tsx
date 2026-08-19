// 병원 상세 (화면메모 A-6, 실연동) + 예약 요청 패널.
import { useParams, Link } from 'react-router-dom'
import { MapPin, Phone, Clock } from 'lucide-react'
import {
  todaySeoul,
  useHospitalDetail,
  useHospitalSlots,
} from '@/features/hospitals/hooks'
import { ReservationRequestPanel } from '@/features/reservations/ReservationRequestPanel'
import { useAuthStore } from '@/lib/auth/authStore'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { ErrorState, PageLoader } from '@/components/common/States'
import { HospitalMap } from '@/components/common/HospitalMap'
import { HospitalReviewList } from '@/features/reviews/HospitalReviewList'
import type { BusinessHour } from '@/features/hospitals/types'

const DAY_LABEL: Record<string, string> = {
  MONDAY: '월',
  TUESDAY: '화',
  WEDNESDAY: '수',
  THURSDAY: '목',
  FRIDAY: '금',
  SATURDAY: '토',
  SUNDAY: '일',
}

function BusinessHours({ hours }: { hours: BusinessHour[] }) {
  return (
    <ul className="space-y-1 text-sm">
      {hours.map((h) => (
        <li key={h.dayOfWeek} className="flex justify-between">
          <span className="text-muted-foreground">{DAY_LABEL[h.dayOfWeek]}</span>
          <span>
            {h.closed ? '휴무' : `${h.openTime ?? '-'} ~ ${h.closeTime ?? '-'}`}
          </span>
        </li>
      ))}
    </ul>
  )
}

export function HospitalDetailPage() {
  const { hospitalId } = useParams()
  const id = Number(hospitalId)
  const { data, isLoading, isError, refetch } = useHospitalDetail(id)
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  const isPartner = data?.partnershipStatus === 'PARTNER'
  // 가장 빠른 진료 가능 안내용 (제휴 병원만 오늘 기준 슬롯 조회).
  const today = todaySeoul()
  const slotsQuery = useHospitalSlots(id, today, isPartner)

  if (isLoading) return <PageLoader />
  if (isError || !data) return <ErrorState onRetry={() => refetch()} />

  // 14일치 날짜 가용성에서 첫 예약 가능일을 찾고, 그 날이 오늘이면 정확한 시각까지 보여준다.
  const lookup = slotsQuery.data
  const firstAvailableDate = lookup?.dateAvailabilities.find(
    (d) => d.reservationAvailable,
  )?.date
  const earliestTodaySlot =
    firstAvailableDate === today
      ? [...(lookup?.slots ?? [])]
          .filter((s) => s.availabilityStatus === 'AVAILABLE')
          .sort((a, b) => a.startAt.localeCompare(b.startAt))[0]
      : undefined
  const earliestLabel = firstAvailableDate
    ? earliestTodaySlot
      ? `오늘 ${new Date(earliestTodaySlot.startAt).toLocaleTimeString('ko-KR', {
          hour: '2-digit',
          minute: '2-digit',
        })}`
      : new Date(`${firstAvailableDate}T00:00:00`).toLocaleDateString('ko-KR', {
          month: 'long',
          day: 'numeric',
          weekday: 'short',
        })
    : null

  return (
    <div className="grid gap-5 lg:grid-cols-[1fr_360px]">
      <div className="space-y-5">
        <div className="space-y-2">
          <div className="flex flex-wrap items-center gap-2">
            {isPartner && (
              <Badge variant="success">제휴 병원 · 온라인 예약 가능</Badge>
            )}
            {!isPartner && <Badge variant="muted">제휴 전 병원</Badge>}
            {data.businessStatus !== 'OPEN' && (
              <Badge variant="destructive">
                {data.businessStatus === 'CLOSED_TEMP' ? '임시휴업' : '폐업'}
              </Badge>
            )}
          </div>
          <h1 className="text-2xl font-bold">{data.name}</h1>
          <p className="flex items-center gap-1.5 text-sm text-muted-foreground">
            <MapPin className="h-4 w-4" />
            {data.address}
          </p>
          {data.phoneNumber && (
            <p className="flex items-center gap-1.5 text-sm text-muted-foreground">
              <Phone className="h-4 w-4" />
              {data.phoneNumber}
            </p>
          )}
          {isPartner && earliestLabel && (
            <p className="flex items-center gap-1.5 text-sm font-medium text-primary">
              <Clock className="h-4 w-4" />
              가장 빠른 진료 가능: {earliestLabel}
            </p>
          )}
          {/* 예약 응답 지표(제휴 병원, PR #165). 집계할 예약이 없으면 null이라 숨긴다. */}
          {isPartner &&
            (data.reservationResponseRate != null ||
              data.averageApprovalMinutes != null) && (
              <div className="flex flex-wrap gap-2 pt-1">
                {data.reservationResponseRate != null && (
                  <Badge variant="outline">
                    예약 응답률 {data.reservationResponseRate}%
                  </Badge>
                )}
                {data.averageApprovalMinutes != null && (
                  <Badge variant="outline">
                    평균 승인 {data.averageApprovalMinutes}분
                  </Badge>
                )}
              </div>
            )}
        </div>

        {/* 위치 지도 (주소 기반, 제휴 여부 무관) */}
        <Card>
          <CardHeader>
            <CardTitle>위치</CardTitle>
          </CardHeader>
          <CardContent>
            <HospitalMap
              address={data.address}
              name={data.name}
              className="h-64"
            />
          </CardContent>
        </Card>

        {isPartner ? (
          <>
            {data.capabilities && data.capabilities.length > 0 && (
              <Card>
                <CardHeader>
                  <CardTitle>진료 역량</CardTitle>
                </CardHeader>
                <CardContent className="flex flex-wrap gap-1.5">
                  {data.capabilities.map((c) => (
                    <Badge key={c} variant="secondary">
                      {c}
                    </Badge>
                  ))}
                </CardContent>
              </Card>
            )}
            <div className="flex flex-wrap gap-2 text-sm">
              {data.surgeryAvailable && <Badge variant="outline">수술 가능</Badge>}
              {data.hospitalizationAvailable && (
                <Badge variant="outline">입원 가능</Badge>
              )}
              {data.nightCare && <Badge variant="outline">야간 진료</Badge>}
              {data.emergency && <Badge variant="outline">응급 진료</Badge>}
            </div>
            {data.businessHours && data.businessHours.length > 0 && (
              <Card>
                <CardHeader>
                  <CardTitle>영업시간</CardTitle>
                </CardHeader>
                <CardContent>
                  <BusinessHours hours={data.businessHours} />
                </CardContent>
              </Card>
            )}
            {/* 보호자 후기 (이슈 #114) — 결제 완료 예약의 보호자만 작성할 수 있고, 목록은 익명이다. */}
            <HospitalReviewList
              hospitalId={id}
              averageRating={data.averageRating}
              reviewCount={data.reviewCount}
            />
          </>
        ) : (
          <Card>
            <CardContent className="p-6 text-sm text-muted-foreground">
              {data.partnershipNotice ??
                '아직 제휴 전 병원이라 온라인 예약을 지원하지 않습니다.'}
            </CardContent>
          </Card>
        )}
      </div>

      {/* 예약 패널: 제휴 병원 + 로그인 상태에서만 */}
      <aside>
        {!isPartner ? null : !isAuthenticated ? (
          <Card>
            <CardContent className="space-y-3 p-6 text-center text-sm">
              <p className="text-muted-foreground">예약하려면 로그인이 필요합니다.</p>
              <Button asChild className="w-full">
                <Link to="/login">로그인</Link>
              </Button>
            </CardContent>
          </Card>
        ) : (
          <ReservationRequestPanel hospitalId={id} />
        )}
      </aside>
    </div>
  )
}
