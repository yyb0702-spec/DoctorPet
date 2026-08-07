// 예약 진행 스텝 표시 (화면메모 B-4: 1 예약확정 · 2 내원완료 · 3 진료중 · 4 진료완료).
// 상태 전이는 병원 운영 API 몫 — 프론트는 조회 결과를 표시만 한다.
import { Check } from 'lucide-react'
import { cn } from '@/lib/utils'
import { ReservationStatus } from '@/types/enums'

const STEPS = [
  { key: ReservationStatus.CONFIRMED, label: '예약 확정' },
  { key: ReservationStatus.CHECKED_IN, label: '내원 완료' },
  { key: ReservationStatus.IN_TREATMENT, label: '진료 중' },
  { key: ReservationStatus.TREATMENT_COMPLETED, label: '진료 완료' },
] as const

// 현재 상태가 몇 번째 스텝까지 왔는지 (0-indexed, -1이면 스텝 이전).
function currentStepIndex(status: ReservationStatus): number {
  return STEPS.findIndex((s) => s.key === status)
}

export function ReservationProgress({ status }: { status: ReservationStatus }) {
  const current = currentStepIndex(status)
  // CONFIRMED 이상 진행 상태에서만 스텝을 보여준다.
  if (current < 0) return null

  return (
    <ol className="flex items-center">
      {STEPS.map((step, i) => {
        const done = i < current
        const active = i === current
        return (
          <li key={step.key} className="flex flex-1 items-center last:flex-none">
            <div className="flex flex-col items-center gap-1.5">
              <span
                className={cn(
                  'flex h-8 w-8 items-center justify-center rounded-full border text-sm font-semibold',
                  (done || active) &&
                    'border-primary bg-primary text-primary-foreground',
                  !done && !active && 'border-border text-muted-foreground',
                )}
              >
                {done ? <Check className="h-4 w-4" /> : i + 1}
              </span>
              <span
                className={cn(
                  'whitespace-nowrap text-xs',
                  active ? 'font-medium text-foreground' : 'text-muted-foreground',
                )}
              >
                {step.label}
              </span>
            </div>
            {i < STEPS.length - 1 && (
              <span
                className={cn(
                  'mx-1 h-0.5 flex-1',
                  i < current ? 'bg-primary' : 'bg-border',
                )}
              />
            )}
          </li>
        )
      })}
    </ol>
  )
}
