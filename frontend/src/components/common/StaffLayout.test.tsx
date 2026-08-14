// 병원 스태프 레이아웃이 보호자와 같은 알림 벨·SSE 훅을 그대로 쓰는지 검증한다
// (벨 노출 + 구독 1회 — 레이아웃에서 중복 연결이 생기지 않아야 한다).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { StaffLayout } from './StaffLayout'
import { notificationApi } from '@/features/notifications/api'
import { subscribeToNotifications } from '@/features/notifications/notificationSse'
import { useAuthStore } from '@/lib/auth/authStore'

vi.mock('@/features/notifications/notificationSse', () => ({
  subscribeToNotifications: vi.fn(() => () => {}),
}))
vi.mock('@/features/notifications/api', () => ({
  notificationApi: {
    list: vi.fn(),
    markRead: vi.fn(),
    issueTicket: vi.fn(),
  },
}))
// 레이아웃 헤더가 쓰는 회원·병원·로그아웃 훅은 이 테스트의 관심사가 아니라 고정값으로 둔다.
// role은 벨이 리소스 이동 경로를 고를 때 쓰므로 스태프로 채운다(경로 자체 검증은 NotificationBell.test.tsx).
vi.mock('@/features/members/hooks', () => ({
  useMe: () => ({
    data: {
      nickname: '스태프',
      email: 'staff@example.com',
      hospitalId: 7,
      role: 'HOSPITAL_STAFF',
    },
  }),
}))
vi.mock('@/features/hospitals/hooks', () => ({
  useHospitalDetail: () => ({ data: { name: '행복동물병원' } }),
}))
vi.mock('@/features/auth/hooks', () => ({
  useLogout: () => ({ mutate: vi.fn(), isPending: false }),
}))

function renderStaffLayout() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/staff']}>
        <Routes>
          <Route element={<StaffLayout />}>
            <Route path="/staff" element={<div>대시보드</div>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('StaffLayout', () => {
  beforeEach(() => {
    vi.mocked(notificationApi.list).mockResolvedValue({
      content: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
      first: true,
      last: true,
    })
    vi.mocked(subscribeToNotifications).mockClear()
    useAuthStore.setState({ isAuthenticated: true })
  })

  it('헤더에 알림 벨을 노출한다', () => {
    renderStaffLayout()

    expect(screen.getByRole('button', { name: '알림' })).toBeInTheDocument()
  })

  it('기존 SSE 구독 훅을 그대로 쓰고 중복 연결을 만들지 않는다', async () => {
    renderStaffLayout()

    await waitFor(() => expect(subscribeToNotifications).toHaveBeenCalledTimes(1))
  })
})
