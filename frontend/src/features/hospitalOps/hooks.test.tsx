// 진료시간 PUT 응답은 미래 발효 시간표일 수 있으므로, 현재 시간표(GET) 캐시에 넣지 않는다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { hospitalOpsApi } from './api'
import { hospitalOpsKeys, useUpdateOperatingHours } from './hooks'
import type { OperatingHours } from './types'
import { hospitalKeys } from '@/features/hospitals/hooks'

vi.mock('./api', () => ({
  hospitalOpsApi: {
    updateOperatingHours: vi.fn(),
  },
}))

const currentHours: OperatingHours = {
  scheduleId: 1,
  updatedAt: '2026-08-20T09:00:00',
  effectiveFrom: '2026-08-01',
  days: [],
}

const futureHours: OperatingHours = {
  scheduleId: 2,
  updatedAt: '2026-08-20T10:00:00',
  effectiveFrom: '2026-08-22',
  days: [],
}

describe('useUpdateOperatingHours', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    })
    vi.mocked(hospitalOpsApi.updateOperatingHours).mockResolvedValue(
      futureHours,
    )
  })

  it('미래 발효 PUT 응답으로 현재 시간표 캐시를 덮지 않고 재조회한다', async () => {
    queryClient.setQueryData(hospitalOpsKeys.operatingHours, currentHours)
    const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries')
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    const { result } = renderHook(() => useUpdateOperatingHours(), { wrapper })

    await act(async () => {
      await result.current.mutateAsync({
        desiredEffectiveFrom: futureHours.effectiveFrom,
        saveMode: 'CREATE',
        days: futureHours.days,
      })
    })

    expect(queryClient.getQueryData(hospitalOpsKeys.operatingHours)).toEqual(
      currentHours,
    )
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: hospitalOpsKeys.operatingHours,
    })
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: hospitalKeys.all,
    })
  })
})
