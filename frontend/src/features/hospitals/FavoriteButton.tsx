// 병원 찜 하트 버튼. 검색 카드·병원 상세·찜 목록이 같은 컴포넌트를 쓴다.
import { useLocation, useNavigate } from 'react-router-dom'
import { Heart } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { useAuthStore } from '@/lib/auth/authStore'
import { useMe } from '@/features/members/hooks'
import { MemberRole } from '@/types/enums'
import { useToggleHospitalFavorite } from './hooks'

interface FavoriteButtonProps {
  hospitalId: number
  favorite: boolean
  // 버튼 안에 라벨을 함께 보여줄지(상세 화면처럼 공간이 있는 곳에서 쓴다).
  withLabel?: boolean
  className?: string
}

export function FavoriteButton({
  hospitalId,
  favorite,
  withLabel = false,
  className,
}: FavoriteButtonProps) {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  const navigate = useNavigate()
  const location = useLocation()
  const me = useMe()
  const toggle = useToggleHospitalFavorite()

  /*
    찜은 백엔드가 보호자 전용으로 제한한다(SecurityConfig → hasRole("GUARDIAN")). 병원 스태프에게
    하트를 보여주면 누르는 순간 403이라 감춘다(리뷰 P2).

    감추는 기준은 "스태프로 확인됨"이지 "보호자로 확인되지 않음"이 아니다(자기검토) — useMe는 실패 시
    재시도하는 동안 계속 undefined이고, 그 창에서 하트를 지우면 정상 보호자가 기능을 잃는다. 역할을
    모르면 보여주고, 최종 판단은 서버에 맡긴다. 비로그인은 눌렀을 때 로그인으로 안내한다.
  */
  if (isAuthenticated && me.data != null && me.data.role !== MemberRole.GUARDIAN) {
    return null
  }

  /*
    비로그인 상태에서는 서버가 favorite=false로 주므로 하트는 늘 비어 보인다. 눌러도 401이 날 뿐이라
    호출하지 않고 로그인으로 보낸다 — 어디서 눌렀는지 state로 넘겨 로그인 후 제자리로 돌아온다.
  */
  const handleClick = () => {
    if (!isAuthenticated) {
      /*
        LoginPage는 `state.from.pathname`을 읽는다(ProtectedRoute·StaffRoute와 같은 계약).
        문자열을 넘기면 로그인 후 항상 '/'로 떨어져 원래 병원 화면으로 돌아오지 못한다(리뷰 P2).
      */
      navigate('/login', { state: { from: location } })
      return
    }
    toggle.mutate({ hospitalId, favorite: !favorite })
  }

  return (
    <Button
      type="button"
      variant="ghost"
      size={withLabel ? 'sm' : 'icon'}
      aria-label={favorite ? '찜 해제' : '찜하기'}
      aria-pressed={favorite}
      // 낙관적 편집이라 연타해도 화면은 즉시 따라오지만, 요청이 겹치지 않게 진행 중에는 막는다.
      disabled={toggle.isPending}
      onClick={handleClick}
      className={className}
    >
      <Heart
        className={cn(
          'h-4 w-4',
          favorite ? 'fill-destructive text-destructive' : 'text-muted-foreground',
        )}
      />
      {withLabel && <span>{favorite ? '찜 해제' : '찜하기'}</span>}
    </Button>
  )
}
