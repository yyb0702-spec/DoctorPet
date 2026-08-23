// 예약 요청 패널 — 날짜 선택 → 슬롯 선택 → 펫·결제수단 선택 후 POST /api/reservations.
// 프로필/결제수단 미등록이면 등록으로 유도한다(SA §8-5 사전조건).
import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { todaySeoul, useHospitalSlots } from '@/features/hospitals/hooks'
import { usePets } from '@/features/pets/hooks'
import { PetAvatar } from '@/features/pets/PetAvatar'
import { usePaymentMethods } from '@/features/payments/hooks'
import { paymentMethodLabels } from '@/features/payments/methodLabel'
import { useCreateReservation } from './hooks'
import {
  useMyWaitlists,
  useRegisterWaitlist,
} from '@/features/waitlist/hooks'
import { waitlistBlockReason } from '@/features/waitlist/slotBlock'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { EmptyState, PageLoader } from '@/components/common/States'
import { cn } from '@/lib/utils'
import { ApiError } from '@/lib/api/error'
import { speciesLabel } from '@/lib/species'
import { useAuthStore } from '@/lib/auth/authStore'

// LocalDateTime("...THH:mm:ss")은 타임존이 없어 로컬 시각으로 파싱된다 → 시:분만 표시.
function formatSlotTime(iso: string): string {
  return new Date(iso).toLocaleTimeString('ko-KR', {
    hour: '2-digit',
    minute: '2-digit',
  })
}

// yyyy-MM-dd → { 요일, 일 } (로컬 파싱).
function parseDate(dateStr: string): Date {
  return new Date(`${dateStr}T00:00:00`)
}
const WEEKDAY_KO = ['일', '월', '화', '수', '목', '금', '토']

// RESERVED는 별도 대기 신청 경로로 처리하므로 여기엔 마감(LEAD_TIME_CLOSED)만 둔다.
const SLOT_STATUS_LABEL: Record<string, string> = {
  LEAD_TIME_CLOSED: '마감',
}

// 만석 슬롯의 대기 신청 버튼 — 슬롯마다 독립된 mutation을 갖는다. 공유 mutation 하나를
// 여러 슬롯이 쓰면 pending·성공·실패 상태가 마지막 요청 기준으로 섞여, A 신청 실패가 B 신청
// 성공에 가려지거나 그 반대가 된다. 슬롯별로 분리하면 각 결과가 자기 버튼에만 반영된다.
// 성공하면 onSuccess가 내 대기열 캐시를 무효화해 이 슬롯이 부모에서 "대기중"으로 바뀌므로
// 별도 성공 메시지는 두지 않는다(PR #188 리뷰).
function WaitlistRegisterButton({
  slotId,
  startAt,
}: {
  slotId: number
  startAt: string
}) {
  const register = useRegisterWaitlist()
  return (
    <div className="flex flex-col gap-1">
      <button
        type="button"
        disabled={register.isPending}
        onClick={() => register.mutate(slotId)}
        className={cn(
          'rounded-md border px-3 py-2 text-sm transition-colors',
          register.isPending ? 'cursor-wait opacity-60' : 'hover:bg-accent',
        )}
      >
        <span>{formatSlotTime(startAt)}</span>
        <span className="ml-1 text-xs text-muted-foreground">
          {register.isPending ? '신청 중…' : '대기 신청'}
        </span>
      </button>
      {register.isError && (
        <p className="text-xs text-destructive">
          {register.error instanceof ApiError
            ? register.error.message
            : '대기 신청에 실패했습니다.'}
        </p>
      )}
    </div>
  )
}

export function ReservationRequestPanel({ hospitalId }: { hospitalId: number }) {
  const navigate = useNavigate()
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  const [selectedDate, setSelectedDate] = useState<string>(todaySeoul())
  const slotsQuery = useHospitalSlots(hospitalId, selectedDate)
  const petsQuery = usePets()
  const methodsQuery = usePaymentMethods()
  const createReservation = useCreateReservation()
  const myWaitlistsQuery = useMyWaitlists()

  const [slotId, setSlotId] = useState<number | null>(null)
  const [petId, setPetId] = useState<number | null>(null)
  const [paymentMethodId, setPaymentMethodId] = useState<number | null>(null)

  const pets = petsQuery.data ?? []
  const methods = (methodsQuery.data ?? []).filter((m) => m.status === 'ACTIVE')
  // 카드 정보가 없는 카카오페이 수단이 여럿이면 문구가 겹쳐 어느 걸 청구할지 못 고른다 → 겹칠 때만 등록 시각을 덧붙인다.
  const methodLabels = paymentMethodLabels(methods)
  const myWaitlists = myWaitlistsQuery.data ?? []

  /*
    기본 결제수단(PR #152)을 초기 선택값으로 쓴다. useEffect로 상태에 밀어넣지 않고 읽는 시점에
    합성한다 — 목록이 늦게 도착해도 반영되고, 사용자가 직접 고른 값이 나중에 덮이지 않는다.
  */
  const effectivePaymentMethodId =
    paymentMethodId ?? methods.find((m) => m.isDefault)?.id ?? null

  const dateAvailabilities = slotsQuery.data?.dateAvailabilities ?? []
  const slots = slotsQuery.data?.slots ?? []

  // 날짜를 바꾸면 이전에 고른 슬롯은 무효 → 초기화. 대기 신청 상태는 슬롯별 버튼이 각자
  // 들고 있어(WaitlistRegisterButton) 날짜를 바꾸면 슬롯이 언마운트되며 함께 정리된다.
  const handleSelectDate = (date: string) => {
    if (date === selectedDate) return
    setSelectedDate(date)
    setSlotId(null)
  }

  const canSubmit =
    slotId != null && petId != null && effectivePaymentMethodId != null

  const handleSubmit = () => {
    if (petId == null || slotId == null || effectivePaymentMethodId == null)
      return
    createReservation.mutate(
      { petId, slotId, paymentMethodId: effectivePaymentMethodId },
      { onSuccess: () => navigate('/reservations') },
    )
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>예약 요청</CardTitle>
      </CardHeader>
      <CardContent className="space-y-5">
        {/* 사전조건 안내 */}
        {(pets.length === 0 || methods.length === 0) && (
          <div className="rounded-md bg-accent p-3 text-sm text-accent-foreground">
            예약하려면{' '}
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
        )}

        {/* 날짜 선택 (오늘부터 14일) */}
        <div className="space-y-2">
          <p className="text-sm font-medium">날짜 선택</p>
          {slotsQuery.isLoading && dateAvailabilities.length === 0 ? (
            <PageLoader label="예약 가능 날짜 불러오는 중…" />
          ) : dateAvailabilities.length === 0 ? (
            <EmptyState message="예약 가능한 날짜가 없습니다." />
          ) : (
            <div className="flex gap-1.5 overflow-x-auto pb-1">
              {dateAvailabilities.map((d) => {
                const day = parseDate(d.date)
                const selected = d.date === selectedDate
                return (
                  <button
                    key={d.date}
                    type="button"
                    onClick={() => handleSelectDate(d.date)}
                    className={cn(
                      'flex shrink-0 flex-col items-center rounded-md border px-2.5 py-1.5 text-xs transition-colors',
                      selected
                        ? 'border-primary bg-primary/10 font-medium'
                        : 'hover:bg-accent',
                      !d.reservationAvailable && !selected && 'opacity-40',
                    )}
                  >
                    <span className="text-muted-foreground">
                      {WEEKDAY_KO[day.getDay()]}
                    </span>
                    <span className="text-sm">{day.getDate()}</span>
                    {/* 예약 가능일 표시 점 */}
                    <span
                      className={cn(
                        'mt-0.5 h-1 w-1 rounded-full',
                        d.reservationAvailable
                          ? 'bg-primary'
                          : 'bg-transparent',
                      )}
                    />
                  </button>
                )
              })}
            </div>
          )}
        </div>

        {/* 슬롯(시간) 선택 — AVAILABLE만 선택 가능 */}
        {dateAvailabilities.length > 0 && (
          <div className="space-y-2">
            <p className="text-sm font-medium">시간 선택</p>
            {slotsQuery.isFetching && slots.length === 0 ? (
              <PageLoader label="시간 불러오는 중…" />
            ) : slots.length === 0 ? (
              <EmptyState message="이 날짜에는 예약 가능한 시간이 없습니다." />
            ) : (
              <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
                {slots.map((s) => {
                  // 만석(RESERVED) 슬롯은 예약 대신 대기 신청 경로를 얹는다.
                  if (s.availabilityStatus === 'RESERVED') {
                    // ACCEPTED 이력이 있으면 백엔드가 재활성화하지 않아(reactivateTerminalIfSlotReserved
                    // 대상 아님) 재신청은 항상 WAITLIST_002(409)다 — WAITING·OFFERED("대기중")와
                    // 구분해 버튼 자체를 숨긴다(PR #188 리뷰).
                    const blockReason = waitlistBlockReason(myWaitlists, s.slotId)
                    if (blockReason === 'ACCEPTED') {
                      return (
                        <div
                          key={s.slotId}
                          className="flex items-center justify-center rounded-md border border-emerald-300 bg-emerald-50 px-3 py-2 text-sm text-emerald-700"
                        >
                          <span>{formatSlotTime(s.startAt)}</span>
                          <span className="ml-1 text-xs">예약완료</span>
                        </div>
                      )
                    }
                    if (blockReason === 'PENDING') {
                      return (
                        <div
                          key={s.slotId}
                          className="flex items-center justify-center rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-700"
                        >
                          <span>{formatSlotTime(s.startAt)}</span>
                          <span className="ml-1 text-xs">대기중</span>
                        </div>
                      )
                    }
                    // 미인증이면 로그인으로 유도(예약 요청과 동일한 사전조건).
                    if (!isAuthenticated) {
                      return (
                        <Link
                          key={s.slotId}
                          to="/login"
                          className="flex items-center justify-center rounded-md border px-3 py-2 text-sm transition-colors hover:bg-accent"
                        >
                          <span>{formatSlotTime(s.startAt)}</span>
                          <span className="ml-1 text-xs text-muted-foreground">
                            대기 신청
                          </span>
                        </Link>
                      )
                    }
                    return (
                      <WaitlistRegisterButton
                        key={s.slotId}
                        slotId={s.slotId}
                        startAt={s.startAt}
                      />
                    )
                  }
                  // AVAILABLE만 선택 가능, LEAD_TIME_CLOSED는 마감.
                  const disabled = s.availabilityStatus !== 'AVAILABLE'
                  return (
                    <button
                      key={s.slotId}
                      type="button"
                      disabled={disabled}
                      onClick={() => setSlotId(s.slotId)}
                      className={cn(
                        'rounded-md border px-3 py-2 text-sm transition-colors',
                        disabled && 'cursor-not-allowed opacity-40',
                        slotId === s.slotId
                          ? 'border-primary bg-primary/10 font-medium'
                          : !disabled && 'hover:bg-accent',
                      )}
                    >
                      <span>{formatSlotTime(s.startAt)}</span>
                      {disabled && (
                        <span className="ml-1 text-xs text-muted-foreground">
                          {SLOT_STATUS_LABEL[s.availabilityStatus]}
                        </span>
                      )}
                    </button>
                  )
                })}
              </div>
            )}
          </div>
        )}

        {/* 펫 선택 */}
        {pets.length > 0 && (
          <div className="space-y-2">
            <p className="text-sm font-medium">반려동물</p>
            <div className="flex flex-wrap gap-2">
              {pets.map((p) => (
                <Button
                  key={p.petId}
                  type="button"
                  size="sm"
                  className="h-auto gap-2 px-2 py-1.5"
                  variant={petId === p.petId ? 'default' : 'outline'}
                  onClick={() => setPetId(p.petId)}
                >
                  <PetAvatar
                    name={p.name}
                    imageUrl={p.imageUrl}
                    className="h-7 w-7"
                  />
                  <span>
                    {p.name} ({speciesLabel(p.species)})
                  </span>
                </Button>
              ))}
            </div>
          </div>
        )}

        {/* 결제수단 선택 */}
        {methods.length > 0 && (
          <div className="space-y-2">
            <p className="text-sm font-medium">결제수단</p>
            <div className="flex flex-wrap gap-2">
              {methods.map((m) => (
                <Button
                  key={m.id}
                  type="button"
                  size="sm"
                  variant={
                    effectivePaymentMethodId === m.id ? 'default' : 'outline'
                  }
                  onClick={() => setPaymentMethodId(m.id)}
                >
                  {methodLabels.get(m.id)}
                  {m.isDefault && ' · 기본'}
                </Button>
              ))}
            </div>
          </div>
        )}

        {createReservation.isError && (
          <p className="text-sm text-destructive">
            {createReservation.error instanceof ApiError
              ? createReservation.error.message
              : '예약 요청에 실패했습니다.'}
          </p>
        )}

        <Button
          className="w-full"
          disabled={!canSubmit || createReservation.isPending}
          onClick={handleSubmit}
        >
          {createReservation.isPending ? '요청 중…' : '예약 요청'}
        </Button>
      </CardContent>
    </Card>
  )
}
