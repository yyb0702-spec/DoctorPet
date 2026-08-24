import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { HospitalSearchPage } from './HospitalSearchPage'
import { useHospitalSearch } from '@/features/hospitals/hooks'

vi.mock('@/features/hospitals/hooks', () => ({
  useHospitalSearch: vi.fn(),
}))

vi.mock('@/features/hospitals/FavoriteButton', () => ({
  FavoriteButton: () => null,
}))

const searchResult = {
  content: [
    {
      hospitalId: 1,
      name: '행복동물병원',
      address: '서울 강남구',
      distanceKm: 1.2,
      businessStatus: 'OPEN',
      partnershipStatus: 'PARTNER',
      reservationAvailable: true,
      partnershipBadge: null,
      openNow: true,
      favorite: false,
    },
  ],
  page: 1,
  size: 20,
  totalElements: 1,
  totalPages: 1,
  first: true,
  last: true,
} as const

function renderPage() {
  return render(
    <MemoryRouter>
      <HospitalSearchPage />
    </MemoryRouter>,
  )
}

function latestSearchParams() {
  const calls = vi.mocked(useHospitalSearch).mock.calls
  return calls[calls.length - 1]?.[0]
}

const getCurrentPosition = vi.fn()

describe('HospitalSearchPage filters', () => {
  beforeEach(() => {
    vi.mocked(useHospitalSearch).mockReset()
    vi.mocked(useHospitalSearch).mockReturnValue({
      data: searchResult,
      isLoading: false,
      isError: false,
      isFetching: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useHospitalSearch>)
    getCurrentPosition.mockReset()
    Object.defineProperty(navigator, 'geolocation', {
      value: { getCurrentPosition },
      configurable: true,
    })
  })

  it('반려동물 필터가 supportedSpecies와 첫 페이지를 전달한다', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: '반려동물' }))
    await user.click(screen.getByRole('button', { name: '강아지' }))

    await waitFor(() => {
      expect(latestSearchParams()).toMatchObject({
        supportedSpecies: ['DOG'],
        page: 1,
      })
    })
  })

  it('진료 특성 다중 선택이 hospitalization·nightCare를 함께 전달한다', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: '진료 특성' }))
    await user.click(screen.getByRole('checkbox', { name: '야간진료' }))
    await user.click(screen.getByRole('checkbox', { name: '입원' }))

    await waitFor(() => {
      expect(latestSearchParams()).toMatchObject({
        nightCare: true,
        hospitalization: true,
        page: 1,
      })
    })
  })

  it('제휴 여부 필터가 partnerOnly=true를 전달한다', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: '제휴 여부' }))
    await user.click(screen.getByRole('button', { name: '제휴 병원만' }))

    await waitFor(() => {
      expect(latestSearchParams()).toMatchObject({
        partnerOnly: true,
        page: 1,
      })
    })
  })

  it('지역 필터가 시/도를 region으로 전달한다', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: '지역' }))
    await user.click(screen.getByRole('button', { name: '서울' }))

    await waitFor(() => {
      expect(latestSearchParams()).toMatchObject({ region: '서울', page: 1 })
    })
  })

  it('충청·전라·경상계 도는 축약명 대신 정식 명칭을 region으로 전달한다', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: '지역' }))
    await user.click(screen.getByRole('button', { name: '충북' }))

    await waitFor(() => {
      // 서버는 주소 부분일치라 "충북"은 "충청북도" 주소에 안 걸린다 → 정식 명칭을 보내야 한다.
      expect(latestSearchParams()).toMatchObject({ region: '충청북도', page: 1 })
    })
  })

  it('거리순은 현재 위치를 받아 sort=distance와 좌표를 전달한다', async () => {
    getCurrentPosition.mockImplementation((success) =>
      success({ coords: { latitude: 37.5, longitude: 127.0 } }),
    )
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: '이름순' }))
    await user.click(screen.getByRole('button', { name: /거리순/ }))

    await waitFor(() => {
      expect(latestSearchParams()).toMatchObject({
        sort: 'distance',
        latitude: 37.5,
        longitude: 127.0,
        page: 1,
      })
    })
  })

  it('위치 조회 중 이름순으로 되돌리면 늦게 온 콜백이 거리순을 강제하지 않는다', async () => {
    // 콜백을 붙잡아 두고 나중에 수동으로 발화한다(홀더 객체로 둬 타입 narrowing을 피한다).
    const held: {
      success?: (pos: { coords: { latitude: number; longitude: number } }) => void
    } = {}
    getCurrentPosition.mockImplementation((success) => {
      held.success = success
    })
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: '이름순' }))
    await user.click(screen.getByRole('button', { name: /거리순/ })) // 위치 조회 시작(콜백 보류)
    // 조회가 끝나기 전 사용자가 이름순으로 되돌린다(패널 안의 옵션을 특정).
    await user.click(
      within(screen.getByRole('group')).getByRole('button', { name: '이름순' }),
    )
    // 그 뒤 늦게 위치 콜백이 도착해도 거리순을 되살리면 안 된다.
    held.success?.({ coords: { latitude: 37.5, longitude: 127.0 } })

    await waitFor(() => {
      expect(latestSearchParams()?.sort).toBeUndefined()
    })
    expect(latestSearchParams()?.latitude).toBeUndefined()
  })

  it('위치 권한이 거부되면 거리순으로 바꾸지 않고 안내를 보여준다', async () => {
    getCurrentPosition.mockImplementation((_success, error) => error())
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: '이름순' }))
    await user.click(screen.getByRole('button', { name: /거리순/ }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('위치 권한이 필요')
    })
    expect(latestSearchParams()?.sort).toBeUndefined()
  })
})
