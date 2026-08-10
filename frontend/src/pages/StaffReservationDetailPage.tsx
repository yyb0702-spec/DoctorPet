// 병원 스태프 — 진료비 청구·결제 결과·환불/오프라인 정산 (SA §8-7).
// 병원 예약 단건 조회 API가 없어(§8-6은 목록뿐), 큐 목록에서 navigate state로 넘겨받은
// 항목을 그대로 쓴다. 직접 URL 진입 등으로 state가 없으면 목록으로 돌려보낸다.
import { useState } from 'react'
import { Link, Navigate, useLocation, useParams } from 'react-router-dom'
import {
  useChargePayment,
  useRefundPayment,
  useSettleOffline,
  useStaffReservationPayments,
} from '@/features/staffPayments/hooks'
import type { StaffReservationListItem } from '@/features/staffReservations/types'
import { ReservationStatusBadge, PaymentStatusBadge } from '@/components/common/StatusBadge'
import { ReasonPrompt } from '@/components/common/ReasonPrompt'
import { Field } from '@/components/common/Field'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { ErrorState, PageLoader } from '@/components/common/States'
import { ApiError } from '@/lib/api/error'
import { PaymentStatus } from '@/types/enums'

interface LocationState {
  item?: StaffReservationListItem
}

function fmt(iso: string): string {
  return new Date(iso).toLocaleString('ko-KR')
}

function errorMessage(error: unknown): string {
  return error instanceof ApiError ? error.message : '처리에 실패했습니다.'
}

function ChargeForm({ reservationId }: { reservationId: number }) {
  const [amount, setAmount] = useState('')
  const charge = useChargePayment(reservationId)

  const parsed = Number(amount)
  const canSubmit = amount.trim().length > 0 && parsed > 0

  return (
    <div className="space-y-3">
      <Field label="진료비 (원)">
        <Input
          type="number"
          min={1}
          inputMode="numeric"
          value={amount}
          onChange={(e) => setAmount(e.target.value)}
          placeholder="예: 68000"
        />
      </Field>
      {charge.isError && (
        <p className="text-sm text-destructive">{errorMessage(charge.error)}</p>
      )}
      <Button
        disabled={!canSubmit || charge.isPending}
        onClick={() => charge.mutate(parsed)}
      >
        {charge.isPending ? '청구 중…' : '진료비 청구'}
      </Button>
    </div>
  )
}

function PaymentSection({ reservationId }: { reservationId: number }) {
  const paymentsQuery = useStaffReservationPayments(reservationId)
  const settleOffline = useSettleOffline(reservationId)
  const refund = useRefundPayment(reservationId)

  if (paymentsQuery.isLoading) return <PageLoader />
  if (paymentsQuery.isError) {
    return <ErrorState onRetry={() => paymentsQuery.refetch()} />
  }

  const payments = paymentsQuery.data ?? []

  if (payments.length === 0) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>진료비 청구</CardTitle>
        </CardHeader>
        <CardContent>
          <ChargeForm reservationId={reservationId} />
        </CardContent>
      </Card>
    )
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>결제 결과</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        {payments.map((p) => (
          <div key={p.paymentId} className="space-y-2">
            <div className="flex items-center justify-between">
              <span className="text-2xl font-bold">
                {p.amount.toLocaleString('ko-KR')}원
              </span>
              <PaymentStatusBadge status={p.status} />
            </div>
            {p.cardBrandSnapshot && (
              <p className="text-sm text-muted-foreground">
                {p.cardBrandSnapshot} ****{p.cardLast4Snapshot}
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
            {p.refundedAt && (
              <p className="text-xs text-muted-foreground">
                환불 완료 · {fmt(p.refundedAt)}
              </p>
            )}

            {p.status === PaymentStatus.PENDING && (
              <div className="space-y-2 rounded-md bg-amber-50 p-3 text-sm text-amber-900">
                <p>결제 결과를 확인하고 있어요. 정산 배치가 자동으로 확정해요.</p>
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => paymentsQuery.refetch()}
                >
                  새로고침
                </Button>
              </div>
            )}

            {p.status === PaymentStatus.OFFLINE_REQUIRED && (
              <div className="space-y-2">
                {settleOffline.isError && (
                  <p className="text-sm text-destructive">
                    {errorMessage(settleOffline.error)}
                  </p>
                )}
                <Button
                  size="sm"
                  disabled={settleOffline.isPending}
                  onClick={() => settleOffline.mutate(p.paymentId)}
                >
                  {settleOffline.isPending ? '처리 중…' : '현장 수납 완료'}
                </Button>
              </div>
            )}

            {p.status === PaymentStatus.PAID && (
              <ReasonPrompt
                triggerLabel="전액 환불"
                triggerVariant="destructive"
                confirmLabel="환불 확정"
                maxLength={200}
                pending={refund.isPending}
                errorMessage={refund.isError ? errorMessage(refund.error) : undefined}
                onConfirm={(reason) =>
                  refund.mutate({ paymentId: p.paymentId, reason })
                }
              />
            )}
          </div>
        ))}
      </CardContent>
    </Card>
  )
}

export function StaffReservationDetailPage() {
  const { reservationId } = useParams()
  const location = useLocation()
  const item = (location.state as LocationState | null)?.item

  if (!item) {
    return <Navigate to="/staff/reservations" replace />
  }

  const id = Number(reservationId)

  return (
    <div className="mx-auto max-w-2xl space-y-5">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">예약 상세</h1>
        <ReservationStatusBadge status={item.reservationStatus} />
      </div>

      <Card>
        <CardHeader>
          <CardTitle>{item.petName}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-1 text-sm">
          <p className="text-muted-foreground">진료 시간 {fmt(item.reservedAt)}</p>
          <p className="text-xs text-muted-foreground">
            전체 예약 {item.reservationHistory.totalReservationCount} ·
            진료완료 {item.reservationHistory.completedCount} · 취소{' '}
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
        </CardContent>
      </Card>

      <PaymentSection reservationId={id} />

      <Button variant="ghost" asChild>
        <Link to="/staff/reservations">← 예약 목록으로</Link>
      </Button>
    </div>
  )
}
