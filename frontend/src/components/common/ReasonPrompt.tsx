// 사유 입력이 필요한 액션(거절·노쇼 확정/정정·환불) 공통 UI.
// 접힌 트리거 버튼 → 펼치면 사유 입력(옵션 목록이면 select, 아니면 자유 텍스트) + 확인/취소.
import { useState } from 'react'
import { Button, type ButtonProps } from '@/components/ui/button'
import { Select } from '@/components/ui/select'
import { Field } from '@/components/common/Field'

export function ReasonPrompt({
  triggerLabel,
  triggerVariant = 'outline',
  confirmLabel,
  pending,
  errorMessage,
  maxLength,
  options,
  onConfirm,
}: {
  triggerLabel: string
  triggerVariant?: ButtonProps['variant']
  confirmLabel: string
  pending: boolean
  errorMessage?: string
  maxLength?: number
  options?: readonly string[]
  onConfirm: (reason: string) => void
}) {
  const [open, setOpen] = useState(false)
  const [reason, setReason] = useState(options?.[0] ?? '')

  if (!open) {
    return (
      <Button
        size="sm"
        variant={triggerVariant}
        onClick={() => {
          setReason(options?.[0] ?? '')
          setOpen(true)
        }}
      >
        {triggerLabel}
      </Button>
    )
  }

  const trimmed = reason.trim()
  const canSubmit = trimmed.length > 0 && (!maxLength || trimmed.length <= maxLength)

  return (
    <div className="space-y-2 rounded-md border bg-muted/30 p-3">
      <Field label="사유">
        {options ? (
          <Select value={reason} onChange={(e) => setReason(e.target.value)}>
            {options.map((o) => (
              <option key={o} value={o}>
                {o}
              </option>
            ))}
          </Select>
        ) : (
          <textarea
            className="flex min-h-16 w-full rounded-md border border-input bg-background px-3 py-2 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            value={reason}
            maxLength={maxLength}
            onChange={(e) => setReason(e.target.value)}
          />
        )}
      </Field>
      {errorMessage && <p className="text-sm text-destructive">{errorMessage}</p>}
      <div className="flex gap-2">
        <Button
          size="sm"
          disabled={!canSubmit || pending}
          onClick={() => onConfirm(trimmed)}
        >
          {pending ? '처리 중…' : confirmLabel}
        </Button>
        <Button
          size="sm"
          variant="ghost"
          disabled={pending}
          onClick={() => setOpen(false)}
        >
          취소
        </Button>
      </div>
    </div>
  )
}
