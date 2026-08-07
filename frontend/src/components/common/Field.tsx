// 라벨 + 입력 + 에러메시지를 묶는 폼 필드 래퍼.
// 에러가 있으면 자식 입력에 aria-invalid/aria-describedby를 연결해 스크린리더가 읽게 한다.
import { cloneElement, isValidElement, type ReactElement, type ReactNode } from 'react'
import { Label } from '@/components/ui/label'

export function Field({
  label,
  htmlFor,
  error,
  children,
}: {
  label: string
  htmlFor?: string
  error?: string
  children: ReactNode
}) {
  const errorId = htmlFor ? `${htmlFor}-error` : undefined
  // 단일 요소 자식이면 접근성 속성을 주입한다(입력이 아닌 경우엔 무해).
  const enhanced =
    error && errorId && isValidElement(children)
      ? cloneElement(children as ReactElement<Record<string, unknown>>, {
          'aria-invalid': true,
          'aria-describedby': errorId,
        })
      : children

  return (
    <div className="space-y-1.5">
      <Label htmlFor={htmlFor}>{label}</Label>
      {enhanced}
      {error && (
        <p id={errorId} className="text-xs text-destructive">
          {error}
        </p>
      )}
    </div>
  )
}
