// 사진 유무와 무관하게 예약 화면의 반려동물 식별 자리가 유지되는지 검증한다.
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { PetAvatar } from './PetAvatar'

describe('PetAvatar', () => {
  it('사진 URL이 있으면 반려동물 이름을 alt로 가진 이미지를 표시한다', () => {
    render(
      <PetAvatar
        name="초코"
        imageUrl="https://images.example/pets/1/choco.png"
        className="h-7 w-7"
      />,
    )

    expect(screen.getByRole('img', { name: '초코 프로필 사진' })).toHaveAttribute(
      'src',
      'https://images.example/pets/1/choco.png',
    )
  })

  it('사진이 없으면 이름을 설명하는 발자국 자리표시자를 표시한다', () => {
    render(<PetAvatar name="초코" imageUrl={null} className="h-7 w-7" />)

    expect(screen.getByLabelText('초코 프로필 사진 없음')).toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })
})
