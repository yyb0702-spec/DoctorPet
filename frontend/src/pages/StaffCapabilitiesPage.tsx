// 병원 스태프 — 진료역량 관리(분류별 체크박스, 전체 교체 저장). GET/PUT /api/hospital/capabilities.
import { useState } from 'react'
import {
  useCapabilities,
  useUpdateCapabilities,
} from '@/features/hospitalOps/hooks'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { ErrorState, PageLoader } from '@/components/common/States'
import {
  CAPABILITY_GROUPS,
  CAPABILITY_TYPE_LABEL,
  capabilityLabel,
} from '@/lib/capabilities'
import { ApiError } from '@/lib/api/error'
import type { CapabilityValue } from '@/types/enums'

function errorMessage(error: unknown): string {
  return error instanceof ApiError ? error.message : '저장에 실패했습니다.'
}

// 서버 값이 바뀌면 부모가 key로 이 컴포넌트를 새로 만든다 — 편집 상태 동기화를 effect로 하지 않는다.
function CapabilityPicker({
  initial,
  pending,
  onEdit,
  onSave,
}: {
  initial: CapabilityValue[]
  pending: boolean
  onEdit: () => void
  onSave: (capabilities: CapabilityValue[]) => void
}) {
  const [selected, setSelected] = useState<Set<CapabilityValue>>(
    () => new Set(initial),
  )

  // 편집을 시작하면 직전 저장 결과 메시지를 지운다 — 바뀐 선택과 "저장했습니다"가 같이 떠서
  // 저장된 것으로 오독하는 일을 막는다.
  const toggle = (capability: CapabilityValue) => {
    onEdit()
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(capability)) next.delete(capability)
      else next.add(capability)
      return next
    })
  }

  return (
    <>
      <Card>
        <CardContent className="space-y-5 p-4">
          {CAPABILITY_GROUPS.map((group) => (
            <div key={group.type} className="space-y-2">
              <h2 className="text-sm font-semibold">
                {CAPABILITY_TYPE_LABEL[group.type]}
              </h2>
              <div className="flex flex-wrap gap-3">
                {group.values.map((capability) => (
                  <label
                    key={capability}
                    className="flex items-center gap-1.5 text-sm"
                  >
                    <input
                      type="checkbox"
                      checked={selected.has(capability)}
                      onChange={() => toggle(capability)}
                    />
                    {capabilityLabel(capability)}
                  </label>
                ))}
              </div>
            </div>
          ))}
        </CardContent>
      </Card>

      <Card>
        <CardContent className="space-y-3 p-4">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-sm text-muted-foreground">
              선택 {selected.size}개
            </span>
            {[...selected].map((capability) => (
              <Badge key={capability} variant="secondary">
                {capabilityLabel(capability)}
              </Badge>
            ))}
          </div>
          {/* 저장은 전체 교체다 — 체크를 푼 항목은 삭제된다. */}
          <Button disabled={pending} onClick={() => onSave([...selected])}>
            {pending ? '저장 중…' : '진료역량 저장'}
          </Button>
        </CardContent>
      </Card>
    </>
  )
}

export function StaffCapabilitiesPage() {
  const query = useCapabilities()
  const update = useUpdateCapabilities()

  const clearResult = () => {
    if (update.isSuccess || update.isError) update.reset()
  }

  if (query.isLoading) return <PageLoader />
  if (query.isError || !query.data) {
    return (
      <ErrorState
        message={errorMessage(query.error)}
        onRetry={() => query.refetch()}
      />
    )
  }

  const capabilities = query.data.capabilities

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">진료역량 관리</h1>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">저장 전에 알아두기</CardTitle>
        </CardHeader>
        <CardContent className="space-y-1.5 text-sm text-muted-foreground">
          <p>
            · 저장하면 체크한 항목이 병원의 진료역량 <strong>전체</strong>가
            됩니다. 체크를 푼 항목은 삭제됩니다.
          </p>
          <p>
            · 같은 값이 보호자 화면의{' '}
            <strong>병원 검색 필터·병원 상세 태그</strong>에 그대로 쓰입니다.
          </p>
          <p>· 폐업 처리된 병원은 진료역량을 수정할 수 없습니다.</p>
        </CardContent>
      </Card>

      <CapabilityPicker
        key={capabilities.join('|')}
        initial={capabilities}
        pending={update.isPending}
        onEdit={clearResult}
        onSave={(next) => update.mutate(next)}
      />

      {update.isError && (
        <p className="text-sm text-destructive">{errorMessage(update.error)}</p>
      )}
      {update.isSuccess && (
        <p className="text-sm">
          저장했습니다. 보호자 화면에는 {update.data.capabilities.length}개
          항목이 노출됩니다.
        </p>
      )}
    </div>
  )
}
