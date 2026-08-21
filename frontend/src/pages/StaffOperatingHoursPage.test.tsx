// 진료시간 화면의 두 계약을 고정한다(PR #198 자체 리뷰).
//  1) 클라이언트 검증에 걸린 입력은 서버로 보내지 않는다 — 겹침을 서버까지 보내면 안 된다.
//  2) 유효 스케줄이 없는 병원(HOSPITAL_004)은 폼을 열어 최초 등록이 가능해야 한다.
//     GET 실패를 그대로 에러 화면으로 처리하면 PUT으로 만들 수 있는데도 등록 경로가 사라진다.
//  3) 휴무 토글은 구간을 파기하지 않고 되살린다.
//  4) 저장하지 않은 편집이 있으면 새로고침·탭 닫기를 막는다.
//  5) 서버로 보내는 payload에는 화면 전용 id가 섞이지 않는다.
//  6) 자정을 넘겨도 건드리지 않은 폼이 미저장 상태로 바뀌지 않는다.
import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { RouterProvider, createMemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { StaffOperatingHoursPage } from './StaffOperatingHoursPage'
import { ApiError } from '@/lib/api/error'
import type { OperatingHours } from '@/features/hospitalOps/types'

// 오늘(Asia/Seoul)을 고정해 실행 시각에 흔들리지 않게 하고, 자정 경계도 직접 만든다.
let fakeToday = '2026-08-21'
vi.mock('@/lib/seoulTime', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/seoulTime')>()
  return { ...actual, todaySeoulKey: () => fakeToday }
})

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

const FUTURE_WEEKDAY_HOURS: OperatingHours = {
  effectiveFrom: '2026-08-25',
  days: [
    {
      dayOfWeek: 'MONDAY',
      periods: [{ startTime: '10:00', endTime: '19:00' }],
    },
    { dayOfWeek: 'TUESDAY', periods: [] },
    { dayOfWeek: 'WEDNESDAY', periods: [] },
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
  fakeToday = '2026-08-21'
  mutate.mockReset()
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

  it('발효일만 바꿔도 저장 전 이탈을 막는다', () => {
    operatingHoursQuery.data = WEEKDAY_HOURS
    renderPage()

    fireEvent.change(screen.getByLabelText('발효일'), {
      target: { value: '2026-12-31' },
    })

    const beforeUnload = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(beforeUnload)
    expect(beforeUnload.defaultPrevented).toBe(true)
  })

  /*
    GET은 오늘 유효한 시간표만 주므로, PUT 성공 뒤 쿼리를 무효화하면 기존 시간표가 다시 온다.
    이때 PUT 응답(적용 예정 시간표)을 따로 붙잡지 않으면 방금 저장한 내용을 보거나 같은 발효일로
    재편집할 수 없고, 현재 시간표를 다시 저장해 미래 시간표를 덮어쓸 수 있다.
  */
  it('저장한 미래 시간표를 현재 시간표와 구분해 같은 발효일로 재편집한다', async () => {
    operatingHoursQuery.data = WEEKDAY_HOURS
    mutate.mockImplementation((_request, options) =>
      options?.onSuccess?.(FUTURE_WEEKDAY_HOURS),
    )
    renderPage()

    await userEvent.click(screen.getByRole('button', { name: '진료시간 저장' }))

    expect(
      screen.getByText(/8월 25일부터 적용 예정인 시간표를 편집 중입니다/),
    ).toBeInTheDocument()
    expect(screen.getByLabelText('월요일 1번째 구간 시작 시각')).toHaveValue(
      '10:00',
    )
    expect(screen.getByLabelText('발효일')).toHaveValue('2026-08-25')

    // PUT 응답을 폼의 새 기준값으로 다시 마운트했으므로, 저장 직후에는 이탈 경고가 남지 않아야 한다.
    const afterSave = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(afterSave)
    expect(afterSave.defaultPrevented).toBe(false)

    fireEvent.change(screen.getByLabelText('월요일 1번째 구간 시작 시각'), {
      target: { value: '11:00' },
    })
    await userEvent.click(screen.getByRole('button', { name: '진료시간 저장' }))

    expect(mutate).toHaveBeenLastCalledWith(
      {
        desiredEffectiveFrom: '2026-08-25',
        days: [
          {
            dayOfWeek: 'MONDAY',
            periods: [{ startTime: '11:00', endTime: '19:00' }],
          },
          { dayOfWeek: 'TUESDAY', periods: [] },
          { dayOfWeek: 'WEDNESDAY', periods: [] },
          { dayOfWeek: 'THURSDAY', periods: [] },
          { dayOfWeek: 'FRIDAY', periods: [] },
          { dayOfWeek: 'SATURDAY', periods: [] },
          { dayOfWeek: 'SUNDAY', periods: [] },
        ],
      },
      expect.any(Object),
    )
  })

  /*
    구간은 화면 안에서만 쓰는 id를 갖는다(중간 삭제 시 포커스가 튀지 않게). 그 id가 요청에
    섞이면 요청 DTO에 없는 필드를 보내게 되므로, 저장 payload는 startTime·endTime만 담아야 한다.
    중간 구간을 지운 뒤에도 남은 구간의 값이 그대로인지 함께 본다.
  */
  it('중간 구간을 지워도 남은 값이 유지되고 payload에 화면용 id가 섞이지 않는다', async () => {
    operatingHoursQuery.data = WEEKDAY_HOURS
    renderPage()

    // 수요일 09:00~13:00 / 14:00~18:00 중 첫 구간을 지운다.
    await userEvent.click(
      screen.getByRole('button', { name: '수요일 1번째 구간 삭제' }),
    )
    expect(screen.getByLabelText('수요일 1번째 구간 시작 시각')).toHaveValue(
      '14:00',
    )
    expect(
      screen.queryByLabelText('수요일 2번째 구간 시작 시각'),
    ).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: '진료시간 저장' }))

    expect(mutate).toHaveBeenCalledTimes(1)
    const { days } = mutate.mock.calls[0][0]
    expect(days[2]).toEqual({
      dayOfWeek: 'WEDNESDAY',
      periods: [{ startTime: '14:00', endTime: '18:00' }],
    })
  })

  /*
    발효일 기본값("내일")을 미저장 판정의 기준으로 매 렌더 다시 계산하면, 페이지를 열어둔 채
    자정을 넘길 때 기준값만 하루 밀려 아무것도 건드리지 않은 폼이 미저장 상태가 된다.
    기준값은 마운트 시점에 고정돼야 한다.
  */
  it('자정을 넘겨도 건드리지 않은 폼은 이탈을 막지 않는다', async () => {
    operatingHoursQuery.data = WEEKDAY_HOURS
    renderPage()

    fakeToday = '2026-08-22' // 자정 경과

    // 구간을 추가했다 지워 내용은 그대로 두면서 자정 이후 상태로 다시 렌더한다.
    await userEvent.click(
      screen.getAllByRole('button', { name: '구간 추가' })[0],
    )
    await userEvent.click(
      screen.getByRole('button', { name: '월요일 2번째 구간 삭제' }),
    )

    const beforeUnload = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(beforeUnload)
    expect(beforeUnload.defaultPrevented).toBe(false)
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
