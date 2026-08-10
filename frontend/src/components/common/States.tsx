// 로딩·에러·빈 상태 공통 표시 컴포넌트.
import { Loader2 } from 'lucide-react'
import { Button } from '@/components/ui/button'

export function PageLoader({ label = '불러오는 중…' }: { label?: string }) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 py-16 text-muted-foreground">
      <Loader2 className="h-6 w-6 animate-spin" />
      <span className="text-sm">{label}</span>
    </div>
  )
}

export function ErrorState({
  message = '문제가 발생했습니다.',
  onRetry,
}: {
  message?: string
  onRetry?: () => void
}) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 py-16">
      <p className="text-sm text-destructive">{message}</p>
      {onRetry && (
        <Button variant="outline" size="sm" onClick={onRetry}>
          다시 시도
        </Button>
      )}
    </div>
  )
}

export function EmptyState({ message }: { message: string }) {
  return (
    <div className="flex items-center justify-center py-16 text-sm text-muted-foreground">
      {message}
    </div>
  )
}
