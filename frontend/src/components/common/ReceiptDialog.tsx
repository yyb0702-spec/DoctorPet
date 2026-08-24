// JSON 영수증 뷰어(PR #158). 보호자·스태프가 공유한다 — 조회 엔드포인트만 다르므로
// fetcher를 주입받아 같은 화면을 그린다. paymentId가 null이면 닫힌 상태다.
import { useQuery } from '@tanstack/react-query'
import { X } from 'lucide-react'
import type { Receipt } from '@/features/payments/types'
import { PaymentStatusBadge } from '@/components/common/StatusBadge'
import { Button } from '@/components/ui/button'
import { ApiError } from '@/lib/api/error'
import { speciesLabel } from '@/lib/species'
import { PaymentChannel } from '@/types/enums'

const REFUND_STATUS_LABEL: Record<string, string> = {
  REQUESTED: '환불 처리 중',
  COMPLETED: '환불 완료',
  FAILED: '환불 실패',
}

function fmt(iso: string): string {
  return new Date(iso).toLocaleString('ko-KR')
}

function won(amount: number): string {
  return `${amount.toLocaleString('ko-KR')}원`
}

export function ReceiptDialog({
  paymentId,
  scope,
  fetcher,
  onClose,
}: {
  paymentId: number | null
  // 보호자/스태프 캐시 충돌을 막기 위한 스코프. 같은 paymentId라도 조회 권한이 다르다.
  scope: 'guardian' | 'staff'
  fetcher: (paymentId: number) => Promise<Receipt>
  onClose: () => void
}) {
  const query = useQuery({
    queryKey: ['receipt', scope, paymentId],
    queryFn: () => fetcher(paymentId as number),
    enabled: paymentId != null,
    // 영수증은 결제 상태(환불·정정 재청구)에 따라 바뀌므로 열 때마다 다시 불러온다. 전역
    // staleTime(30s)을 그대로 두면 환불 직후 30초 내 재열람 시 캐시된 PAID가 남는다(PR #180 리뷰).
    staleTime: 0,
  })

  if (paymentId == null) return null

  const receipt = query.data

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      role="dialog"
      aria-modal="true"
      aria-label="영수증"
      onClick={onClose}
    >
      <div
        className="max-h-[85vh] w-full max-w-md overflow-y-auto rounded-xl bg-background p-5 shadow-lg"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-bold">영수증</h2>
          <Button
            size="sm"
            variant="ghost"
            aria-label="닫기"
            onClick={onClose}
          >
            <X className="h-4 w-4" />
          </Button>
        </div>

        {query.isLoading && (
          <p className="py-8 text-center text-sm text-muted-foreground">
            영수증을 불러오는 중…
          </p>
        )}

        {query.isError && (
          <div className="space-y-3 py-6 text-center">
            <p className="text-sm text-destructive">
              {query.error instanceof ApiError
                ? query.error.message
                : '영수증을 불러오지 못했습니다.'}
            </p>
            <Button size="sm" variant="outline" onClick={() => query.refetch()}>
              다시 시도
            </Button>
          </div>
        )}

        {receipt && (
          <div className="mt-3 space-y-4 text-sm">
            <div className="flex items-center justify-between">
              <span className="text-2xl font-bold">{won(receipt.totalAmount)}</span>
              <PaymentStatusBadge status={receipt.status} />
            </div>

            <dl className="grid grid-cols-[84px_1fr] gap-y-1.5">
              <dt className="text-muted-foreground">반려동물</dt>
              <dd>
                {receipt.petName} ({speciesLabel(receipt.petSpecies)})
              </dd>
              {receipt.cardBrandSnapshot && (
                <>
                  <dt className="text-muted-foreground">결제 카드</dt>
                  <dd>
                    {receipt.cardBrandSnapshot} ****{receipt.cardLast4Snapshot}
                    {receipt.paymentChannel === PaymentChannel.OFFLINE &&
                      ' · 현장 수납'}
                  </dd>
                </>
              )}
              {receipt.paidAt && (
                <>
                  <dt className="text-muted-foreground">결제 일시</dt>
                  <dd>{fmt(receipt.paidAt)}</dd>
                </>
              )}
              {receipt.offlineSettledAt && (
                <>
                  <dt className="text-muted-foreground">현장 수납</dt>
                  <dd>{fmt(receipt.offlineSettledAt)}</dd>
                </>
              )}
              {receipt.refundedAt && (
                <>
                  <dt className="text-muted-foreground">환불</dt>
                  <dd>
                    {fmt(receipt.refundedAt)}
                    {receipt.refundStatus &&
                      ` · ${REFUND_STATUS_LABEL[receipt.refundStatus] ?? receipt.refundStatus}`}
                  </dd>
                </>
              )}
            </dl>

            {/* 청구 항목. 항목화 이전 결제는 빈 배열이라 항목 표를 숨기고 총액만 보여준다. */}
            {receipt.items.length > 0 && (
              <div className="border-t pt-3">
                <ul className="space-y-1.5">
                  {receipt.items.map((item, i) => (
                    <li key={i} className="flex justify-between gap-2">
                      <span className="truncate">
                        {item.name}
                        {item.quantity > 1 && (
                          <span className="text-muted-foreground">
                            {' '}
                            × {item.quantity}
                          </span>
                        )}
                      </span>
                      <span
                        className={
                          item.amount < 0 ? 'text-emerald-600' : undefined
                        }
                      >
                        {won(item.amount)}
                      </span>
                    </li>
                  ))}
                </ul>
                <div className="mt-2 flex justify-between border-t pt-2 font-semibold">
                  <span>합계</span>
                  <span>{won(receipt.totalAmount)}</span>
                </div>
              </div>
            )}

            <p className="text-xs text-muted-foreground">
              결제 번호 {receipt.paymentId} · 예약 번호 {receipt.reservationId}
            </p>
          </div>
        )}
      </div>
    </div>
  )
}
