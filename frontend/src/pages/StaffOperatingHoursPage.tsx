// 병원 스태프 — 진료시간 관리(요일별 구간 편집 + 발효일 지정). GET/PUT /api/hospital/operating-hours.
import { useMemo, useState } from 'react'
import { Plus, Trash2 } from 'lucide-react'
import {
  useOperatingHours,
  useUpdateOperatingHours,
} from '@/features/hospitalOps/hooks'
import {
  emptyWeek,
  toWeekDraft,
  validateWeeklyOperatingHours,
} from '@/features/hospitalOps/operatingHours'
import {
  DAY_LABEL,
  SLOT_PUBLICATION_DAYS,
  type DailyOperatingHours,
  type DayOfWeek,
  type OperatingHoursUpdateRequest,
  type OperatingPeriod,
} from '@/features/hospitalOps/types'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ErrorState, PageLoader } from '@/components/common/States'
import { ApiError } from '@/lib/api/error'
import { dateKeyLabel, shiftDateKey, todaySeoulKey } from '@/lib/seoulTime'
import { useUnsavedChangesWarning } from '@/lib/useUnsavedChangesWarning'

// 새 구간을 추가할 때의 기본값(휴무 해제도 같은 값으로 연다).
const DEFAULT_PERIOD = { startTime: '09:00', endTime: '18:00' }

// 아직 유효한 진료시간이 없는 병원에서 GET이 내는 코드(HospitalErrorCode.OPERATING_SCHEDULE_NOT_FOUND).
const OPERATING_SCHEDULE_NOT_FOUND = 'HOSPITAL_004'

// ApiError면 백엔드 문구를 그대로 쓴다. 그 밖(네트워크·예상 못한 예외)은 호출 맥락에 맞는
// 문구로 대체한다 — 조회 실패에 "저장에 실패"가 뜨면 안 된다.
function errorMessage(
  error: unknown,
  fallback = '저장에 실패했습니다.',
): string {
  return error instanceof ApiError ? error.message : fallback
}

// 서버 값이 바뀌면 부모가 key로 이 폼을 새로 만든다 — 편집 상태 동기화를 effect로 하지 않는다.
function OperatingHoursForm({
  initialDays,
  pending,
  onEdit,
  onSave,
}: {
  initialDays: DailyOperatingHours[]
  pending: boolean
  onEdit: () => void
  onSave: (request: OperatingHoursUpdateRequest) => void
}) {
  const today = todaySeoulKey()
  const tomorrow = shiftDateKey(today, 1)

  const [draft, setDraft] = useState<DailyOperatingHours[]>(() =>
    toWeekDraft(initialDays),
  )
  const [desiredEffectiveFrom, setDesiredEffectiveFrom] = useState(tomorrow)
  const [clientProblems, setClientProblems] = useState<string[]>([])
  // 휴무로 바꾸기 직전의 구간. 휴무를 풀면 기본값이 아니라 이 값을 되살린다.
  const [stashedPeriods, setStashedPeriods] = useState<
    Partial<Record<DayOfWeek, OperatingPeriod[]>>
  >({})

  const problems = useMemo(() => validateWeeklyOperatingHours(draft), [draft])

  // 저장하지 않은 편집이 있으면 이탈을 막는다(서버 값과 다른지로만 판정한다).
  const initialSnapshot = useMemo(
    () => JSON.stringify(toWeekDraft(initialDays)),
    [initialDays],
  )
  useUnsavedChangesWarning(JSON.stringify(draft) !== initialSnapshot)

  // 편집을 시작하면 직전 저장 결과 메시지를 지운다 — "저장했습니다"와 검증 오류가 같이 떠서
  // 저장된 것으로 오독하는 일을 막는다.
  const updateDay = (
    dayOfWeek: DayOfWeek,
    mapper: (day: DailyOperatingHours) => DailyOperatingHours,
  ) => {
    onEdit()
    setDraft((prev) =>
      prev.map((day) => (day.dayOfWeek === dayOfWeek ? mapper(day) : day)),
    )
  }

  /*
    휴무 토글은 구간을 파기하지 않는다 — 체크할 때 보관해 두고, 풀면 그대로 되살린다.
    실수로 눌러 하루치 구간(예: 오전·오후 두 구간)을 잃는 일이 없어야 하고, 그래서 삭제
    확인 모달도 두지 않았다. 보관은 이 폼이 살아 있는 동안만 유효하다(저장·재조회 시 초기화).
  */
  const toggleClosed = (dayOfWeek: DayOfWeek, closed: boolean) => {
    if (closed) {
      const current =
        draft.find((day) => day.dayOfWeek === dayOfWeek)?.periods ?? []
      if (current.length > 0) {
        setStashedPeriods((prev) => ({ ...prev, [dayOfWeek]: current }))
      }
      updateDay(dayOfWeek, (day) => ({ ...day, periods: [] }))
      return
    }

    const restored = stashedPeriods[dayOfWeek]
    updateDay(dayOfWeek, (day) => ({
      ...day,
      periods: restored?.length
        ? restored.map((period) => ({ ...period }))
        : [{ ...DEFAULT_PERIOD }],
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
                      {stashedPeriods[day.dayOfWeek]?.length
                        ? '휴무일입니다. 휴무를 풀면 이전 구간이 복원됩니다.'
                        : '휴무일입니다.'}
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
                onChange={(e) => {
                  onEdit()
                  setDesiredEffectiveFrom(e.target.value)
                }}
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

  /*
    아직 유효한 진료시간이 없는 병원은 GET이 HOSPITAL_004로 실패하지만 PUT은 스케줄을 새로
    만들 수 있다(updateOperatingHours의 orElseGet(createSchedule)). 초기 스케줄 시드는 제휴
    병원만 대상이라(findPartnerDetailsWithoutEffectiveSchedule) 그 밖의 병원은 이 화면이
    유일한 등록 경로다 — 이 코드일 때만 빈 폼을 열어 준다.
    다른 오류(일시적 5xx·권한)는 폼을 열지 않는다. 빈 시간표를 현재 상태로 착각해 저장하면
    기존 진료시간을 덮어쓰기 때문이다.
  */
  const scheduleMissing =
    query.isError &&
    query.error instanceof ApiError &&
    query.error.code === OPERATING_SCHEDULE_NOT_FOUND

  const clearResult = () => {
    if (update.isSuccess || update.isError) update.reset()
  }

  if (query.isLoading) return <PageLoader />
  if (!query.data && !scheduleMissing) {
    return (
      <ErrorState
        message={errorMessage(query.error, '진료시간을 불러오지 못했습니다.')}
        onRetry={() => query.refetch()}
      />
    )
  }

  const schedule = query.data ?? null
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
          {schedule ? (
            <p>
              현재 적용 중인 진료시간의 발효일은{' '}
              <strong className="text-foreground">
                {dateKeyLabel(schedule.effectiveFrom)}
              </strong>
              입니다.
            </p>
          ) : (
            <p className="text-foreground">
              <strong>아직 등록된 진료시간이 없습니다.</strong> 아래에서 요일별
              진료 구간을 채워 처음 등록해 주세요.
            </p>
          )}
          <p>· 새 진료시간은 오늘 이후 날짜부터만 적용할 수 있습니다.</p>
          <p>
            · 예약 슬롯은 오늘부터 {SLOT_PUBLICATION_DAYS}일 뒤까지 미리 발행돼
            있습니다. 발효일이 그 안이면 발효일부터 {SLOT_PUBLICATION_DAYS}일
            뒤까지의 슬롯을 <strong>통째로 새 시간표로 교체</strong>
            합니다(슬롯을 골라 남기는 부분 교체가 아닙니다).
          </p>
          <p>
            · 그 기간에 이미 예약이 있으면 서버가 발효일을{' '}
            <strong>마지막 예약일의 다음 날로 자동 조정</strong>합니다. 저장 후
            확정된 발효일을 확인해 주세요. 조정한 뒤에도 예약이 남아 있으면
            저장은 실패합니다(예약된 슬롯이 있다는 오류).
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
        key={
          schedule
            ? `${schedule.effectiveFrom}|${JSON.stringify(schedule.days)}`
            : 'missing-schedule'
        }
        initialDays={schedule?.days ?? emptyWeek()}
        pending={update.isPending}
        onEdit={clearResult}
        onSave={(request) => update.mutate(request)}
      />

      {update.isError && (
        <p className="text-sm text-destructive">{errorMessage(update.error)}</p>
      )}
      {update.isSuccess && confirmedFrom && (
        <div className="space-y-1 text-sm">
          <p>
            저장했습니다. 확정된 발효일은 {dateKeyLabel(confirmedFrom)}입니다.
          </p>
          {pushedBack && requestedFrom && (
            <p className="text-muted-foreground">
              요청한 {dateKeyLabel(requestedFrom)}에는 이미 예약이 있어 서버가
              발효일을 뒤로 조정했습니다. 그 전까지는 기존 진료시간이 그대로
              쓰입니다.
            </p>
          )}
        </div>
      )}
    </div>
  )
}
