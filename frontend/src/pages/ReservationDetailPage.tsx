// 예약 상세 (실연동, PR #68). 상태별 안내 배너 + 진행 스텝 + 취소.
import { useParams, Link } from 'react-router-dom'
import { MapPin, Phone } from 'lucide-react'
import {
  useCancelReservation,
  useReservationDetail,
} from '@/features/reservations/hooks'
import { useReservationPayments } from '@/features/payments/hooks'
import { ReservationProgress } from '@/features/reservations/ReservationProgress'
import {
  PaymentStatusBadge,
  ReservationProgressBadge,
} from '@/components/common/StatusBadge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { ErrorState, PageLoader } from '@/components/common/States'
import { cn } from '@/lib/utils'
import { PaymentChannel, PaymentStatus, ReservationStatus } from '@/types/enums'
import type { PaymentStatus as PaymentStatusType } from '@/types/enums'
import { ApiError } from '@/lib/api/error'
import { ChatPanel } from '@/features/chat/ChatPanel'

// 취소 가능한 예약 상태 (SA §5-1: REQUESTED·CONFIRMED, 서버가 리드타임 최종 검증).
const CANCELABLE: ReservationStatus[] = [
  ReservationStatus.REQUESTED,
  ReservationStatus.CONFIRMED,
]

type Tone = 'info' | 'success' | 'warning' | 'danger'

const TONE_CLASS: Record<Tone, string> = {
  info: 'bg-primary/5 text-foreground',
  success: 'bg-emerald-50 text-emerald-900',
  warning: 'bg-amber-50 text-amber-900',
  danger: 'bg-destructive/10 text-destructive',
}

// 예약·결제 상태에 따른 안내 배너 문구.
function banner(
  status: ReservationStatus,
  payment: PaymentStatusType | null | undefined,
): { title: string; desc: string; tone: Tone } {
  switch (status) {
    case ReservationStatus.REQUESTED:
      return {
        title: '예약 요청이 접수됐어요',
        desc: '병원의 확인을 기다리고 있어요. 승인되면 알림을 보내드려요.',
        tone: 'warning',
      }
    case ReservationStatus.CONFIRMED:
      return {
        title: '예약이 확정됐어요',
        desc: '방문 전 2시간까지 취소할 수 있어요.',
        tone: 'success',
      }
    case ReservationStatus.CHECKED_IN:
      return {
        title: '내원 완료',
        desc: '병원에서 진료를 준비하고 있어요.',
        tone: 'info',
      }
    case ReservationStatus.IN_TREATMENT:
      return { title: '진료 중', desc: '진료가 진행되고 있어요.', tone: 'info' }
    case ReservationStatus.TREATMENT_COMPLETED:
      if (payment === PaymentStatus.PENDING)
        return {
          title: '결제를 확인하고 있어요',
          desc: '잠시 후 결제 결과를 알려드려요.',
          tone: 'warning',
        }
      if (payment === PaymentStatus.OFFLINE_REQUIRED)
        return {
          title: '현장 수납이 필요해요',
          desc: '자동 결제에 실패해 병원에서 직접 수납해 주세요.',
          tone: 'danger',
        }
      if (payment === PaymentStatus.PAID || payment === PaymentStatus.OFFLINE_PAID)
        return {
          title: '진료와 결제가 완료됐어요',
          desc: '이용해 주셔서 감사합니다.',
          tone: 'success',
        }
      if (payment === PaymentStatus.REFUNDED)
        return {
          title: '진료비가 환불됐어요',
          desc: '병원에서 전액 환불을 처리했어요.',
          tone: 'info',
        }
      return {
        title: '진료가 완료됐어요',
        desc: '진료비 청구를 기다리고 있어요.',
        tone: 'info',
      }
    case ReservationStatus.REJECTED:
      return {
        title: '예약이 거절됐어요',
        desc: '다른 시간이나 병원으로 다시 예약해 주세요.',
        tone: 'danger',
      }
    case ReservationStatus.CANCELED:
      return {
        title: '예약이 취소됐어요',
        desc: '취소 가능 시간 안에 요청되어 정상 취소됐어요.',
        tone: 'danger',
      }
    case ReservationStatus.NO_SHOW_PENDING:
      return {
        title: '노쇼 여부를 확인하고 있어요',
        desc: '예약 시간이 지나 병원에서 방문 여부를 확인 중이에요.',
        tone: 'warning',
      }
    case ReservationStatus.NO_SHOW:
      return {
        title: '노쇼로 처리됐어요',
        desc: '방문하지 못한 예약이에요.',
        tone: 'danger',
      }
    default:
      return { title: '', desc: '', tone: 'info' }
  }
}

function fmt(iso: string): string {
  return new Date(iso).toLocaleString('ko-KR')
}

export function ReservationDetailPage() {
  const { reservationId } = useParams()
  const id = Number(reservationId)
  const detailQuery = useReservationDetail(id)
  const paymentsQuery = useReservationPayments(id)
  const cancel = useCancelReservation()

  if (detailQuery.isLoading) return <PageLoader />
  if (detailQuery.isError || !detailQuery.data)
    return <ErrorState onRetry={() => detailQuery.refetch()} />

  const r = detailQuery.data
  const payments = paymentsQuery.data ?? []
  const cancelable = CANCELABLE.includes(r.reservationStatus)
  const info = banner(r.reservationStatus, r.paymentStatus)
  const showProgress =
    r.reservationStatus === ReservationStatus.CONFIRMED ||
    r.reservationStatus === ReservationStatus.CHECKED_IN ||
    r.reservationStatus === ReservationStatus.IN_TREATMENT ||
    r.reservationStatus === ReservationStatus.TREATMENT_COMPLETED

  return (
    <div className="mx-auto max-w-2xl space-y-5">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">예약 상세</h1>
        <ReservationProgressBadge
          progressStatus={r.progressStatus}
          paymentStatus={r.paymentStatus}
        />
      </div>

      {/* 상태 안내 배너 */}
      <div className={cn('rounded-xl p-5', TONE_CLASS[info.tone])}>
        <p className="text-lg font-semibold">{info.title}</p>
        <p className="mt-1 text-sm opacity-90">{info.desc}</p>
      </div>

      {/* 진행 스텝 */}
      {showProgress && (
        <Card>
          <CardContent className="p-5">
            <ReservationProgress status={r.reservationStatus} />
          </CardContent>
        </Card>
      )}

      <Card>
        <CardHeader>
          <CardTitle>{r.hospital.name}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2 text-sm">
          <p className="flex items-center gap-1.5 text-muted-foreground">
            <MapPin className="h-4 w-4" />
            {r.hospital.address}
          </p>
          {r.hospital.phoneNumber && (
            <p className="flex items-center gap-1.5 text-muted-foreground">
              <Phone className="h-4 w-4" />
              {r.hospital.phoneNumber}
            </p>
          )}
          <dl className="grid grid-cols-[80px_1fr] gap-y-1 pt-2">
            <dt className="text-muted-foreground">반려동물</dt>
            <dd>
              {r.petSnapshot.name} (
              {r.petSnapshot.species === 'DOG' ? '강아지' : '고양이'})
            </dd>
            <dt className="text-muted-foreground">진료 시간</dt>
            <dd>{fmt(r.slot.startAt)}</dd>
            <dt className="text-muted-foreground">요청 시각</dt>
            <dd>{fmt(r.createdAt)}</dd>
            {r.rejectionReason && (
              <>
                <dt className="text-muted-foreground">거절 사유</dt>
                <dd>{r.rejectionReason}</dd>
              </>
            )}
          </dl>
        </CardContent>
      </Card>

      {/* 결제 내역 (진료 완료 이후 청구된 경우). 예약당 여러 건일 수 있다
          (빌링키 자동 청구 실패 → 오프라인 수납). 진료 전이면 빈 배열이라 표시하지 않는다. */}
      {payments.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle>진료비 결제</CardTitle>
          </CardHeader>
          <CardContent className="space-y-4 text-sm">
            {payments.map((p) => (
              <div
                key={p.paymentId}
                className="space-y-1 border-b pb-3 last:border-b-0 last:pb-0"
              >
                <div className="flex items-center justify-between">
                  <span className="text-2xl font-bold">
                    {p.amount.toLocaleString('ko-KR')}원
                  </span>
                  <PaymentStatusBadge status={p.status} />
                </div>
                {p.cardBrandSnapshot && (
                  <p className="text-muted-foreground">
                    {p.cardBrandSnapshot} ****{p.cardLast4Snapshot}
                    {p.paymentChannel === PaymentChannel.OFFLINE && ' · 현장 수납'}
                  </p>
                )}
                {p.paidAt && (
                  <p className="text-xs text-muted-foreground">
                    결제 완료 · {fmt(p.paidAt)}
                  </p>
                )}
                {p.offlineSettledAt && (
                  <p className="text-xs text-muted-foreground">
                    현장 수납 완료 · {fmt(p.offlineSettledAt)}
                  </p>
                )}
                {p.failedAt && !p.offlineSettledAt && (
                  <p className="text-xs text-muted-foreground">
                    현장 수납 대기 · 자동 결제 {fmt(p.failedAt)} 전환
                  </p>
                )}
                {p.refundedAt && (
                  <p className="text-xs text-muted-foreground">
                    환불 완료 · {fmt(p.refundedAt)}
                  </p>
                )}
              </div>
            ))}
          </CardContent>
        </Card>
      )}

      <ChatPanel
        reservationId={r.reservationId}
        reservationStatus={r.reservationStatus}
      />

      {/* 취소 (REQUESTED·CONFIRMED) */}
      {cancelable && (
        <div className="space-y-2">
          {cancel.isError && (
            <p className="text-sm text-destructive">
              {cancel.error instanceof ApiError
                ? cancel.error.message
                : '취소에 실패했습니다.'}
            </p>
          )}
          <Button
            variant="destructive"
            disabled={cancel.isPending}
            onClick={() => cancel.mutate(id)}
          >
            {cancel.isPending ? '취소 중…' : '예약 취소'}
          </Button>
          <p className="text-xs text-muted-foreground">
            예약 취소는 진료 2시간 전까지 가능합니다.
          </p>
        </div>
      )}

      {/* 취소·거절 상태면 다른 병원 찾기 유도 */}
      {(r.reservationStatus === ReservationStatus.CANCELED ||
        r.reservationStatus === ReservationStatus.REJECTED) && (
        <Button asChild>
          <Link to="/hospitals">다른 병원 찾기</Link>
        </Button>
      )}
    </div>
  )
}
