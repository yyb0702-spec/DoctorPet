// 알림 벨의 리소스 이동 경로가 역할별로 갈리는지 검증한다 — 같은 컴포넌트를 재사용하되
// 보호자는 /reservations/{id}, 병원 스태프는 /staff/reservations/{id}로 간다(벨 복제 없음).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { NotificationBell } from './NotificationBell'
import { notificationApi } from './api'
import { subscribeToNotifications } from './notificationSse'
import type { Notification } from './types'
import { useAuthStore } from '@/lib/auth/authStore'

// SSE는 이 테스트의 관심사가 아니다(연결 계약은 StaffLayout.test.tsx가 확인한다).
vi.mock('./notificationSse', () => ({
  subscribeToNotifications: vi.fn(() => () => {}),
}))
vi.mock('./api', () => ({
  notificationApi: {
    list: vi.fn(),
    markRead: vi.fn(),
    issueTicket: vi.fn(),
  },
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

function renderBell(props?: { reservationBasePath?: string }) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/']}>
        <NotificationBell {...props} />
        <CurrentPath />
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
    vi.mocked(subscribeToNotifications).mockClear()
    useAuthStore.setState({ isAuthenticated: true })
  })

  it('보호자 알림의 예약 리소스를 누르면 /reservations/{id}로 이동한다', async () => {
    renderBell()

    await openBellAndClickNotification()

    expect(screen.getByTestId('path')).toHaveTextContent('/reservations/42')
  })

  it('병원 스태프 알림의 예약 리소스를 누르면 /staff/reservations/{id}로 이동한다', async () => {
    renderBell({ reservationBasePath: '/staff/reservations' })

    await openBellAndClickNotification()

    expect(screen.getByTestId('path')).toHaveTextContent('/staff/reservations/42')
  })

  it('미읽음 배지를 노출하고, 알림을 누르면 읽음 처리를 요청한다(기존 동작 유지)', async () => {
    renderBell()

    expect(await screen.findByText('1')).toBeInTheDocument()

    await openBellAndClickNotification()

    expect(notificationApi.markRead).toHaveBeenCalledWith(1)
  })
})
