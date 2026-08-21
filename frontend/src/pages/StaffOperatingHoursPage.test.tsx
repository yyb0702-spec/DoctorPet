// 진료시간 화면의 두 계약을 고정한다(PR #198 자체 리뷰).
//  1) 클라이언트 검증에 걸린 입력은 서버로 보내지 않는다 — 겹침을 서버까지 보내면 안 된다.
//  2) 유효 스케줄이 없는 병원(HOSPITAL_004)은 폼을 열어 최초 등록이 가능해야 한다.
//     GET 실패를 그대로 에러 화면으로 처리하면 PUT으로 만들 수 있는데도 등록 경로가 사라진다.
//  3) 휴무 토글은 구간을 파기하지 않고 되살린다.
//  4) 저장하지 않은 편집이 있으면 새로고침·탭 닫기를 막는다.
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { RouterProvider, createMemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { StaffOperatingHoursPage } from './StaffOperatingHoursPage'
import { ApiError } from '@/lib/api/error'
import type { OperatingHours } from '@/features/hospitalOps/types'

const mutate = vi.fn()
const operatingHoursQuery = {
  data: undefined as OperatingHours | undefined,
  error: null as unknown,
  isLoading: false,
  isError: false,
  refetch: vi.fn(),
}

vi.mock('@/features/hospitalOps/hooks', () => ({
  useOperatingHours: () => operatingHoursQuery,
  useUpdateOperatingHours: () => ({
    mutate,
    variables: undefined,
    data: undefined,
    isPending: false,
    isError: false,
    isSuccess: false,
    error: null,
    reset: vi.fn(),
  }),
}))

const WEEKDAY_HOURS: OperatingHours = {
  effectiveFrom: '2026-07-21',
  days: [
    {
      dayOfWeek: 'MONDAY',
      periods: [{ startTime: '09:00', endTime: '18:00' }],
    },
    { dayOfWeek: 'TUESDAY', periods: [] },
    {
      dayOfWeek: 'WEDNESDAY',
      periods: [
        { startTime: '09:00', endTime: '13:00' },
        { startTime: '14:00', endTime: '18:00' },
      ],
    },
    { dayOfWeek: 'THURSDAY', periods: [] },
    { dayOfWeek: 'FRIDAY', periods: [] },
    { dayOfWeek: 'SATURDAY', periods: [] },
    { dayOfWeek: 'SUNDAY', periods: [] },
  ],
}

// 이탈 경고 훅(useBlocker)이 데이터 라우터를 요구하므로 메모리 라우터로 감싼다.
function renderPage() {
  const router = createMemoryRouter([
    { path: '/', element: <StaffOperatingHoursPage /> },
  ])
  return render(<RouterProvider router={router} />)
}

beforeEach(() => {
  mutate.mockClear()
  operatingHoursQuery.data = undefined
  operatingHoursQuery.error = null
  operatingHoursQuery.isError = false
})

describe('StaffOperatingHoursPage', () => {
  it('구간이 겹치면 오류만 보여주고 저장 요청을 보내지 않는다', async () => {
    operatingHoursQuery.data = WEEKDAY_HOURS
    renderPage()

    // 월요일에 09:00~18:00과 겹치는 구간을 하나 더 만든다.
    await userEvent.click(
      screen.getAllByRole('button', { name: '구간 추가' })[0],
    )
    const start = screen.getByLabelText('월요일 2번째 구간 시작 시각')
    await userEvent.clear(start)
    await userEvent.type(start, '17:00')
    const end = screen.getByLabelText('월요일 2번째 구간 종료 시각')
    await userEvent.clear(end)
    await userEvent.type(end, '21:00')

    await userEvent.click(screen.getByRole('button', { name: '진료시간 저장' }))

    expect(
      screen.getByText(/월요일: 다른 진료 시간과 겹칩니다/),
    ).toBeInTheDocument()
    expect(mutate).not.toHaveBeenCalled()
  })

  /*
    휴무 체크는 그 요일 구간을 지우지만, 실수로 눌러 하루치 구간을 잃으면 안 된다.
    체크를 풀었을 때 09:00~18:00 기본값이 아니라 원래 두 구간이 그대로 돌아와야 한다.
  */
  it('휴무를 체크했다 풀면 원래 구간을 되살린다', async () => {
    operatingHoursQuery.data = WEEKDAY_HOURS
    renderPage()

    const closedToggle = screen.getAllByRole('checkbox')[2] // 수요일
    await userEvent.click(closedToggle)
    expect(
      screen.getByText('휴무일입니다. 휴무를 풀면 이전 구간이 복원됩니다.'),
    ).toBeInTheDocument()

    await userEvent.click(closedToggle)
    expect(screen.getByLabelText('수요일 1번째 구간 시작 시각')).toHaveValue(
      '09:00',
    )
    expect(screen.getByLabelText('수요일 1번째 구간 종료 시각')).toHaveValue(
      '13:00',
    )
    expect(screen.getByLabelText('수요일 2번째 구간 시작 시각')).toHaveValue(
      '14:00',
    )
    expect(screen.getByLabelText('수요일 2번째 구간 종료 시각')).toHaveValue(
      '18:00',
    )
  })

  // 새로고침·탭 닫기 경로. 앱 안의 경로 이동은 useBlocker가 막는다(같은 훅).
  it('저장하지 않은 편집이 있을 때만 이탈을 막는다', async () => {
    operatingHoursQuery.data = WEEKDAY_HOURS
    renderPage()

    const beforeEdit = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(beforeEdit)
    expect(beforeEdit.defaultPrevented).toBe(false)

    await userEvent.click(
      screen.getAllByRole('button', { name: '구간 추가' })[0],
    )

    const afterEdit = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(afterEdit)
    expect(afterEdit.defaultPrevented).toBe(true)
  })

  it('진료시간이 없는 병원(HOSPITAL_004)은 빈 폼으로 최초 등록할 수 있다', () => {
    operatingHoursQuery.isError = true
    operatingHoursQuery.error = new ApiError(
      'HOSPITAL_004',
      '병원 진료시간을 불러오는 중 오류가 발생했습니다.',
      500,
    )
    renderPage()

    expect(
      screen.getByText('아직 등록된 진료시간이 없습니다.'),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: '진료시간 저장' }),
    ).toBeInTheDocument()
    // 7요일이 전부 휴무인 빈 주로 열린다.
    expect(screen.getAllByText('휴무일입니다.')).toHaveLength(7)
  })

  it('그 밖의 조회 오류는 폼을 열지 않는다', () => {
    operatingHoursQuery.isError = true
    operatingHoursQuery.error = new ApiError(
      'HOSPITAL_003',
      '해당 병원에 접근할 권한이 없습니다.',
      403,
    )
    renderPage()

    expect(
      screen.getByText('해당 병원에 접근할 권한이 없습니다.'),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '진료시간 저장' }),
    ).not.toBeInTheDocument()
  })
})
