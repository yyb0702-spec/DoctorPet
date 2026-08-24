// 예약 상세에서 여는 "우리 병원 진료·결제 이력" 섹션. 예약으로 회원을 해석해 자병원 이력만 페이지로 본다.
import { useState } from 'react'
import { useMemberHistory } from './hooks'
import { staffPaymentApi } from '@/features/staffPayments/api'
import {
  PaymentStatusBadge,
  ReservationStatusBadge,
} from '@/components/common/StatusBadge'
import { ReceiptDialog } from '@/components/common/ReceiptDialog'
import { Button } from '@/components/ui/button'
import { naiveDateTimeLabel } from '@/lib/seoulTime'
import { PaymentStatus } from '@/types/enums'

// 영수증은 결제가 실제로 남은 상태에서만 제공된다(백엔드 계약과 동일, PR #158).
const RECEIPT_STATUSES: PaymentStatus[] = [
  PaymentStatus.PAID,
  PaymentStatus.OFFLINE_PAID,
  PaymentStatus.REFUNDED,
]

export function MemberHistorySection({
  reservationId,
}: {
  reservationId: number
}) {
  const [open, setOpen] = useState(false)
  const [page, setPage] = useState(0)
  const [receiptPaymentId, setReceiptPaymentId] = useState<number | null>(null)
  const query = useMemberHistory(reservationId, page, open)
  const data = query.data

  return (
    <div className="border-t pt-3">
      <Button
        variant="ghost"
        size="sm"
        className="text-xs"
        onClick={() => setOpen((v) => !v)}
      >
        {open ? '우리 병원 이력 접기' : '우리 병원 진료·결제 이력 보기'}
      </Button>

      {open && (
        <div className="mt-2 space-y-2">
          {query.isLoading && (
            <p className="text-sm text-muted-foreground">불러오는 중…</p>
          )}
          {query.isError && (
            <p className="text-sm text-destructive">이력을 불러오지 못했습니다.</p>
          )}
          {data && data.content.length === 0 && (
            <p className="text-sm text-muted-foreground">
              우리 병원 진료 이력이 없습니다.
            </p>
          )}

          {data?.content.map((row) => (
            <div
              key={row.reservationId}
              className="flex items-center justify-between gap-3 rounded-md border p-3 text-sm"
            >
              <div className="space-y-0.5">
                <div className="flex items-center gap-2">
                  <span className="font-medium">{row.petName}</span>
                  <ReservationStatusBadge status={row.reservationStatus} />
                </div>
                <p className="text-xs text-muted-foreground">
                  {naiveDateTimeLabel(row.reservedAt)}
                </p>
              </div>
              <div className="flex items-center gap-2">
                {row.paymentStatus ? (
                  <>
                    <PaymentStatusBadge status={row.paymentStatus} />
                    {row.amount != null && (
                      <span className="text-xs text-muted-foreground">
                        {row.amount.toLocaleString('ko-KR')}원
                      </span>
                    )}
                    {row.paymentId != null &&
                      RECEIPT_STATUSES.includes(row.paymentStatus) && (
                        <Button
                          variant="outline"
                          size="sm"
                          className="text-xs"
                          onClick={() => setReceiptPaymentId(row.paymentId)}
                        >
                          영수증
                        </Button>
                      )}
                  </>
                ) : (
                  <span className="text-xs text-muted-foreground">미청구</span>
                )}
              </div>
            </div>
          ))}

          {data && data.totalPages > 1 && (
            <div className="flex items-center justify-center gap-3 pt-1">
              <Button
                variant="outline"
                size="sm"
                disabled={data.first || query.isFetching}
                onClick={() => setPage((p) => p - 1)}
              >
                이전
              </Button>
              <span className="text-xs text-muted-foreground">
                {data.page + 1} / {data.totalPages}
              </span>
              <Button
                variant="outline"
                size="sm"
                disabled={data.last || query.isFetching}
                onClick={() => setPage((p) => p + 1)}
              >
                다음
              </Button>
            </div>
          )}
        </div>
      )}

      <ReceiptDialog
        paymentId={receiptPaymentId}
        scope="staff"
        fetcher={staffPaymentApi.getReceipt}
        onClose={() => setReceiptPaymentId(null)}
      />
    </div>
  )
}
