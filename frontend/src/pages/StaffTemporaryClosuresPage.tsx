// 병원 스태프 — 임시휴진 등록·취소. POST/DELETE /api/hospital/temporary-closures.
//
// 백엔드에 임시휴진 "목록 조회" API가 없어 현재 걸린 휴진 전체를 받아올 수 없다. 그래서 화면은
// 등록·취소만 제공하고, 이번 화면에서 처리한 내역만 세션 동안 보여준다(새로고침하면 사라진다).
// 유추 표시(공개 슬롯 조회로 예약 가능 여부를 보는 방식)는 "임시휴진"과 "원래 휴무"를 구분하지
// 못해 오해를 부르므로 쓰지 않는다. 목록 API가 필요해지면 별도 이슈로 제안한다.
import { useState } from 'react'
import {
  useCancelTemporaryClosure,
  useCreateTemporaryClosure,
} from '@/features/hospitalOps/hooks'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { SLOT_PUBLICATION_DAYS } from '@/features/hospitalOps/types'
import { ApiError } from '@/lib/api/error'
import { dateKeyLabel, shiftDateKey, todaySeoulKey } from '@/lib/seoulTime'

interface HistoryEntry {
  businessDate: string
  action: 'created' | 'canceled'
}

function errorMessage(error: unknown): string {
  return error instanceof ApiError ? error.message : '처리에 실패했습니다.'
}

export function StaffTemporaryClosuresPage() {
  const today = todaySeoulKey()
  const tomorrow = shiftDateKey(today, 1)
  const [businessDate, setBusinessDate] = useState(tomorrow)
  const [history, setHistory] = useState<HistoryEntry[]>([])
  const [dateProblem, setDateProblem] = useState<string | null>(null)

  const create = useCreateTemporaryClosure()
  const cancel = useCancelTemporaryClosure()
  const pending = create.isPending || cancel.isPending

  const addHistory = (entry: HistoryEntry) =>
    setHistory((prev) => [entry, ...prev])

  // 날짜를 바꾸면 이전 날짜에 대한 오류 문구를 지운다 — 그대로 두면 새 날짜의 오류로 읽힌다.
  const changeBusinessDate = (next: string) => {
    setBusinessDate(next)
    setDateProblem(null)
    if (create.isError) create.reset()
    if (cancel.isError) cancel.reset()
  }

  // 등록·취소 모두 "요청일 다음 날부터"만 가능하다(오늘도 불가).
  const guardDate = (): boolean => {
    if (businessDate <= today) {
      setDateProblem('임시휴진은 내일 이후 날짜만 등록·취소할 수 있습니다.')
      return false
    }
    setDateProblem(null)
    return true
  }

  const handleCreate = () => {
    if (!guardDate()) return
    cancel.reset()
    const target = businessDate
    create.mutate(target, {
      onSuccess: () => addHistory({ businessDate: target, action: 'created' }),
    })
  }

  const handleCancel = () => {
    if (!guardDate()) return
    create.reset()
    const target = businessDate
    cancel.mutate(target, {
      onSuccess: () => addHistory({ businessDate: target, action: 'canceled' }),
    })
  }

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">임시휴진 관리</h1>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">처리 전에 알아두기</CardTitle>
        </CardHeader>
        <CardContent className="space-y-1.5 text-sm text-muted-foreground">
          <p>· 등록·취소 모두 내일 이후 날짜만 가능합니다(오늘은 불가).</p>
          <p>
            · <strong>예약이 있는 날은 등록할 수 없습니다.</strong> 예약을
            강제로 취소하지 않으니, 먼저 예약을 정리한 뒤 등록해 주세요.
          </p>
          <p>
            · 등록하면 그 날의 예약 없는 슬롯이 사라져 새 예약을 받지 않습니다.
          </p>
          <p>
            · 취소하면 그 날 슬롯이 다시 열립니다. 오늘부터{' '}
            {SLOT_PUBLICATION_DAYS}일 뒤까지의 발행창 안이면{' '}
            <strong>즉시</strong> 다시 만들어지고, 그 밖의 날짜는 발행
            스케줄러가 도달할 때 반영됩니다.
          </p>
          <p>· 휴진 취소는 휴진 영업일 전날까지만 할 수 있습니다.</p>
        </CardContent>
      </Card>

      <Card>
        <CardContent className="space-y-3 p-4">
          <div className="flex flex-wrap items-end gap-3">
            <div className="space-y-1">
              <Label htmlFor="businessDate">영업일</Label>
              <Input
                id="businessDate"
                type="date"
                className="w-44"
                min={tomorrow}
                value={businessDate}
                onChange={(e) => changeBusinessDate(e.target.value)}
              />
            </div>
            <Button disabled={pending} onClick={handleCreate}>
              {create.isPending ? '등록 중…' : '임시휴진 등록'}
            </Button>
            <Button variant="outline" disabled={pending} onClick={handleCancel}>
              {cancel.isPending ? '취소 중…' : '임시휴진 취소'}
            </Button>
          </div>

          {dateProblem && (
            <p className="text-sm text-destructive">{dateProblem}</p>
          )}
          {create.isError && (
            <p className="text-sm text-destructive">
              {errorMessage(create.error)}
            </p>
          )}
          {cancel.isError && (
            <p className="text-sm text-destructive">
              {errorMessage(cancel.error)}
            </p>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">이번 화면에서 처리한 내역</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
          <p className="text-sm text-muted-foreground">
            임시휴진 목록 조회 API가 없어 현재 걸린 휴진 전체는 표시할 수
            없습니다. 아래는 이번 접속에서 처리한 내역만이며 새로고침하면
            사라집니다.
          </p>
          {history.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              아직 처리한 내역이 없습니다.
            </p>
          ) : (
            <ul className="space-y-1 text-sm">
              {history.map((entry, index) => (
                <li
                  key={`${entry.businessDate}-${entry.action}-${index}`}
                  className="flex items-center gap-2"
                >
                  <Badge
                    variant={
                      entry.action === 'created' ? 'destructive' : 'secondary'
                    }
                  >
                    {entry.action === 'created' ? '휴진 등록' : '휴진 취소'}
                  </Badge>
                  {dateKeyLabel(entry.businessDate)}
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
