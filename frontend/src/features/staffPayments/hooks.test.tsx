// 대시보드 미수금 카드는 스태프 조작 없이도 새 미수금이 생기므로 주기 갱신(refetchInterval)을 켠다.
// 결제 관리 화면은 폴링이 필요 없어 기본은 끈다 — 두 계약을 회귀 없이 고정한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { staffPaymentApi } from './api'
import { useHospitalPayments } from './hooks'

vi.mock('./api', () => ({
  staffPaymentApi: {
    listHospitalPayments: vi.fn(),
  },
}))

const emptyPage = {
  content: [],
  page: 0,
  size: 100,
  totalElements: 0,
  totalPages: 1,
  first: true,
  last: true,
}

describe('useHospitalPayments', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    vi.useFakeTimers()
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    vi.mocked(staffPaymentApi.listHospitalPayments).mockReset()
    vi.mocked(staffPaymentApi.listHospitalPayments).mockResolvedValue(emptyPage)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )

  it('refetchInterval을 주면 주기가 지날 때마다 다시 조회한다(대시보드 미수금 카드)', async () => {
    renderHook(() => useHospitalPayments(0, 100, { refetchInterval: 30_000 }), {
      wrapper,
    })

    // 초기 조회를 흘려보낸다(fake timer 아래에서 waitFor는 폴링이 멈춰 쓰지 못한다).
    await vi.advanceTimersByTimeAsync(0)
    expect(staffPaymentApi.listHospitalPayments).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(30_000)
    expect(staffPaymentApi.listHospitalPayments).toHaveBeenCalledTimes(2)

    await vi.advanceTimersByTimeAsync(30_000)
    expect(staffPaymentApi.listHospitalPayments).toHaveBeenCalledTimes(3)
  })

  it('refetchInterval이 없으면 주기가 지나도 재조회하지 않는다(결제 관리 화면)', async () => {
    renderHook(() => useHospitalPayments(0, 20), { wrapper })

    await vi.advanceTimersByTimeAsync(0)
    expect(staffPaymentApi.listHospitalPayments).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(60_000)
    expect(staffPaymentApi.listHospitalPayments).toHaveBeenCalledTimes(1)
  })
})
