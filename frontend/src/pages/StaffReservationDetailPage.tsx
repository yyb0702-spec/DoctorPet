// 병원 스태프 — 진료비 청구·결제 결과·환불/오프라인 정산 (SA §8-7).
// 병원 예약 단건 조회 API가 없어(§8-6은 목록뿐), 큐 목록에서 navigate state로 넘겨받은
// 항목을 그대로 쓴다. 직접 URL 진입 등으로 state가 없으면 목록으로 돌려보낸다.
import { useState } from 'react'
import { Link, Navigate, useLocation, useParams } from 'react-router-dom'
import {
  useChargePayment,
  useCorrectionCharge,
  useItemDrafts,
  useRefundPayment,
  useSettleOffline,
  useStaffReservationPayments,
} from '@/features/staffPayments/hooks'
import { staffPaymentApi } from '@/features/staffPayments/api'
import { activePaymentId } from '@/features/staffPayments/activePayment'
import type { PaymentItemInput } from '@/features/staffPayments/types'
import type { StaffReservationListItem } from '@/features/staffReservations/types'
import { ReservationStatusBadge, PaymentStatusBadge } from '@/components/common/StatusBadge'
import { ReasonPrompt } from '@/components/common/ReasonPrompt'
import { ReceiptDialog } from '@/components/common/ReceiptDialog'
import { Field } from '@/components/common/Field'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { ErrorState, PageLoader } from '@/components/common/States'
import { ApiError } from '@/lib/api/error'
import { PaymentStatus } from '@/types/enums'
import { ChatPanel } from '@/features/chat/ChatPanel'

interface LocationState {
  item?: StaffReservationListItem
}

function fmt(iso: string): string {
  return new Date(iso).toLocaleString('ko-KR')
}

function errorMessage(error: unknown): string {
  return error instanceof ApiError ? error.message : '처리에 실패했습니다.'
}

// 영수증을 제공하는 결제 상태(PR #158). 그 외는 발급 대상이 아니라 백엔드가 409(RECEIPT_NOT_AVAILABLE), 결제 자체가 없으면 404다.
const RECEIPT_STATUSES: PaymentStatus[] = [
  PaymentStatus.PAID,
  PaymentStatus.OFFLINE_PAID,
  PaymentStatus.REFUNDED,
]

interface ItemRow {
  name: string
  quantity: string
  unitPrice: string
}

const EMPTY_ROW: ItemRow = { name: '', quantity: '1', unitPrice: '' }

// 항목 한 줄의 금액. 서버가 quantity × unitPrice로 다시 계산하므로 여기 값은 화면 표시용이다.
function rowAmount(row: ItemRow): number {
  const quantity = Number(row.quantity)
  const unitPrice = Number(row.unitPrice)
  if (!Number.isFinite(quantity) || !Number.isFinite(unitPrice)) return 0
  return quantity * unitPrice
}

function isRowValid(row: ItemRow): boolean {
  const quantity = Number(row.quantity)
  const unitPrice = Number(row.unitPrice)
  return (
    row.name.trim().length > 0 &&
    Number.isInteger(quantity) &&
    quantity > 0 &&
    Number.isInteger(unitPrice)
  )
}

// 진료비 청구 — 총액을 직접 입력하지 않고 청구 항목을 작성한다. 저장된 초안의 합계로 서버가
// 청구하므로(SA §8-7·§9-4), 이 폼은 항목 목록을 저장한 뒤 body 없이 청구를 호출한다.
// 할인·조정은 단가를 음수로 넣어 표현한다(수량은 항상 양수).
function ChargeForm({
  reservationId,
  mode = 'charge',
}: {
  reservationId: number
  // 'correction'이면 정정 재청구(기존 환불 결제를 대체). 항목 편집·저장 흐름은 정상 청구와 같고
  // 마지막 청구 호출만 다르다(SA §9-4).
  mode?: 'charge' | 'correction'
}) {
  const draftsQuery = useItemDrafts(reservationId)
  const [rows, setRows] = useState<ItemRow[] | null>(null)
  const charge = useChargePayment(reservationId)
  const correction = useCorrectionCharge(reservationId)
  const mutation = mode === 'correction' ? correction : charge

  // 저장해 둔 초안이 있으면 그대로 이어서 편집한다. 첫 로드 이후에는 사용자의 편집을 덮지 않는다.
  const effectiveRows =
    rows ??
    (draftsQuery.data && draftsQuery.data.items.length > 0
      ? draftsQuery.data.items.map((item) => ({
          name: item.name,
          quantity: String(item.quantity),
          unitPrice: String(item.unitPrice),
        }))
      : [EMPTY_ROW])

  const update = (index: number, patch: Partial<ItemRow>) =>
    setRows(effectiveRows.map((row, i) => (i === index ? { ...row, ...patch } : row)))
  const addRow = () => setRows([...effectiveRows, EMPTY_ROW])
  const removeRow = (index: number) =>
    setRows(effectiveRows.filter((_, i) => i !== index))

  const total = effectiveRows.reduce((sum, row) => sum + rowAmount(row), 0)
  // 서버도 같은 규칙을 검증한다 — 항목 1건 이상, 각 항목 유효, 합계 0 초과(SA §9-4).
  const canSubmit =
    effectiveRows.length > 0 && effectiveRows.every(isRowValid) && total > 0

  const submit = () =>
    mutation.mutate(
      effectiveRows.map<PaymentItemInput>((row) => ({
        name: row.name.trim(),
        quantity: Number(row.quantity),
        unitPrice: Number(row.unitPrice),
      })),
    )

  return (
    <div className="space-y-3">
      {effectiveRows.map((row, index) => (
        <div key={index} className="grid grid-cols-12 gap-2">
          <div className="col-span-5">
            <Field label="항목">
              <Input
                value={row.name}
                onChange={(e) => update(index, { name: e.target.value })}
                placeholder="예: 진찰료"
              />
            </Field>
          </div>
          <div className="col-span-2">
            <Field label="수량">
              <Input
                type="number"
                min={1}
                inputMode="numeric"
                value={row.quantity}
                onChange={(e) => update(index, { quantity: e.target.value })}
              />
            </Field>
          </div>
          <div className="col-span-4">
            <Field label="단가 (원)">
              <Input
                type="number"
                inputMode="numeric"
                value={row.unitPrice}
                onChange={(e) => update(index, { unitPrice: e.target.value })}
                placeholder="예: 68000 / 할인은 -5000"
              />
            </Field>
          </div>
          <div className="col-span-1 flex items-end">
            <Button
              variant="ghost"
              aria-label={`${index + 1}번 항목 삭제`}
              disabled={effectiveRows.length === 1}
              onClick={() => removeRow(index)}
            >
              ✕
            </Button>
          </div>
        </div>
      ))}

      <div className="flex items-center justify-between">
        <Button variant="outline" onClick={addRow}>
          항목 추가
        </Button>
        <p className="text-sm">
          합계 <strong>{total.toLocaleString('ko-KR')}원</strong>
        </p>
      </div>

      {mutation.isError && (
        <p className="text-sm text-destructive">{errorMessage(mutation.error)}</p>
      )}
      <Button disabled={!canSubmit || mutation.isPending} onClick={submit}>
        {mutation.isPending
          ? mode === 'correction'
            ? '정정 청구 중…'
            : '청구 중…'
          : mode === 'correction'
            ? '정정 재청구'
            : '진료비 청구'}
      </Button>
    </div>
  )
}

function PaymentSection({ reservationId }: { reservationId: number }) {
  const paymentsQuery = useStaffReservationPayments(reservationId)
  const settleOffline = useSettleOffline(reservationId)
  const refund = useRefundPayment(reservationId)
  const [receiptPaymentId, setReceiptPaymentId] = useState<number | null>(null)
  const [correctingId, setCorrectingId] = useState<number | null>(null)

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

  const activeId = activePaymentId(payments)

  return (
    <Card>
      <CardHeader>
        <CardTitle>결제 결과</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <ReceiptDialog
          paymentId={receiptPaymentId}
          scope="staff"
          fetcher={staffPaymentApi.getReceipt}
          onClose={() => setReceiptPaymentId(null)}
        />
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

            {RECEIPT_STATUSES.includes(p.status) && (
              <Button
                size="sm"
                variant="outline"
                onClick={() => setReceiptPaymentId(p.paymentId)}
              >
                영수증 보기
              </Button>
            )}

            {/* 정정 재청구는 활성(최신) 결제가 REFUNDED일 때만. 금액 정정은 항목 수정이 아니라
                기존 환불 결제를 대체하는 새 결제로만 한다(SA §9-4). */}
            {p.paymentId === activeId &&
              p.status === PaymentStatus.REFUNDED &&
              (correctingId === p.paymentId ? (
                <div className="space-y-2 rounded-md border p-3">
                  <p className="text-sm text-muted-foreground">
                    정정할 청구 항목을 다시 작성하세요. 기존 환불 결제를 대체하는 새
                    결제가 생성됩니다.
                  </p>
                  <ChargeForm reservationId={reservationId} mode="correction" />
                </div>
              ) : (
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => setCorrectingId(p.paymentId)}
                >
                  정정 재청구
                </Button>
              ))}
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

      <ChatPanel
        reservationId={id}
        reservationStatus={item.reservationStatus}
      />

      <PaymentSection reservationId={id} />

      <Button variant="ghost" asChild>
        <Link to="/staff/reservations">← 예약 목록으로</Link>
      </Button>
    </div>
  )
}
