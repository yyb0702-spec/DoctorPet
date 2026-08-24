import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { HomePage } from './HomePage'
import { useHospitalSearch } from '@/features/hospitals/hooks'

vi.mock('@/features/hospitals/hooks', () => ({
  useHospitalSearch: vi.fn(),
}))

function makeHospital(id: number, name: string) {
  return {
    hospitalId: id,
    name,
    address: '서울 강남구',
    distanceKm: 1.2,
    businessStatus: 'OPEN',
    partnershipStatus: 'PARTNER',
    reservationAvailable: true,
    partnershipBadge: null,
    openNow: true,
    favorite: false,
  }
}

// 백엔드는 필터 없는 초기 목록이 size=20일 때만 제휴 우선 정렬을 태운다.
// 홈은 그 경로를 타야 하므로 5곳을 돌려줘 앞 3곳만 잘리는지 함께 검증한다.
const searchResult = {
  content: [
    makeHospital(1, '가나동물병원'),
    makeHospital(2, '나다동물병원'),
    makeHospital(3, '다라동물병원'),
    makeHospital(4, '라마동물병원'),
    makeHospital(5, '마바동물병원'),
  ],
  page: 1,
  size: 20,
  totalElements: 5,
  totalPages: 1,
  first: true,
  last: true,
} as const

function renderPage() {
  return render(
    <MemoryRouter>
      <HomePage />
    </MemoryRouter>,
  )
}

describe('HomePage 추천 병원', () => {
  beforeEach(() => {
    vi.mocked(useHospitalSearch).mockReset()
    vi.mocked(useHospitalSearch).mockReturnValue({
      data: searchResult,
      isLoading: false,
      isError: false,
      isFetching: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useHospitalSearch>)
  })

  it('제휴 우선 초기 목록 경로를 타도록 page=1·size=20으로 요청한다', () => {
    renderPage()
    // size=3이면 백엔드가 초기 리스팅으로 인정하지 않아 이름순으로 떨어진다.
    expect(vi.mocked(useHospitalSearch)).toHaveBeenCalledWith({
      page: 1,
      size: 20,
    })
  })

  it('초기 목록이 3곳을 넘어도 상위 3곳만 렌더링한다', () => {
    renderPage()
    expect(screen.getByText('가나동물병원')).toBeInTheDocument()
    expect(screen.getByText('나다동물병원')).toBeInTheDocument()
    expect(screen.getByText('다라동물병원')).toBeInTheDocument()
    expect(screen.queryByText('라마동물병원')).not.toBeInTheDocument()
    expect(screen.queryByText('마바동물병원')).not.toBeInTheDocument()
  })
})
