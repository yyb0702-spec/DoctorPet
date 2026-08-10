// 우상단 사용자 메뉴 — [닉네임 ▾] → 마이페이지 / 로그아웃.
import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ChevronDown, User } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { useMe } from '@/features/members/hooks'
import { useLogout } from '@/features/auth/hooks'

export function UserMenu() {
  const [open, setOpen] = useState(false)
  const me = useMe()
  const logout = useLogout()
  const navigate = useNavigate()

  const name = me.data?.nickname ?? '내 계정'

  const handleSignOut = () => {
    setOpen(false)
    // 로컬 토큰만 지우면 서버 Redis의 Refresh Token이 계속 유효하게 남으므로
    // /api/auth/logout까지 호출해 서버 세션도 함께 폐기한다(useLogout이 처리).
    logout.mutate(undefined, { onSettled: () => navigate('/login') })
  }

  return (
    <div className="relative">
      <Button
        variant="ghost"
        size="sm"
        onClick={() => setOpen((v) => !v)}
        aria-haspopup="menu"
        aria-expanded={open}
      >
        <User className="h-4 w-4" />
        <span className="max-w-24 truncate">{name}</span>
        <ChevronDown className="h-3.5 w-3.5" />
      </Button>
      {open && (
        <div
          role="menu"
          className="absolute right-0 mt-2 w-40 rounded-lg border bg-popover p-1 shadow-lg"
        >
          <Link
            to="/mypage"
            role="menuitem"
            onClick={() => setOpen(false)}
            className="block rounded-md px-3 py-2 text-sm hover:bg-accent"
          >
            마이페이지
          </Link>
          <button
            type="button"
            role="menuitem"
            onClick={handleSignOut}
            className="block w-full rounded-md px-3 py-2 text-left text-sm hover:bg-accent"
          >
            로그아웃
          </button>
        </div>
      )}
    </div>
  )
}
