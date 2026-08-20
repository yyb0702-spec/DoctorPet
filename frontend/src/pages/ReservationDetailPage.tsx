// 예약 상세 (실연동, PR #68). 상태별 안내 배너 + 진행 스텝 + 취소.
import { useState } from 'react'
import { useParams, Link } from 'react-router-dom'
import { MapPin, Phone } from 'lucide-react'
import {
  useCancelReservation,
  useReservationDetail,
  useUpdateReservationPaymentMethod,
} from '@/features/reservations/hooks'
import { canChangePaymentMethod } from '@/features/reservations/paymentMethodChange'
import {
  usePaymentMethods,
  useRechargePayment,
  useReservationPayments,
} from '@/features/payments/hooks'
import { paymentApi } from '@/features/payments/api'
import {
  activePaymentId,
  rechargeablePaymentId,
} from '@/features/payments/activePayment'
import type { PaymentChargeResult } from '@/features/payments/types'
import { paymentMethodLabels } from '@/features/payments/methodLabel'
import { ReceiptDialog } from '@/components/common/ReceiptDialog'
import { speciesLabel } from '@/lib/species'
import { ReservationProgress } from '@/features/reservations/ReservationProgress'
import {
  PaymentStatusBadge,
  ReservationProgressBadge,
} from '@/components/common/StatusBadge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { ErrorState, PageLoader } from '@/components/common/States'
import { cn } from '@/lib/utils'
import { PaymentChannel, PaymentStatus, ReservationStatus } from '@/types/enums'
import type { PaymentStatus as PaymentStatusType } from '@/types/enums'
import { ApiError } from '@/lib/api/error'
import { ChatPanel } from '@/features/chat/ChatPanel'
import { ReservationReviewCard } from '@/features/reviews/ReservationReviewCard'

// 취소 가능한 예약 상태 (SA §5-1: REQUESTED·CONFIRMED, 서버가 리드타임 최종 검증).
const CANCELABLE: ReservationStatus[] = [
  ReservationStatus.REQUESTED,
  ReservationStatus.CONFIRMED,
]

// 영수증을 제공하는 결제 상태(PR #158). 그 외(PENDING·OFFLINE_REQUIRED)는 발급 대상이 아니라 백엔드가 409(RECEIPT_NOT_AVAILABLE)를 준다(결제 자체가 없으면 404).
const RECEIPT_STATUSES: PaymentStatusType[] = [
  PaymentStatus.PAID,
  PaymentStatus.OFFLINE_PAID,
  PaymentStatus.REFUNDED,
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

/**
 * 자동 결제 실패(OFFLINE_REQUIRED)를 보호자가 직접 다시 결제하는 패널(SA §9-4, 고도화 3.3).
 *
 * 재청구는 쓸 결제수단을 명시적으로 지정해야 시작된다. 다만 서버(preRecordRecovery)가 요구하는 것은
 * "본인 소유 + ACTIVE"까지이고 **직전에 실패한 수단과 달라야 한다는 제약은 없다** — 화면도 그렇게
 * 강제하지 않으므로, 같은 수단을 다시 고르면 그대로 다시 승인 시도가 나간다.
 * 응답이 201이어도 결제 성공이 아니다: 승인 실패도 레코드가 생기고 status로 내려오므로
 * 2xx를 성공으로 뭉개지 않고 status를 읽어 결과를 보여준다.
 */
function RechargePanel({ reservationId }: { reservationId: number }) {
  const methodsQuery = usePaymentMethods()
  const recharge = useRechargePayment(reservationId)
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [result, setResult] = useState<PaymentChargeResult | null>(null)

  // 백엔드가 ACTIVE만 반환하지만, 삭제·만료 수단으로는 재청구가 성립하지 않으므로 화면에서도 거른다.
  const methods = (methodsQuery.data ?? []).filter((m) => m.status === 'ACTIVE')
  const labels = paymentMethodLabels(methods)

  if (methodsQuery.isLoading) {
    return <p className="text-xs text-muted-foreground">결제수단을 불러오는 중…</p>
  }

  if (methods.length === 0) {
    return (
      <div className="space-y-2 rounded-md border p-3">
        <p className="text-sm text-muted-foreground">
          다시 결제하려면 사용할 결제수단이 필요해요.
        </p>
        <Button size="sm" variant="outline" asChild>
          <Link to="/payment-methods">결제수단 등록하러 가기</Link>
        </Button>
      </div>
    )
  }

  // 재청구 성공(PAID)이면 아래 결제 내역이 새 결제로 갱신되므로 패널은 결과만 짧게 알린다.
  if (result?.status === PaymentStatus.PAID) {
    return (
      <p className="rounded-md bg-emerald-50 p-3 text-sm text-emerald-900">
        결제가 완료됐어요.
      </p>
    )
  }
  if (result?.status === PaymentStatus.PENDING) {
    return (
      <p className="rounded-md bg-amber-50 p-3 text-sm text-amber-900">
        결제 결과를 확인하고 있어요. 결과가 정해지면 알려드려요.
      </p>
    )
  }

  return (
    <div className="space-y-2 rounded-md border p-3">
      <p className="text-sm text-muted-foreground">
        다시 결제할 결제수단을 선택하세요. 금액은 원래 청구 금액 그대로예요.
      </p>
      <div className="grid gap-1">
        {methods.map((m) => (
          <label
            key={m.id}
            className="flex cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-sm hover:bg-muted"
          >
            <input
              type="radio"
              name={`recharge-method-${reservationId}`}
              value={m.id}
              checked={selectedId === m.id}
              onChange={() => setSelectedId(m.id)}
            />
            <span>{labels.get(m.id)}</span>
          </label>
        ))}
      </div>
      {/* 재청구했는데 또 실패한 경우. 상태는 다시 OFFLINE_REQUIRED라 패널이 그대로 열려 있다. */}
      {/* 위 세 상태(PAID·PENDING·OFFLINE_REQUIRED) 밖의 응답이 오면 성공으로도 실패로도 단정하지
          않고, 침묵 대신 확인이 필요하다는 것만 알린다. */}
      {result &&
        result.status !== PaymentStatus.OFFLINE_REQUIRED && (
          <p className="text-sm text-muted-foreground">
            결제 상태를 확인해 주세요. (상태: {result.status})
          </p>
        )}
      {result?.status === PaymentStatus.OFFLINE_REQUIRED && (
        <p className="text-sm text-destructive">
          결제에 실패했어요. 다른 결제수단으로 다시 시도하거나 병원에서 수납해 주세요.
          {result.failureReason && ` (${result.failureReason})`}
        </p>
      )}
      {recharge.isError && (
        <p className="text-sm text-destructive">
          {recharge.error instanceof ApiError
            ? recharge.error.message
            : '다시 결제하지 못했어요.'}
        </p>
      )}
      <Button
        size="sm"
        disabled={selectedId === null || recharge.isPending}
        onClick={() => {
          if (selectedId === null) return
          recharge.mutate(selectedId, { onSuccess: setResult })
        }}
      >
        {recharge.isPending ? '결제 중…' : '이 수단으로 결제'}
      </Button>
    </div>
  )
}

/*
 * 진료 전 예약의 결제수단을 바꾸는 패널(SA §8-7, PR #152).
 *
 * 예약 상세 응답에는 지금 지정된 결제수단이 없어서(ReservationDetailResponse에 필드 부재) "현재 카드"를
 * 보여줄 수 없다 — 바꿀 수단만 고르게 하고 결과만 알린다. 서버는 예약 행을 잠근 뒤 판정하므로,
 * 청구가 먼저 커밋된 경우 RESERVATION_019로 거절되고 그때는 결제 내역을 다시 읽어 패널이 닫힌다.
 */
function PaymentMethodChangePanel({ reservationId }: { reservationId: number }) {
  const methodsQuery = usePaymentMethods()
  const changeMethod = useUpdateReservationPaymentMethod(reservationId)
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [changed, setChanged] = useState(false)

  // 서버가 ACTIVE만 받으므로 화면에서도 ACTIVE만 고를 수 있게 한다.
  const methods = (methodsQuery.data ?? []).filter((m) => m.status === 'ACTIVE')
  const labels = paymentMethodLabels(methods)

  if (methodsQuery.isLoading || methods.length === 0) return null

  return (
    <Card>
      <CardHeader>
        <CardTitle>결제수단</CardTitle>
      </CardHeader>
      <CardContent className="space-y-2 text-sm">
        <p className="text-muted-foreground">
          진료비는 진료 완료 후 자동 결제돼요. 진료 시작 전까지 사용할 결제수단을 바꿀 수 있어요.
        </p>
        <div className="grid gap-1">
          {methods.map((m) => (
            <label
              key={m.id}
              className="flex cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 hover:bg-muted"
            >
              <input
                type="radio"
                name={`change-method-${reservationId}`}
                value={m.id}
                checked={selectedId === m.id}
                onChange={() => {
                  setSelectedId(m.id)
                  setChanged(false)
                }}
              />
              <span>{labels.get(m.id)}</span>
              {m.isDefault && <Badge variant="muted">기본</Badge>}
            </label>
          ))}
        </div>
        {changed && (
          <p className="text-emerald-700">이 결제수단으로 변경했어요.</p>
        )}
        {changeMethod.isError && (
          <p className="text-destructive">
            {changeMethod.error instanceof ApiError
              ? changeMethod.error.message
              : '결제수단을 변경하지 못했어요.'}
          </p>
        )}
        <Button
          size="sm"
          disabled={selectedId === null || changeMethod.isPending}
          onClick={() => {
            if (selectedId === null) return
            changeMethod.mutate(selectedId, {
              onSuccess: () => setChanged(true),
            })
          }}
        >
          {changeMethod.isPending ? '변경 중…' : '이 수단으로 변경'}
        </Button>
      </CardContent>
    </Card>
  )
}

export function ReservationDetailPage() {
  const { reservationId } = useParams()
  const id = Number(reservationId)
  const detailQuery = useReservationDetail(id)
  const paymentsQuery = useReservationPayments(id)
  const cancel = useCancelReservation()
  const [receiptPaymentId, setReceiptPaymentId] = useState<number | null>(null)

  if (detailQuery.isLoading) return <PageLoader />
  if (detailQuery.isError || !detailQuery.data)
    return <ErrorState onRetry={() => detailQuery.refetch()} />

  const r = detailQuery.data
  const payments = paymentsQuery.data ?? []
  // 활성 판정은 최신 생성분 추론이다(응답에 supersededAt이 없다). 실제 성립 여부는 서버의 조건부 전이가 강제한다.
  const activeId = activePaymentId(payments)
  const rechargeableId = rechargeablePaymentId(payments)
  const cancelable = CANCELABLE.includes(r.reservationStatus)
  // 결제 완료 판정은 활성 결제 상태로 한다 — 대체된 과거 결제가 PAID로 남아 있어도
  // 지금 수납된 상태가 아니면 후기 자격이 아니다(SA §5-2 활성 결제).
  const activePayment = payments.find((p) => p.paymentId === activeId)
  const reviewable =
    activePayment?.status === PaymentStatus.PAID ||
    activePayment?.status === PaymentStatus.OFFLINE_PAID
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
              {r.petSnapshot.name} ({speciesLabel(r.petSnapshot.species)})
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

      {/* 진료 전 결제수단 재지정. 결제 선기록이 생기면(=청구 시작) 조건이 깨져 사라진다. */}
      {canChangePaymentMethod(r.reservationStatus, paymentsQuery.data) && (
        <PaymentMethodChangePanel reservationId={id} />
      )}

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
                  <div className="flex items-center gap-2">
                    {/* 결제가 여러 건이면 어느 것이 현재 청구인지 구분해 준다. 대체된 결제는
                        지우지 않고 이력으로 남기므로(SA §9-4) 목록에 함께 보인다. */}
                    {payments.length > 1 && p.paymentId !== activeId && (
                      <Badge variant="muted">대체됨</Badge>
                    )}
                    <PaymentStatusBadge status={p.status} />
                  </div>
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
                {RECEIPT_STATUSES.includes(p.status) && (
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => setReceiptPaymentId(p.paymentId)}
                  >
                    영수증 보기
                  </Button>
                )}

                {/* 셀프 복구(다시 결제)는 활성 결제가 OFFLINE_REQUIRED일 때만. PENDING에서는
                    승인 여부가 불확정이라 절대 노출하지 않는다 — 이중 결제 위험(SA §9-4·§9-7). */}
                {p.paymentId === rechargeableId && (
                  <RechargePanel reservationId={r.reservationId} />
                )}
              </div>
            ))}
          </CardContent>
        </Card>
      )}

      {/* 후기 — 결제가 완료된 진료만 작성할 수 있다(백엔드 REVIEW_003과 같은 조건).
          환불(REFUNDED)은 자격이 아니라, 백엔드가 후기를 지우고 작성 기회를 되돌린다.
          여기서는 카드를 띄울지만 거른다 — 내 후기와 실제 작성 자격은 카드가 서버에 묻는다. */}
      {reviewable && <ReservationReviewCard reservationId={r.reservationId} />}

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

      <ReceiptDialog
        paymentId={receiptPaymentId}
        scope="guardian"
        fetcher={paymentApi.getReceipt}
        onClose={() => setReceiptPaymentId(null)}
      />
    </div>
  )
}
