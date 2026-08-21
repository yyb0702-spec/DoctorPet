// 진료역량 저장이 "전체 교체"라서, 화면에 체크박스가 없는 값이 있으면 그 값의 운명이 불분명해진다.
// 프론트가 모르는 값(서버 화이트리스트가 먼저 늘어난 경우)은 ① 그대로 두면 보존되고
// ② 체크를 풀면 삭제되어야 한다. 조용히 사라지거나, 해제할 방법이 없으면 둘 다 문제다.
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { RouterProvider, createMemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { StaffCapabilitiesPage } from './StaffCapabilitiesPage'
import type { CapabilityValue } from '@/types/enums'

const mutate = vi.fn()
const capabilitiesQuery = {
  data: undefined as { capabilities: CapabilityValue[] } | undefined,
  error: null as unknown,
  isLoading: false,
  isError: false,
  refetch: vi.fn(),
}

vi.mock('@/features/hospitalOps/hooks', () => ({
  useCapabilities: () => capabilitiesQuery,
  useUpdateCapabilities: () => ({
    mutate,
    data: undefined,
    isPending: false,
    isError: false,
    isSuccess: false,
    error: null,
    reset: vi.fn(),
  }),
}))

// 이탈 경고 훅(useBlocker)이 데이터 라우터를 요구하므로 메모리 라우터로 감싼다.
function renderPage() {
  const router = createMemoryRouter([
    { path: '/', element: <StaffCapabilitiesPage /> },
  ])
  return render(<RouterProvider router={router} />)
}

beforeEach(() => {
  mutate.mockClear()
  // 마지막 값은 프론트 화이트리스트(19개)에 없는 값이다.
  capabilitiesQuery.data = {
    capabilities: ['DOG', 'XRAY', 'HYPERBARIC_THERAPY'] as CapabilityValue[],
  }
})

describe('StaffCapabilitiesPage', () => {
  it('모르는 값도 체크박스로 보여주고 그대로 저장하면 보존한다', async () => {
    renderPage()

    expect(screen.getByText('분류 미확인')).toBeInTheDocument()
    expect(
      screen.getByRole('checkbox', { name: 'HYPERBARIC_THERAPY' }),
    ).toBeChecked()

    await userEvent.click(screen.getByRole('button', { name: '진료역량 저장' }))

    expect(mutate).toHaveBeenCalledTimes(1)
    expect([...mutate.mock.calls[0][0]].sort()).toEqual([
      'DOG',
      'HYPERBARIC_THERAPY',
      'XRAY',
    ])
  })

  it('모르는 값도 체크를 풀면 저장에서 빠진다', async () => {
    renderPage()

    await userEvent.click(
      screen.getByRole('checkbox', { name: 'HYPERBARIC_THERAPY' }),
    )
    await userEvent.click(screen.getByRole('button', { name: '진료역량 저장' }))

    expect([...mutate.mock.calls[0][0]].sort()).toEqual(['DOG', 'XRAY'])
  })
})
