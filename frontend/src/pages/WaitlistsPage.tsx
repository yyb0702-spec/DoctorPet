// 내 예약 대기열 — 조회·취소 + OFFERED 승급 제안 수락/거절.
// 대기열 응답엔 슬롯 시각·병원 정보가 없어 slotId·신청시각·상태·만료까지만 표시한다.
import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import type { Waitlist, WaitlistStatus } from '@/features/waitlist/types'
import { WaitlistStatus as Status } from '@/features/waitlist/types'
import {
  useAcceptWaitlist,
  useCancelWaitlist,
  useMyWaitlists,
  useRejectWaitlist,
} from '@/features/waitlist/hooks'
import { usePets } from '@/features/pets/hooks'
import { usePaymentMethods } from '@/features/payments/hooks'
import { Card, CardContent } from '@/components/ui/card'
import { Badge, type BadgeProps } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { EmptyState, ErrorState, PageLoader } from '@/components/common/States'
import { ApiError } from '@/lib/api/error'
import { speciesLabel } from '@/lib/species'

// 상태별 배지 라벨·톤.
const STATUS_META: Record<
  WaitlistStatus,
  { label: string; variant: BadgeProps['variant'] }
> = {
  WAITING: { label: '대기중', variant: 'secondary' },
  OFFERED: { label: '제안 도착', variant: 'warning' },
  ACCEPTED: { label: '수락됨', variant: 'success' },
  REJECTED: { label: '거절', variant: 'muted' },
  EXPIRED: { label: '만료', variant: 'muted' },
  CANCELED: { label: '취소', variant: 'muted' },
}

function dateTimeLabel(iso: string): string {
  return new Date(iso).toLocaleString('ko-KR', {
    month: 'long',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

// 제안 만료까지 남은 ms를 1초마다 갱신한다. 버튼 활성 여부도 이 값으로 판단한다.
// 초기값은 useState 이니셜라이저가 잡고(카드는 waitlistId로 keyed라 offerExpiresAt이 고정),
// 만료에 도달하면 인터벌을 멈춘다 — 이후로는 갱신할 값이 없다.
// expiresAt이 null이면(호출부 가드가 바뀌는 등) new Date(null)이 NaN을 만들어 "NaN:NaN"이
// 표시될 수 있으니, 여기서 곧장 이미 만료된 것으로 취급한다(PR #188 리뷰).
function useCountdown(expiresAt: string | null): number {
  const target = expiresAt ? new Date(expiresAt).getTime() : 0
  const [remainingMs, setRemainingMs] = useState(() => target - Date.now())
  useEffect(() => {
    if (target - Date.now() <= 0) return
    const id = window.setInterval(() => {
      const remaining = target - Date.now()
      setRemainingMs(remaining)
      if (remaining <= 0) window.clearInterval(id)
    }, 1000)
    return () => window.clearInterval(id)
  }, [target])
  return remainingMs
}

function formatCountdown(remainingMs: number): string {
  const totalSec = Math.floor(remainingMs / 1000)
  const mm = String(Math.floor(totalSec / 60)).padStart(2, '0')
  const ss = String(totalSec % 60).padStart(2, '0')
  return `${mm}:${ss}`
}

// 수락 폼 — 펫·결제수단을 골라 예약을 생성한다(예약 요청 패널과 동일한 선택 UI).
// onCancel: 마음이 바뀌면 거절 선택지로 돌아갈 수 있어야 한다 — 제한 시간 내 응답이라
// 되돌아갈 방법이 없으면 사용자가 막다른 골목에 갇힌다(PR #188 리뷰).
function OfferAcceptForm({
  waitlistId,
  onCancel,
}: {
  waitlistId: number
  onCancel: () => void
}) {
  const navigate = useNavigate()
  const petsQuery = usePets()
  const methodsQuery = usePaymentMethods()
  const accept = useAcceptWaitlist()

  const [petId, setPetId] = useState<number | null>(null)
  const [paymentMethodId, setPaymentMethodId] = useState<number | null>(null)

  const pets = petsQuery.data ?? []
  const methods = (methodsQuery.data ?? []).filter((m) => m.status === 'ACTIVE')
  const canSubmit = petId != null && paymentMethodId != null

  if (petsQuery.isLoading || methodsQuery.isLoading) {
    return <PageLoader label="선택 정보 불러오는 중…" />
  }

  // 사전조건 미충족이면 등록으로 유도(예약 요청과 동일).
  if (pets.length === 0 || methods.length === 0) {
    return (
      <div className="space-y-2">
        <div className="rounded-md bg-accent p-3 text-sm text-accent-foreground">
          수락하려면{' '}
          {pets.length === 0 && (
            <Link to="/pets" className="font-medium underline">
              펫 프로필
            </Link>
          )}
          {pets.length === 0 && methods.length === 0 && ' 과 '}
          {methods.length === 0 && (
            <Link to="/payment-methods" className="font-medium underline">
              결제수단
            </Link>
          )}{' '}
          등록이 필요합니다.
        </div>
        <Button size="sm" variant="outline" onClick={onCancel}>
          뒤로
        </Button>
      </div>
    )
  }

  const handleAccept = () => {
    if (petId == null || paymentMethodId == null) return
    accept.mutate(
      { waitlistId, input: { petId, paymentMethodId } },
      {
        onSuccess: (reservation) =>
          navigate(`/reservations/${reservation.reservationId}`),
      },
    )
  }

  return (
    <div className="space-y-3 rounded-md border p-3">
      <div className="space-y-2">
        <p className="text-sm font-medium">반려동물</p>
        <div className="flex flex-wrap gap-2">
          {pets.map((p) => (
            <Button
              key={p.petId}
              type="button"
              size="sm"
              variant={petId === p.petId ? 'default' : 'outline'}
              onClick={() => setPetId(p.petId)}
            >
              {p.name} ({speciesLabel(p.species)})
            </Button>
          ))}
        </div>
      </div>
      <div className="space-y-2">
        <p className="text-sm font-medium">결제수단</p>
        <div className="flex flex-wrap gap-2">
          {methods.map((m) => (
            <Button
              key={m.id}
              type="button"
              size="sm"
              variant={paymentMethodId === m.id ? 'default' : 'outline'}
              onClick={() => setPaymentMethodId(m.id)}
            >
              {m.cardBrand ?? '카드'} ****{m.cardLast4 ?? '****'}
            </Button>
          ))}
        </div>
      </div>
      {accept.isError && (
        <p className="text-sm text-destructive">
          {accept.error instanceof ApiError
            ? accept.error.message
            : '예약 수락에 실패했습니다.'}
        </p>
      )}
      <div className="flex gap-2">
        <Button
          variant="outline"
          disabled={accept.isPending}
          onClick={onCancel}
        >
          뒤로
        </Button>
        <Button
          className="flex-1"
          disabled={!canSubmit || accept.isPending}
          onClick={handleAccept}
        >
          {accept.isPending ? '예약 생성 중…' : '예약 확정'}
        </Button>
      </div>
    </div>
  )
}

// OFFERED 카드 본문 — 카운트다운 + 수락/거절. 만료되면 응답 액션을 막는다.
function OfferSection({ item }: { item: Waitlist }) {
  const reject = useRejectWaitlist()
  const [accepting, setAccepting] = useState(false)
  const remainingMs = useCountdown(item.offerExpiresAt)
  const expired = remainingMs <= 0

  return (
    <div className="space-y-3 rounded-lg bg-amber-50 px-3 py-3">
      {expired ? (
        <p className="text-sm font-medium text-muted-foreground">
          응답 시간이 만료되었습니다. 곧 목록에서 정리됩니다.
        </p>
      ) : (
        <>
          <p className="text-sm font-semibold text-amber-700">
            수락 마감까지 {formatCountdown(remainingMs)}
          </p>
          {!accepting && (
            <div className="flex gap-2">
              <Button size="sm" onClick={() => setAccepting(true)}>
                수락
              </Button>
              <Button
                size="sm"
                variant="outline"
                disabled={reject.isPending}
                onClick={() => reject.mutate(item.waitlistId)}
              >
                거절
              </Button>
            </div>
          )}
          {reject.isError && (
            <p className="text-sm text-destructive">
              {reject.error instanceof ApiError
                ? reject.error.message
                : '제안 거절에 실패했습니다.'}
            </p>
          )}
          {accepting && (
            <OfferAcceptForm
              waitlistId={item.waitlistId}
              onCancel={() => setAccepting(false)}
            />
          )}
        </>
      )}
    </div>
  )
}

function WaitlistCard({ item }: { item: Waitlist }) {
  const cancel = useCancelWaitlist()
  // 백엔드가 유니온 밖의 상태(신규 enum)를 보내도 카드가 죽지 않도록 폴백한다 —
  // status는 컴파일 타임 타입일 뿐 런타임 응답은 이를 벗어날 수 있다.
  const meta = STATUS_META[item.status] ?? {
    label: item.status,
    variant: 'muted' as const,
  }

  return (
    <Card>
      <CardContent className="flex flex-col gap-3 p-5">
        <div className="flex items-start justify-between gap-3">
          <div className="space-y-0.5">
            <p className="font-semibold">슬롯 #{item.slotId}</p>
            <p className="text-sm text-muted-foreground">
              신청 {dateTimeLabel(item.requestedAt)}
            </p>
          </div>
          <Badge variant={meta.variant}>{meta.label}</Badge>
        </div>

        {item.status === Status.OFFERED && item.offerExpiresAt && (
          <OfferSection item={item} />
        )}

        {item.status === Status.WAITING && (
          <div className="flex flex-col items-end gap-1">
            {cancel.isError && (
              <p className="text-sm text-destructive">
                {cancel.error instanceof ApiError
                  ? cancel.error.message
                  : '대기 취소에 실패했습니다.'}
              </p>
            )}
            <Button
              size="sm"
              variant="outline"
              disabled={cancel.isPending}
              onClick={() => cancel.mutate(item.waitlistId)}
            >
              대기 취소
            </Button>
          </div>
        )}
      </CardContent>
    </Card>
  )
}

export function WaitlistsPage() {
  const { data, isLoading, isError, refetch } = useMyWaitlists()
  const items = data ?? []

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">내 대기열</h1>
      <p className="text-sm text-muted-foreground">
        만석 슬롯의 대기 신청과 승급 제안을 확인합니다.
      </p>

      {isLoading && <PageLoader />}
      {isError && <ErrorState onRetry={() => refetch()} />}
      {data && items.length === 0 && (
        <EmptyState message="대기 신청 내역이 없습니다." />
      )}

      <div className="grid gap-3">
        {items.map((item) => (
          <WaitlistCard key={item.waitlistId} item={item} />
        ))}
      </div>
    </div>
  )
}
