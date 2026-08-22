import { render, screen, waitFor } from '@testing-library/react'
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
