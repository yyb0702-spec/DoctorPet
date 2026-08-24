// 병원 스태프 — 진료시간 관리(현재·예정 시간표 조회, 요일별 구간 편집 + 발효일 지정).
import { useEffect, useMemo, useState } from 'react'
import { Plus, Trash2 } from 'lucide-react'
import {
  useOperatingHours,
  useScheduledOperatingHours,
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
  type OperatingHours,
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

/*
  편집용 드래프트는 구간마다 id를 갖는다. 배열 인덱스를 React key로 쓰면 중간 구간을 삭제할 때
  뒤 구간이 앞 구간의 DOM을 물려받아 포커스·IME 상태가 엉뚱한 칸으로 튄다. id는 화면 안에서만
  쓰고 서버로 보낼 때 toWire로 벗긴다 — 요청 DTO(OperatingPeriodRequest)에 없는 필드다.
*/
let periodSeq = 0

interface PeriodDraft extends OperatingPeriod {
  id: number
}

interface DayDraft {
  dayOfWeek: DayOfWeek
  periods: PeriodDraft[]
}

function newPeriod(period: OperatingPeriod): PeriodDraft {
  periodSeq += 1
  return { ...period, id: periodSeq }
}

function toDayDrafts(days: DailyOperatingHours[]): DayDraft[] {
  return toWeekDraft(days).map((day) => ({
    dayOfWeek: day.dayOfWeek,
    periods: day.periods.map(newPeriod),
  }))
}

function toWire(draft: DayDraft[]): DailyOperatingHours[] {
  return draft.map((day) => ({
    dayOfWeek: day.dayOfWeek,
    periods: day.periods.map(({ startTime, endTime }) => ({
      startTime,
      endTime,
    })),
  }))
}

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

// 선택 전환·내 저장 성공 때만 부모가 key로 이 폼을 새로 만든다. 백그라운드 재조회가 같은 선택의
// props를 바꿔도 드래프트를 버리지 않도록 서버 값 자체를 key에 넣지 않는다.
function OperatingHoursForm({
  initialDays,
  initialDesiredEffectiveFrom,
  scheduledEffectiveFroms,
  effectiveFromFixed,
  initialSaveTarget,
  pending,
  onEdit,
  onDirtyChange,
  onSave,
}: {
  initialDays: DailyOperatingHours[]
  initialDesiredEffectiveFrom?: string
  scheduledEffectiveFroms: string[]
  effectiveFromFixed: boolean
  initialSaveTarget?: Pick<OperatingHours, 'scheduleId' | 'updatedAt'>
  pending: boolean
  onEdit: () => void
  onDirtyChange: (dirty: boolean) => void
  onSave: (request: OperatingHoursUpdateRequest) => void
}) {
  const today = todaySeoulKey()
  const tomorrow = shiftDateKey(today, 1)
  const initialEffectiveFrom = initialDesiredEffectiveFrom ?? tomorrow

  const [draft, setDraft] = useState<DayDraft[]>(() => toDayDrafts(initialDays))
  const [desiredEffectiveFrom, setDesiredEffectiveFrom] =
    useState(initialEffectiveFrom)
  // 배경 재조회로 부모의 예정 목록이 바뀌어도 이 편집 세션이 시작할 때 읽은 토큰을 쓴다.
  // 새 토큰을 섞으면 오래된 드래프트가 최신 변경을 덮어쓸 수 있다.
  const [saveTarget] = useState(initialSaveTarget)
  /*
    미저장 판정의 기준 발효일은 마운트 시점 값으로 고정한다. 렌더마다 다시 계산한 tomorrow를
    기준으로 쓰면, 페이지를 열어둔 채 자정(Asia/Seoul)을 넘길 때 기준값만 D+2로 바뀌고 입력은
    D+1에 머물러 — 아무것도 건드리지 않았는데 미저장 편집으로 잡히고 이탈이 막힌다.
    입력 가능 최소값(min)은 그대로 살아 있는 tomorrow를 써야 하므로 둘을 분리한다.
  */
  const [baselineEffectiveFrom] = useState(initialEffectiveFrom)
  const [clientProblems, setClientProblems] = useState<string[]>([])
  // 휴무로 바꾸기 직전의 구간. 휴무를 풀면 기본값이 아니라 이 값을 되살린다.
  const [stashedPeriods, setStashedPeriods] = useState<
    Partial<Record<DayOfWeek, PeriodDraft[]>>
  >({})

  const wireDays = useMemo(() => toWire(draft), [draft])
  const problems = useMemo(
    () => validateWeeklyOperatingHours(wireDays),
    [wireDays],
  )

  /*
    저장하지 않은 편집이 있으면 이탈을 막는다. 시간표뿐 아니라 발효일도 함께 본다 —
    시간표를 그대로 두고 발효일만 옮겨 저장하는 것도 의미 있는 변경이라(같은 주간 시간표를
    더 늦은 날부터 적용) 발효일을 빼면 그 유일한 입력을 조용히 잃는다.
  */
  const initialSnapshot = useMemo(
    () =>
      JSON.stringify({
        days: toWeekDraft(initialDays),
        desiredEffectiveFrom: baselineEffectiveFrom,
      }),
    [initialDays, baselineEffectiveFrom],
  )
  const dirty =
    JSON.stringify({ days: wireDays, desiredEffectiveFrom }) !== initialSnapshot
  useUnsavedChangesWarning(dirty)
  useEffect(() => {
    onDirtyChange(dirty)
    return () => onDirtyChange(false)
  }, [dirty, onDirtyChange])

  // 편집을 시작하면 직전 저장 결과 메시지를 지운다 — "저장했습니다"와 검증 오류가 같이 떠서
  // 저장된 것으로 오독하는 일을 막는다.
  const updateDay = (
    dayOfWeek: DayOfWeek,
    mapper: (day: DayDraft) => DayDraft,
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
        : [newPeriod(DEFAULT_PERIOD)],
    }))
  }

  const addPeriod = (dayOfWeek: DayOfWeek) => {
    updateDay(dayOfWeek, (day) => ({
      ...day,
      periods: [...day.periods, newPeriod(DEFAULT_PERIOD)],
    }))
  }

  const removePeriod = (dayOfWeek: DayOfWeek, periodId: number) => {
    updateDay(dayOfWeek, (day) => ({
      ...day,
      periods: day.periods.filter((period) => period.id !== periodId),
    }))
  }

  const changePeriod = (
    dayOfWeek: DayOfWeek,
    periodId: number,
    field: 'startTime' | 'endTime',
    value: string,
  ) => {
    updateDay(dayOfWeek, (day) => ({
      ...day,
      periods: day.periods.map((period) =>
        period.id === periodId ? { ...period, [field]: value } : period,
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
    /*
      현재 시간표에서 새 미래 정책을 만들 때 이미 있는 발효일을 직접 입력하면 그 시간표를
      의도 없이 덮어쓴다. 예정 목록에서 대상을 선택해야만 같은 날짜를 수정할 수 있게 한다.
    */
    if (
      desiredEffectiveFrom !== initialDesiredEffectiveFrom &&
      scheduledEffectiveFroms.includes(desiredEffectiveFrom)
    ) {
      messages.unshift(
        '이미 저장된 예정 시간표입니다. 위 목록에서 해당 발효일을 선택해 수정해 주세요.',
      )
    }
    setClientProblems(messages)
    if (messages.length > 0) return

    onSave({
      desiredEffectiveFrom,
      saveMode: saveTarget ? 'UPDATE' : 'CREATE',
      targetScheduleId: saveTarget?.scheduleId,
      expectedUpdatedAt: saveTarget?.updatedAt,
      days: wireDays,
    })
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
                    <div key={period.id} className="flex items-center gap-2">
                      <Input
                        type="time"
                        className="w-32"
                        aria-label={`${DAY_LABEL[day.dayOfWeek]} ${index + 1}번째 구간 시작 시각`}
                        value={period.startTime}
                        onChange={(e) =>
                          changePeriod(
                            day.dayOfWeek,
                            period.id,
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
                            period.id,
                            'endTime',
                            e.target.value,
                          )
                        }
                      />
                      <Button
                        size="sm"
                        variant="ghost"
                        aria-label={`${DAY_LABEL[day.dayOfWeek]} ${index + 1}번째 구간 삭제`}
                        onClick={() => removePeriod(day.dayOfWeek, period.id)}
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
                disabled={effectiveFromFixed}
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
  const scheduledQuery = useScheduledOperatingHours()
  const update = useUpdateOperatingHours()
  // 선택값은 서버 조회 캐시에서 복구하되, 선택 중인 폼의 드래프트는 재조회 응답으로 교체하지 않는다.
  // 따라서 새로고침·재진입에는 복구되고, 편집 중 배경 재조회에는 입력이 보존된다.
  const [selectedScheduledEffectiveFrom, setSelectedScheduledEffectiveFrom] =
    useState<string | null>(null)
  const [formRevision, setFormRevision] = useState(0)
  const [hasUnsavedChanges, setHasUnsavedChanges] = useState(false)
  const [selectionWarning, setSelectionWarning] = useState<string | null>(null)

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

  const selectSchedule = (effectiveFrom: string | null) => {
    if (effectiveFrom === selectedScheduledEffectiveFrom) return
    if (hasUnsavedChanges) {
      setSelectionWarning(
        '저장하지 않은 변경이 있습니다. 먼저 저장하거나 되돌린 뒤 다른 시간표를 선택해 주세요.',
      )
      return
    }
    clearResult()
    setSelectionWarning(null)
    setSelectedScheduledEffectiveFrom(effectiveFrom)
    setFormRevision((revision) => revision + 1)
  }

  // 예정 시간표를 알 수 없는 상태에서 저장하면 같은 발효일을 조용히 덮어쓸 수 있다.
  // 현재 시간표가 정상이어도 예정 목록 조회가 끝날 때까지는 편집 폼을 열지 않는다.
  if (query.isLoading || scheduledQuery.isLoading) return <PageLoader />
  if (scheduledQuery.isError) {
    return (
      <ErrorState
        message={errorMessage(
          scheduledQuery.error,
          '적용 예정 진료시간을 불러오지 못했습니다.',
        )}
        onRetry={() => scheduledQuery.refetch()}
      />
    )
  }
  if (!query.data && !scheduleMissing) {
    return (
      <ErrorState
        message={errorMessage(query.error, '진료시간을 불러오지 못했습니다.')}
        onRetry={() => query.refetch()}
      />
    )
  }

  const schedule = query.data ?? null
  const scheduledSchedules = scheduledQuery.data ?? []
  const scheduledSchedule = scheduledSchedules.find(
    (item) => item.effectiveFrom === selectedScheduledEffectiveFrom,
  )
  const editableSchedule = scheduledSchedule ?? schedule
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
          {scheduledSchedule && (
            <p className="text-foreground">
              <strong>
                {dateKeyLabel(scheduledSchedule.effectiveFrom)}부터 적용 예정인
                시간표를 편집 중입니다.
              </strong>{' '}
              현재 적용 중인 시간표는 그 전날까지 유지됩니다.
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

      <Card>
        <CardHeader>
          <CardTitle className="text-base">적용 예정 시간표</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3 text-sm">
          {scheduledSchedules.length === 0 ? (
            <p className="text-muted-foreground">
              저장된 적용 예정 시간표가 없습니다.
            </p>
          ) : (
            <div className="flex flex-wrap gap-2">
              {scheduledSchedules.map((item) => (
                <Button
                  key={item.effectiveFrom}
                  size="sm"
                  variant={
                    item.effectiveFrom === selectedScheduledEffectiveFrom
                      ? 'default'
                      : 'outline'
                  }
                  onClick={() => {
                    selectSchedule(item.effectiveFrom)
                  }}
                >
                  {dateKeyLabel(item.effectiveFrom)}부터 적용
                </Button>
              ))}
            </div>
          )}
          {scheduledSchedule && schedule && (
            <Button
              size="sm"
              variant="ghost"
              onClick={() => {
                selectSchedule(null)
              }}
            >
              현재 시간표로 새 예정 시간표 만들기
            </Button>
          )}
          <p className="text-muted-foreground">
            예정 시간표를 수정하려면 발효일을 선택하세요. 현재 시간표에서 같은
            발효일을 입력해 덮어쓰지는 못합니다.
          </p>
          {selectionWarning && (
            <p className="text-destructive" role="alert">
              {selectionWarning}
            </p>
          )}
        </CardContent>
      </Card>

      <OperatingHoursForm
        key={`${selectedScheduledEffectiveFrom ?? 'current'}|${formRevision}`}
        initialDays={editableSchedule?.days ?? emptyWeek()}
        initialDesiredEffectiveFrom={scheduledSchedule?.effectiveFrom}
        scheduledEffectiveFroms={scheduledSchedules.map(
          (item) => item.effectiveFrom,
        )}
        effectiveFromFixed={Boolean(scheduledSchedule)}
        initialSaveTarget={
          scheduledSchedule
            ? {
                scheduleId: scheduledSchedule.scheduleId,
                updatedAt: scheduledSchedule.updatedAt,
              }
            : undefined
        }
        pending={update.isPending}
        onEdit={clearResult}
        onDirtyChange={setHasUnsavedChanges}
        onSave={(request) =>
          update.mutate(request, {
            onSuccess: (savedSchedule) => {
              setHasUnsavedChanges(false)
              setSelectionWarning(null)
              setFormRevision((revision) => revision + 1)
              setSelectedScheduledEffectiveFrom(savedSchedule.effectiveFrom)
            },
          })
        }
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
