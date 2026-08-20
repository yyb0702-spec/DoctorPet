// 검색 필터용 드롭다운 — 버튼을 누르면 패널이 펼쳐지고, 바깥을 클릭하거나 Esc를 누르면 닫힌다.
// 접근성 속성은 같은 저장소의 UserMenu와 맞춘다(aria-haspopup·aria-expanded + 패널 라벨링).
// 패널에 role="menu"를 쓰지 않는 이유: 진료 특성 패널은 체크박스 묶음이라 menu 역할과 맞지 않는다.
// 그래서 그룹으로 라벨링하고, 단일 선택 항목만 눌린 상태(aria-pressed)를 노출한다.
import { useEffect, useRef, useState } from 'react'
import { ChevronDown } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

export function FilterDropdown({
  label,
  active,
  children,
}: {
  label: string
  // 하나라도 선택돼 있으면 버튼을 강조한다(단일·다중 선택 공용).
  active: boolean
  children: React.ReactNode
}) {
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const onOutside = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) {
        setOpen(false)
      }
    }
    const onEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onOutside)
    document.addEventListener('keydown', onEscape)
    return () => {
      document.removeEventListener('mousedown', onOutside)
      document.removeEventListener('keydown', onEscape)
    }
  }, [open])

  return (
    <div ref={rootRef} className="relative">
      <Button
        type="button"
        size="sm"
        variant={active ? 'default' : 'outline'}
        aria-haspopup="true"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
      >
        {label}
        <ChevronDown
          className={cn('ml-1 h-3.5 w-3.5 transition-transform', open && 'rotate-180')}
        />
      </Button>
      {open && (
        <div
          role="group"
          aria-label={`${label} 필터`}
          className="absolute left-0 top-full z-10 mt-1 min-w-40 rounded-md border bg-background p-1 shadow-md"
        >
          {children}
        </div>
      )}
    </div>
  )
}

// 드롭다운 패널 안의 단일 선택 항목(종, 제휴 여부 등).
export function FilterOption({
  selected,
  onClick,
  children,
}: {
  selected: boolean
  onClick: () => void
  children: React.ReactNode
}) {
  return (
    <button
      type="button"
      aria-pressed={selected}
      onClick={onClick}
      className={cn(
        'block w-full rounded-sm px-3 py-1.5 text-left text-sm transition-colors hover:bg-accent',
        selected && 'bg-accent font-medium',
      )}
    >
      {children}
    </button>
  )
}

// 드롭다운 패널 안의 체크박스 항목(야간진료·응급 등 다중 선택).
export function FilterCheckbox({
  checked,
  onChange,
  children,
}: {
  checked: boolean
  onChange: () => void
  children: React.ReactNode
}) {
  return (
    <label className="flex cursor-pointer items-center gap-2 rounded-sm px-3 py-1.5 text-sm hover:bg-accent">
      <input
        type="checkbox"
        checked={checked}
        onChange={onChange}
        className="h-3.5 w-3.5 rounded border-input"
      />
      {children}
    </label>
  )
}
