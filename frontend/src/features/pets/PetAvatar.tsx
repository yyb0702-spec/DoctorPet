// 반려동물 사진이 없는 경우에도 같은 크기의 발자국 자리표시자를 보여주는 공용 아바타.
import { PawPrint } from 'lucide-react'
import { cn } from '@/lib/utils'

interface PetAvatarProps {
  name: string
  imageUrl: string | null | undefined
  className?: string
}

export function PetAvatar({ name, imageUrl, className }: PetAvatarProps) {
  const shape = cn('shrink-0 rounded-full object-cover', className)

  if (imageUrl) {
    return <img src={imageUrl} alt={`${name} 프로필 사진`} className={shape} />
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
