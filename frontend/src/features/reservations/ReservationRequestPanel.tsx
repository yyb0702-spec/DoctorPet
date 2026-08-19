// 예약 요청 패널 — 날짜 선택 → 슬롯 선택 → 펫·결제수단 선택 후 POST /api/reservations.
// 프로필/결제수단 미등록이면 등록으로 유도한다(SA §8-5 사전조건).
import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { todaySeoul, useHospitalSlots } from '@/features/hospitals/hooks'
import { usePets } from '@/features/pets/hooks'
import { usePaymentMethods } from '@/features/payments/hooks'
import { useCreateReservation } from './hooks'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { EmptyState, PageLoader } from '@/components/common/States'
import { cn } from '@/lib/utils'
import { ApiError } from '@/lib/api/error'
import { speciesLabel } from '@/lib/species'

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

const SLOT_STATUS_LABEL: Record<string, string> = {
  RESERVED: '예약됨',
  LEAD_TIME_CLOSED: '마감',
}

export function ReservationRequestPanel({ hospitalId }: { hospitalId: number }) {
  const navigate = useNavigate()
  const [selectedDate, setSelectedDate] = useState<string>(todaySeoul())
  const slotsQuery = useHospitalSlots(hospitalId, selectedDate)
  const petsQuery = usePets()
  const methodsQuery = usePaymentMethods()
  const createReservation = useCreateReservation()

  const [slotId, setSlotId] = useState<number | null>(null)
  const [petId, setPetId] = useState<number | null>(null)
  const [paymentMethodId, setPaymentMethodId] = useState<number | null>(null)

  const pets = petsQuery.data ?? []
  const methods = (methodsQuery.data ?? []).filter((m) => m.status === 'ACTIVE')

  const dateAvailabilities = slotsQuery.data?.dateAvailabilities ?? []
  const slots = slotsQuery.data?.slots ?? []

  // 날짜를 바꾸면 이전에 고른 슬롯은 무효 → 초기화.
  const handleSelectDate = (date: string) => {
    if (date === selectedDate) return
    setSelectedDate(date)
    setSlotId(null)
  }

  const canSubmit =
    slotId != null && petId != null && paymentMethodId != null

  const handleSubmit = () => {
    if (petId == null || slotId == null || paymentMethodId == null) return
    createReservation.mutate(
      { petId, slotId, paymentMethodId },
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
                  variant={petId === p.petId ? 'default' : 'outline'}
                  onClick={() => setPetId(p.petId)}
                >
                  {p.name} ({speciesLabel(p.species)})
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
                  variant={paymentMethodId === m.id ? 'default' : 'outline'}
                  onClick={() => setPaymentMethodId(m.id)}
                >
                  {m.cardBrand ?? '카드'} ****{m.cardLast4 ?? '****'}
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
