// 병원 스태프 — 진료시간 관리(요일별 구간 편집 + 발효일 지정). GET/PUT /api/hospital/operating-hours.
import { useMemo, useState } from 'react'
import { Plus, Trash2 } from 'lucide-react'
import {
  useOperatingHours,
  useUpdateOperatingHours,
} from '@/features/hospitalOps/hooks'
import {
  toWeekDraft,
  validateWeeklyOperatingHours,
} from '@/features/hospitalOps/operatingHours'
import {
  DAY_LABEL,
  type DailyOperatingHours,
  type DayOfWeek,
  type OperatingHoursUpdateRequest,
} from '@/features/hospitalOps/types'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ErrorState, PageLoader } from '@/components/common/States'
import { ApiError } from '@/lib/api/error'
import { shiftDateKey, todaySeoulKey } from '@/lib/seoulTime'

// 새 구간을 추가할 때의 기본값(휴무 해제도 같은 값으로 연다).
const DEFAULT_PERIOD = { startTime: '09:00', endTime: '18:00' }

// 슬롯 발행창 — 백엔드가 today+13일까지 슬롯을 발행해 둔다.
const PUBLISHED_DAYS = 13

function errorMessage(error: unknown): string {
  return error instanceof ApiError ? error.message : '저장에 실패했습니다.'
}

function dateLabel(dateKey: string): string {
  const [year, month, day] = dateKey.split('-').map(Number)
  return `${year}년 ${month}월 ${day}일`
}

// 서버 값이 바뀌면 부모가 key로 이 폼을 새로 만든다 — 편집 상태 동기화를 effect로 하지 않는다.
function OperatingHoursForm({
  initialDays,
  pending,
  onSave,
}: {
  initialDays: DailyOperatingHours[]
  pending: boolean
  onSave: (request: OperatingHoursUpdateRequest) => void
}) {
  const today = todaySeoulKey()
  const tomorrow = shiftDateKey(today, 1)

  const [draft, setDraft] = useState<DailyOperatingHours[]>(() =>
    toWeekDraft(initialDays),
  )
  const [desiredEffectiveFrom, setDesiredEffectiveFrom] = useState(tomorrow)
  const [clientProblems, setClientProblems] = useState<string[]>([])

  const problems = useMemo(() => validateWeeklyOperatingHours(draft), [draft])

  const updateDay = (
    dayOfWeek: DayOfWeek,
    mapper: (day: DailyOperatingHours) => DailyOperatingHours,
  ) => {
    setDraft((prev) =>
      prev.map((day) => (day.dayOfWeek === dayOfWeek ? mapper(day) : day)),
    )
  }

  const toggleClosed = (dayOfWeek: DayOfWeek, closed: boolean) => {
    updateDay(dayOfWeek, (day) => ({
      ...day,
      periods: closed ? [] : [{ ...DEFAULT_PERIOD }],
    }))
  }

  const addPeriod = (dayOfWeek: DayOfWeek) => {
    updateDay(dayOfWeek, (day) => ({
      ...day,
      periods: [...day.periods, { ...DEFAULT_PERIOD }],
    }))
  }

  const removePeriod = (dayOfWeek: DayOfWeek, index: number) => {
    updateDay(dayOfWeek, (day) => ({
      ...day,
      periods: day.periods.filter((_, i) => i !== index),
    }))
  }

  const changePeriod = (
    dayOfWeek: DayOfWeek,
    index: number,
    field: 'startTime' | 'endTime',
    value: string,
  ) => {
    updateDay(dayOfWeek, (day) => ({
      ...day,
      periods: day.periods.map((period, i) =>
        i === index ? { ...period, [field]: value } : period,
      ),
    }))
  }

  const handleSave = () => {
    const messages = problems.map(
      (problem) => `${DAY_LABEL[problem.dayOfWeek]}: ${problem.message}`,
    )
    if (desiredEffectiveFrom <= today) {
      messages.unshift('발효일은 오늘 이후 날짜여야 합니다.')
    }
    setClientProblems(messages)
    if (messages.length > 0) return

    onSave({ desiredEffectiveFrom, days: draft })
  }

  return (
    <>
      <Card>
        <CardContent className="space-y-4 p-4">
          {draft.map((day) => {
            const closed = day.periods.length === 0
            return (
              <div
                key={day.dayOfWeek}
                className="flex flex-col gap-2 border-b pb-3 last:border-b-0 last:pb-0 sm:flex-row sm:items-start"
              >
                <div className="flex w-40 shrink-0 items-center gap-2">
                  <span className="font-medium">
                    {DAY_LABEL[day.dayOfWeek]}
                  </span>
                  <label className="flex items-center gap-1 text-xs text-muted-foreground">
                    <input
                      type="checkbox"
                      checked={closed}
                      onChange={(e) =>
                        toggleClosed(day.dayOfWeek, e.target.checked)
                      }
                    />
                    휴무
                  </label>
                </div>
                <div className="flex-1 space-y-2">
                  {closed && (
                    <p className="text-sm text-muted-foreground">
                      휴무일입니다.
                    </p>
                  )}
                  {day.periods.map((period, index) => (
                    <div key={index} className="flex items-center gap-2">
                      <Input
                        type="time"
                        className="w-32"
                        aria-label={`${DAY_LABEL[day.dayOfWeek]} ${index + 1}번째 구간 시작 시각`}
                        value={period.startTime}
                        onChange={(e) =>
                          changePeriod(
                            day.dayOfWeek,
                            index,
                            'startTime',
                            e.target.value,
                          )
                        }
                      />
                      <span className="text-muted-foreground">~</span>
                      <Input
                        type="time"
                        className="w-32"
                        aria-label={`${DAY_LABEL[day.dayOfWeek]} ${index + 1}번째 구간 종료 시각`}
                        value={period.endTime}
                        onChange={(e) =>
                          changePeriod(
                            day.dayOfWeek,
                            index,
                            'endTime',
                            e.target.value,
                          )
                        }
                      />
                      <Button
                        size="sm"
                        variant="ghost"
                        aria-label={`${DAY_LABEL[day.dayOfWeek]} ${index + 1}번째 구간 삭제`}
                        onClick={() => removePeriod(day.dayOfWeek, index)}
                      >
                        <Trash2 className="h-4 w-4" />
                      </Button>
                    </div>
                  ))}
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => addPeriod(day.dayOfWeek)}
                  >
                    <Plus className="mr-1 h-4 w-4" />
                    구간 추가
                  </Button>
                </div>
              </div>
            )
          })}
        </CardContent>
      </Card>

      <Card>
        <CardContent className="space-y-3 p-4">
          <div className="flex flex-wrap items-end gap-3">
            <div className="space-y-1">
              <Label htmlFor="desiredEffectiveFrom">발효일</Label>
              <Input
                id="desiredEffectiveFrom"
                type="date"
                className="w-44"
                min={tomorrow}
                value={desiredEffectiveFrom}
                onChange={(e) => setDesiredEffectiveFrom(e.target.value)}
              />
            </div>
            <Button disabled={pending} onClick={handleSave}>
              {pending ? '저장 중…' : '진료시간 저장'}
            </Button>
          </div>

          {clientProblems.length > 0 && (
            <ul className="space-y-1 text-sm text-destructive">
              {clientProblems.map((message) => (
                <li key={message}>{message}</li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>
    </>
  )
}

export function StaffOperatingHoursPage() {
  const query = useOperatingHours()
  const update = useUpdateOperatingHours()

  if (query.isLoading) return <PageLoader />
  if (query.isError || !query.data) {
    return (
      <ErrorState
        message={errorMessage(query.error)}
        onRetry={() => query.refetch()}
      />
    )
  }

  const schedule = query.data
  // 요청한 발효일과 서버가 확정한 발효일을 비교해 "뒤로 밀렸다"를 알린다.
  const requestedFrom = update.variables?.desiredEffectiveFrom
  const confirmedFrom = update.data?.effectiveFrom
  const pushedBack = Boolean(
    update.isSuccess &&
    confirmedFrom &&
    requestedFrom &&
    confirmedFrom !== requestedFrom,
  )

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">진료시간 관리</h1>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">저장 전에 알아두기</CardTitle>
        </CardHeader>
        <CardContent className="space-y-1.5 text-sm text-muted-foreground">
          <p>
            현재 적용 중인 진료시간의 발효일은{' '}
            <strong className="text-foreground">
              {dateLabel(schedule.effectiveFrom)}
            </strong>
            입니다.
          </p>
          <p>· 새 진료시간은 오늘 이후 날짜부터만 적용할 수 있습니다.</p>
          <p>
            · 예약 슬롯은 오늘부터 {PUBLISHED_DAYS}일 뒤까지 미리 발행돼
            있습니다. 발효일이 그 안이면 발효일부터 {PUBLISHED_DAYS}일 뒤까지의{' '}
            <strong>예약 없는 슬롯이 새 진료시간으로 교체</strong>됩니다.
          </p>
          <p>
            · 그 기간에 이미 예약이 있으면 서버가 발효일을{' '}
            <strong>마지막 예약일의 다음 날로 자동 조정</strong>합니다. 저장 후
            확정된 발효일을 확인해 주세요.
          </p>
          <p>
            · 종료 시각이 시작 시각보다 이르면 자정을 넘긴 야간 진료로
            처리됩니다. 하루에 여러 구간을 둘 수 있고, 구간을 모두 지우면 그
            요일은 휴무입니다.
          </p>
          <p>
            · 병원이 영업 중이 아니면 시간표만 저장되고 슬롯 재배치는 일어나지
            않습니다.
          </p>
        </CardContent>
      </Card>

      <OperatingHoursForm
        key={`${schedule.effectiveFrom}|${JSON.stringify(schedule.days)}`}
        initialDays={schedule.days}
        pending={update.isPending}
        onSave={(request) => update.mutate(request)}
      />

      {update.isError && (
        <p className="text-sm text-destructive">{errorMessage(update.error)}</p>
      )}
      {update.isSuccess && confirmedFrom && (
        <div className="space-y-1 text-sm">
          <p>저장했습니다. 확정된 발효일은 {dateLabel(confirmedFrom)}입니다.</p>
          {pushedBack && requestedFrom && (
            <p className="text-muted-foreground">
              요청한 {dateLabel(requestedFrom)}에는 이미 예약이 있어 서버가
              발효일을 뒤로 조정했습니다. 그 전까지는 기존 진료시간이 그대로
              쓰입니다.
            </p>
          )}
        </div>
      )}
    </div>
  )
}
