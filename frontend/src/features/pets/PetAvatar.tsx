// 반려동물 사진이 없는 경우에도 같은 크기의 발자국 자리표시자를 보여주는 공용 아바타.
import { useState } from 'react'
import { PawPrint } from 'lucide-react'
import { cn } from '@/lib/utils'

interface PetAvatarProps {
  name: string
  imageUrl: string | null | undefined
  className?: string
}

export function PetAvatar({ name, imageUrl, className }: PetAvatarProps) {
  // 유효한 저장 URL도 객체 삭제·권한 설정 오류 등으로 브라우저 로드에 실패할 수 있다. URL별로
  // 실패를 기억하면 같은 URL의 재시도 루프는 막되, 새 사진 URL로 교체되면 다시 표시할 수 있다.
  const [failedImageUrl, setFailedImageUrl] = useState<string | null>(null)
  const shape = cn('shrink-0 rounded-full object-cover', className)

  if (imageUrl && failedImageUrl !== imageUrl) {
    return (
      <img
        src={imageUrl}
        alt={`${name} 프로필 사진`}
        className={shape}
        onError={() => setFailedImageUrl(imageUrl)}
      />
    )
  }

  return (
    <span
      className={cn(
        'flex shrink-0 items-center justify-center rounded-full bg-muted',
        className,
      )}
      aria-label={`${name} 프로필 사진 없음`}
    >
      <PawPrint className="h-4 w-4 text-muted-foreground" aria-hidden="true" />
    </span>
  )
}
