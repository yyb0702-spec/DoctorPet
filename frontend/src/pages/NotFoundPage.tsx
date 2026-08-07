// 404.
import { Link } from 'react-router-dom'
import { Button } from '@/components/ui/button'

export function NotFoundPage() {
  return (
    <div className="flex flex-col items-center gap-4 py-24 text-center">
      <p className="text-5xl font-bold text-muted-foreground">404</p>
      <p className="text-muted-foreground">페이지를 찾을 수 없습니다.</p>
      <Button asChild>
        <Link to="/">홈으로</Link>
      </Button>
    </div>
  )
}
