// 병원 스태프 — 결제/환불 대시보드 (진료 완료 예약 기준).
// 상태 필터는 현재 불러온 페이지 안에서만 적용된다(백엔드가 상태별 재조회를 지원하지 않음 — §Phase B 설계 노트).
import { useState } from 'react'
import { useHospitalPayments, useRefundPayment, useSettleOffline } from '@/features/staffPayments/hooks'
import { staffPaymentApi } from '@/features/staffPayments/api'
import type { HospitalPaymentListItem } from '@/features/staffPayments/types'
import { ReasonPrompt } from '@/components/common/ReasonPrompt'
import { ReceiptDialog } from '@/components/common/ReceiptDialog'
import { PaymentStatusBadge } from '@/components/common/StatusBadge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { EmptyState, ErrorState, PageLoader } from '@/components/common/States'
import { ApiError } from '@/lib/api/error'
import { naiveDateTimeLabel } from '@/lib/seoulTime'
import { PaymentStatus } from '@/types/enums'

// 이 목록은 활성 결제가 붙은 행만 온다(SA §8-7 v1.64) — '청구 전' 예약은 여기 없고 예약 관리에서 청구한다.
const FILTERS = [
  { key: 'ALL', label: '전체' },
  { key: PaymentStatus.PENDING, label: '결제 진행중' },
  { key: PaymentStatus.OFFLINE_REQUIRED, label: '현장 수납 필요' },
  { key: PaymentStatus.PAID, label: '결제 완료' },
  { key: PaymentStatus.OFFLINE_PAID, label: '현장 수납 완료' },
  { key: PaymentStatus.REFUNDED, label: '환불 완료' },
] as const

function errorMessage(error: unknown): string {
  return error instanceof ApiError ? error.message : '처리에 실패했습니다.'
}

// 영수증을 제공하는 결제 상태(PR #158). 그 외는 발급 대상이 아니라 백엔드가 409(RECEIPT_NOT_AVAILABLE), 결제 자체가 없으면 404다.
const RECEIPT_STATUSES: PaymentStatus[] = [
  PaymentStatus.PAID,
  PaymentStatus.OFFLINE_PAID,
  PaymentStatus.REFUNDED,
]

function PaymentRow({
  item,
  onOpenReceipt,
}: {
  item: HospitalPaymentListItem
  onOpenReceipt: (paymentId: number) => void
}) {
  const settleOffline = useSettleOffline(item.reservationId)
  const refund = useRefundPayment(item.reservationId)

  return (
    <Card>
      <CardContent className="flex flex-wrap items-center justify-between gap-3 p-4">
        <div className="space-y-1">
          <div className="flex items-center gap-2">
            <span className="font-semibold">{item.petName}</span>
            <PaymentStatusBadge status={item.paymentStatus} />
          </div>
          <p className="text-sm text-muted-foreground">
            진료 {naiveDateTimeLabel(item.reservedAt)} ·{' '}
            {item.amount.toLocaleString('ko-KR')}원
          </p>
          {item.paidAt && (
            <p className="text-xs text-muted-foreground">
              결제 완료 · {naiveDateTimeLabel(item.paidAt)}
            </p>
          )}
          {item.offlineSettledAt && (
            <p className="text-xs text-muted-foreground">
              현장 수납 완료 · {naiveDateTimeLabel(item.offlineSettledAt)}
            </p>
          )}
          {item.refundedAt && (
            <p className="text-xs text-muted-foreground">
              환불 완료 · {naiveDateTimeLabel(item.refundedAt)}
            </p>
          )}
        </div>

        {item.paymentStatus === PaymentStatus.OFFLINE_REQUIRED && (
          <div className="space-y-1">
            {settleOffline.isError && (
              <p className="text-sm text-destructive">{errorMessage(settleOffline.error)}</p>
            )}
            <Button
              size="sm"
              disabled={settleOffline.isPending}
              onClick={() => settleOffline.mutate(item.paymentId)}
            >
              {settleOffline.isPending ? '처리 중…' : '현장 수납 완료'}
            </Button>
          </div>
        )}

        {item.paymentStatus === PaymentStatus.PAID && (
          <ReasonPrompt
            triggerLabel="전액 환불"
            triggerVariant="destructive"
            confirmLabel="환불 확정"
            maxLength={200}
            pending={refund.isPending}
            errorMessage={refund.isError ? errorMessage(refund.error) : undefined}
            onConfirm={(reason) =>
              refund.mutate({ paymentId: item.paymentId, reason })
            }
          />
        )}

        {RECEIPT_STATUSES.includes(item.paymentStatus) && (
          <Button
            size="sm"
            variant="outline"
            onClick={() => onOpenReceipt(item.paymentId)}
          >
            영수증
          </Button>
        )}
      </CardContent>
    </Card>
  )
}

export function StaffPaymentsPage() {
  const [filter, setFilter] = useState<(typeof FILTERS)[number]['key']>('ALL')
  const [page, setPage] = useState(0)
  const [receiptPaymentId, setReceiptPaymentId] = useState<number | null>(null)
  const query = useHospitalPayments(page, 20)

  const items = query.data?.content ?? []
  const filtered = items.filter((item) => {
    if (filter === 'ALL') return true
    return item.paymentStatus === filter
  })

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">결제 관리</h1>
      <p className="text-sm text-muted-foreground">
        진료 완료된 예약의 결제 현황입니다. 상태 필터는 지금 불러온 목록 안에서만 적용돼요.
      </p>

      <div className="flex flex-wrap gap-1 border-b pb-2">
        {FILTERS.map((f) => (
          <Button
            key={f.key}
            size="sm"
            variant={f.key === filter ? 'default' : 'ghost'}
            onClick={() => setFilter(f.key)}
          >
            {f.label}
          </Button>
        ))}
      </div>

      {query.isLoading && <PageLoader />}
      {query.isError && <ErrorState onRetry={() => query.refetch()} />}
      {query.data && filtered.length === 0 && (
        <EmptyState message="해당 상태의 결제가 없어요." />
      )}

      <div className="space-y-3">
        {filtered.map((item) => (
          <PaymentRow
            key={item.reservationId}
            item={item}
            onOpenReceipt={setReceiptPaymentId}
          />
        ))}
      </div>

      <ReceiptDialog
        paymentId={receiptPaymentId}
        scope="staff"
        fetcher={staffPaymentApi.getReceipt}
        onClose={() => setReceiptPaymentId(null)}
      />

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
