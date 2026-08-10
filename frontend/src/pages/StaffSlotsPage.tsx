// 병원 스태프 — 슬롯 관리(휴진/재개). SA §9-9 병원 직접 편성의 최소 형태.
import { useState } from 'react'
import { todaySeoul } from '@/features/hospitals/hooks'
import {
  useCloseDay,
  useCloseSlot,
  useReopenSlot,
  useStaffSlots,
} from '@/features/staffSlots/hooks'
import type { StaffSlot } from '@/features/staffSlots/types'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { EmptyState, ErrorState, PageLoader } from '@/components/common/States'
import { ApiError } from '@/lib/api/error'

const STATUS_LABEL: Record<StaffSlot['status'], { label: string; variant: 'default' | 'muted' | 'destructive' }> = {
  OPEN: { label: '예약 가능', variant: 'default' },
  RESERVED: { label: '예약 있음', variant: 'destructive' },
  CLOSED: { label: '휴진', variant: 'muted' },
}

function formatTime(iso: string): string {
  return new Date(iso).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })
}

function errorMessage(error: unknown): string {
  return error instanceof ApiError ? error.message : '처리에 실패했습니다.'
}

function SlotRow({ slot, date }: { slot: StaffSlot; date: string }) {
  const closeSlot = useCloseSlot(date)
  const reopenSlot = useReopenSlot(date)
  const meta = STATUS_LABEL[slot.status]

  return (
    <div className="flex items-center justify-between rounded-md border p-3">
      <div className="flex items-center gap-2">
        <span className="font-medium">
          {formatTime(slot.startAt)} - {formatTime(slot.endAt)}
        </span>
        <Badge variant={meta.variant}>{meta.label}</Badge>
      </div>
      <div className="flex flex-col items-end gap-1">
        {slot.status === 'OPEN' && (
          <Button
            size="sm"
            variant="outline"
            disabled={closeSlot.isPending}
            onClick={() => closeSlot.mutate(slot.slotId)}
          >
            휴진
          </Button>
        )}
        {slot.status === 'CLOSED' && (
          <Button
            size="sm"
            disabled={reopenSlot.isPending}
            onClick={() => reopenSlot.mutate(slot.slotId)}
          >
            재개
          </Button>
        )}
        {closeSlot.isError && closeSlot.variables === slot.slotId && (
          <p className="text-xs text-destructive">{errorMessage(closeSlot.error)}</p>
        )}
      </div>
    </div>
  )
}

function CloseDayAction({ date }: { date: string }) {
  const [confirming, setConfirming] = useState(false)
  const closeDay = useCloseDay(date)

  if (closeDay.isSuccess && closeDay.data) {
    return (
      <p className="text-sm text-muted-foreground">
        {closeDay.data.closed}건 휴진 처리, 확정 예약이 있어 건너뛴 슬롯{' '}
        {closeDay.data.skippedDueToReservation}건.
      </p>
    )
  }

  if (!confirming) {
    return (
      <Button size="sm" variant="outline" onClick={() => setConfirming(true)}>
        이 날짜 전체 휴진
      </Button>
    )
  }

  return (
    <div className="space-y-2 rounded-md border bg-muted/30 p-3">
      <p className="text-sm">
        이 날짜의 예약 가능한 슬롯을 전부 휴진 처리합니다. 확정 예약이 있는 슬롯은 건너뜁니다.
      </p>
      {closeDay.isError && (
        <p className="text-sm text-destructive">{errorMessage(closeDay.error)}</p>
      )}
      <div className="flex gap-2">
        <Button
          size="sm"
          variant="destructive"
          disabled={closeDay.isPending}
          onClick={() => closeDay.mutate()}
        >
          {closeDay.isPending ? '처리 중…' : '전체 휴진 확정'}
        </Button>
        <Button size="sm" variant="ghost" onClick={() => setConfirming(false)}>
          취소
        </Button>
      </div>
    </div>
  )
}

export function StaffSlotsPage() {
  const [date, setDate] = useState(todaySeoul())
  const query = useStaffSlots(date)

  const slots = query.data ?? []

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">슬롯 관리</h1>

      <div className="flex flex-wrap items-center gap-3">
        <Input
          type="date"
          className="w-40"
          value={date}
          onChange={(e) => setDate(e.target.value)}
        />
        <CloseDayAction key={date} date={date} />
      </div>

      {query.isLoading && <PageLoader />}
      {query.isError && <ErrorState onRetry={() => query.refetch()} />}
      {query.data && slots.length === 0 && (
        <EmptyState message="이 날짜에는 슬롯이 없어요(자동 배치가 아직 안 돌았을 수 있어요)." />
      )}

      {slots.length > 0 && (
        <Card>
          <CardContent className="space-y-2 p-4">
            {slots.map((slot) => (
              <SlotRow key={slot.slotId} slot={slot} date={date} />
            ))}
          </CardContent>
        </Card>
      )}
    </div>
  )
}
