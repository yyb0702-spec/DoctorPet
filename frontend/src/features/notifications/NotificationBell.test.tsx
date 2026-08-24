// 알림 벨의 리소스 이동이 역할별로 갈리고, 도착한 화면이 실제로 열리는지 검증한다 — 같은 컴포넌트를
// 재사용하되 보호자는 /reservations/{id} 상세로, 병원 스태프는 /staff/reservations 목록으로 간다(벨 복제 없음).
// 경로 문자열만 보지 않고 라우트를 실제로 걸어 렌더 결과까지 확인한다 — 스태프 예약 상세는 목록에서 넘겨준
// location.state 없이는 열리지 않아서(병원 예약 단건 조회 API 부재) 상세 URL로 보내면 죽은 링크가 된다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { NotificationBell } from './NotificationBell'
import { notificationApi } from './api'
import { subscribeToNotifications } from './notificationSse'
import type { Notification } from './types'
import { useAuthStore } from '@/lib/auth/authStore'
import { MemberRole } from '@/types/enums'

// SSE는 이 테스트의 관심사가 아니다(연결 계약은 StaffLayout.test.tsx가 확인한다).
vi.mock('./notificationSse', () => ({
  subscribeToNotifications: vi.fn(() => () => {}),
}))
vi.mock('./api', () => ({
  notificationApi: {
    list: vi.fn(),
    markRead: vi.fn(),
    getUnreadCount: vi.fn(),
    markAllRead: vi.fn(),
    deleteAll: vi.fn(),
    issueTicket: vi.fn(),
  },
}))

// 벨이 role로 이동 경로를 고르므로 회원 조회는 역할만 바꿔 끼운다.
const useMeMock = vi.fn()
vi.mock('@/features/members/hooks', () => ({
  useMe: () => useMeMock(),
}))

const RESERVATION_NOTIFICATION: Notification = {
  id: 1,
  type: 'RESERVATION_CONFIRMED',
  content: '예약이 승인되었습니다.',
  resourceType: 'RESERVATION',
  resourceId: 42,
  isRead: false,
  readAt: null,
  createdAt: '2026-08-14T10:00:00',
}

function CurrentPath() {
  return <span data-testid="path">{useLocation().pathname}</span>
}

/*
  스태프 예약 상세의 실제 제약을 그대로 재현한다 — location.state.item 없이는 화면을 열 수 없어
  StaffReservationDetailPage가 목록으로 리다이렉트한다. 벨이 상세 URL로 보내면 이 화면이 렌더되어
  단언이 "열 수 없음"으로 실패하므로, 죽은 링크가 다시 들어오면 테스트가 잡는다.
*/
function StaffReservationDetailStub() {
  const item = (useLocation().state as { item?: unknown } | null)?.item
  return (
    <span data-testid="screen">
      {item ? '스태프 예약 상세' : '스태프 예약 상세를 열 수 없음(목록 state 없음)'}
    </span>
  )
}

function renderBell() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/']}>
        <NotificationBell />
        <CurrentPath />
        <Routes>
          <Route path="/" element={<span data-testid="screen">홈</span>} />
          <Route
            path="/reservations"
            element={<span data-testid="screen">보호자 예약 목록</span>}
          />
          <Route
            path="/reservations/:reservationId"
            element={<span data-testid="screen">보호자 예약 상세</span>}
          />
          <Route
            path="/staff/reservations"
            element={<span data-testid="screen">스태프 예약 목록</span>}
          />
          <Route
            path="/staff/reservations/:reservationId"
            element={<StaffReservationDetailStub />}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

async function openBellAndClickNotification() {
  const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: '알림' }))
  await user.click(
    await screen.findByRole('button', { name: /예약이 승인되었습니다/ }),
  )
}

describe('NotificationBell', () => {
  beforeEach(() => {
    vi.mocked(notificationApi.list).mockResolvedValue({
      content: [RESERVATION_NOTIFICATION],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      first: true,
      last: true,
    })
    vi.mocked(notificationApi.markRead).mockResolvedValue(undefined)
    vi.mocked(notificationApi.getUnreadCount).mockResolvedValue({ unreadCount: 1 })
    vi.mocked(notificationApi.markAllRead).mockResolvedValue({ updatedCount: 1 })
    vi.mocked(notificationApi.deleteAll).mockResolvedValue({ deletedCount: 1 })
    vi.mocked(notificationApi.deleteAll).mockClear()
    vi.mocked(subscribeToNotifications).mockClear()
    // 호출 이력은 테스트마다 초기화한다 — 누적되면 "호출되지 않아야 한다" 단언이 앞 테스트 때문에 깨진다.
    vi.mocked(notificationApi.markRead).mockClear()
    vi.mocked(notificationApi.markAllRead).mockClear()
    useMeMock.mockReturnValue({ data: { role: MemberRole.GUARDIAN } })
    useAuthStore.setState({ isAuthenticated: true })
  })

  it('보호자 알림의 예약 리소스를 누르면 /reservations/{id} 상세가 열린다', async () => {
    renderBell()

    await openBellAndClickNotification()

    expect(screen.getByTestId('path')).toHaveTextContent('/reservations/42')
    expect(screen.getByTestId('screen')).toHaveTextContent('보호자 예약 상세')
  })

  it('"전체 삭제"는 두 단계 확인을 거쳐 deleteAll을 한 번만 호출한다', async () => {
    const user = userEvent.setup()
    renderBell()
    await user.click(screen.getByRole('button', { name: '알림' }))

    // 첫 클릭은 확인만 노출하고 아직 삭제하지 않는다(하드 삭제라 실수 방지).
    await user.click(await screen.findByRole('button', { name: '전체 삭제' }))
    expect(screen.getByText('삭제할까요?')).toBeInTheDocument()
    expect(notificationApi.deleteAll).not.toHaveBeenCalled()

    // 둘째 클릭이 실제 삭제를 부른다.
    await user.click(screen.getByRole('button', { name: '삭제' }))
    await waitFor(() =>
      expect(notificationApi.deleteAll).toHaveBeenCalledTimes(1),
    )
  })

  it('병원 스태프 알림의 예약 리소스를 누르면 실제로 열리는 /staff/reservations 목록으로 간다', async () => {
    useMeMock.mockReturnValue({ data: { role: MemberRole.HOSPITAL_STAFF } })
    renderBell()

    await openBellAndClickNotification()

    expect(screen.getByTestId('path')).toHaveTextContent('/staff/reservations')
    // 상세 URL로 보내면 이 단언이 "열 수 없음"으로 실패한다(죽은 링크 회귀 방지).
    expect(screen.getByTestId('screen')).toHaveTextContent('스태프 예약 목록')
  })

  it('레이아웃이 경로를 주입하지 않아도 스태프가 보호자 예약 경로로 떨어지지 않는다', async () => {
    useMeMock.mockReturnValue({ data: { role: MemberRole.HOSPITAL_STAFF } })
    renderBell()

    await openBellAndClickNotification()

    expect(screen.getByTestId('path')).not.toHaveTextContent('/reservations/42')
  })

  /*
    role이 채워지기 전에는 보호자를 기본값으로 삼지 않는다. 기본값을 두면 내 정보 조회가 아직 끝나지 않은
    창(재시도 중이면 짧지도 않다)에 스태프가 알림을 눌렀을 때 보호자 전용 경로로 새는데, AppLayout은
    role 게이트 없이 인증만으로 벨을 렌더하므로 실제로 도달 가능한 경합이다(리뷰 지적 P2).
  */
  it('역할을 모르는 동안에는 이동하지 않고, 역할이 확인된 뒤 그 역할의 경로로 간다', async () => {
    useMeMock.mockReturnValue({ data: undefined })
    renderBell()
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: '알림' }))
    await user.click(
      await screen.findByRole('button', { name: /예약이 승인되었습니다/ }),
    )

    // 보호자 경로(/reservations/42)로 떨어지지 않고 제자리에 머문다.
    expect(screen.getByTestId('path').textContent).toBe('/')
    // 읽음 처리는 역할과 무관하므로 보류하지 않는다.
    expect(notificationApi.markRead).toHaveBeenCalledWith(1)

    // 조회가 끝나 스태프로 확인되면 그때부터 스태프 경로로 이동한다(닫았다 다시 열어 재렌더).
    useMeMock.mockReturnValue({ data: { role: MemberRole.HOSPITAL_STAFF } })
    await user.click(screen.getByRole('button', { name: '알림' }))
    await user.click(screen.getByRole('button', { name: '알림' }))
    await user.click(
      await screen.findByRole('button', { name: /예약이 승인되었습니다/ }),
    )

    expect(screen.getByTestId('path').textContent).toBe('/staff/reservations')
  })

  it('미읽음 배지를 노출하고, 알림을 누르면 읽음 처리를 요청한다(기존 동작 유지)', async () => {
    renderBell()

    expect(await screen.findByText('1')).toBeInTheDocument()

    await openBellAndClickNotification()

    expect(notificationApi.markRead).toHaveBeenCalledWith(1)
  })

  /*
    배지는 드롭다운이 받은 목록(최신 20건)이 아니라 서버 집계를 쓴다. 목록에서 세면 미읽음이 21건을
    넘는 순간 조용히 20으로 상한이 걸려 과소집계되는데, 화면상 티가 나지 않아 회귀를 놓치기 쉽다.
  */
  it('배지 숫자는 목록 길이가 아니라 unread-count 응답을 쓴다', async () => {
    vi.mocked(notificationApi.getUnreadCount).mockResolvedValue({
      unreadCount: 37,
    })
    renderBell()

    // 목록 mock은 1건뿐인데도 서버 집계값이 그대로 보인다.
    expect(await screen.findByText('37')).toBeInTheDocument()
  })

  it('"모두 읽음"을 누르면 read-all 한 번으로 처리하고 배지가 사라진다', async () => {
    vi.mocked(notificationApi.getUnreadCount).mockResolvedValue({
      unreadCount: 3,
    })
    renderBell()
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: '알림' }))
    expect(await screen.findByText('3')).toBeInTheDocument()

    // 처리 후에는 개수가 0으로 내려온다(서버 bulk UPDATE 결과를 재조회).
    vi.mocked(notificationApi.getUnreadCount).mockResolvedValue({
      unreadCount: 0,
    })
    await user.click(screen.getByRole('button', { name: '모두 읽음' }))

    expect(notificationApi.markAllRead).toHaveBeenCalledTimes(1)
    // 보이는 항목을 순회하지 않는다 — 개별 읽음 API는 호출되지 않아야 한다.
    expect(notificationApi.markRead).not.toHaveBeenCalled()
    await waitFor(() => expect(screen.queryByText('3')).not.toBeInTheDocument())
  })

  it('미읽음이 없으면 "모두 읽음" 버튼을 노출하지 않는다', async () => {
    vi.mocked(notificationApi.getUnreadCount).mockResolvedValue({
      unreadCount: 0,
    })
    renderBell()
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: '알림' }))

    expect(
      await screen.findByRole('button', { name: /예약이 승인되었습니다/ }),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '모두 읽음' }),
    ).not.toBeInTheDocument()
  })
})
