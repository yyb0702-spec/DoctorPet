import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as PortOne from '@portone/browser-sdk/v2'
import { useMe } from '@/features/members/hooks'
import {
  useCompleteBillingKeyIssue,
  useIssueBillingKey,
  usePaymentMethods,
  useRemovePaymentMethod,
  useSetDefaultPaymentMethod,
} from '@/features/payments/hooks'
import { PaymentMethodsPage } from './PaymentMethodsPage'

vi.mock('@portone/browser-sdk/v2', () => ({ requestIssueBillingKey: vi.fn() }))
vi.mock('@/features/members/hooks', () => ({ useMe: vi.fn() }))
vi.mock('@/features/payments/hooks', () => ({
  usePaymentMethods: vi.fn(),
  useIssueBillingKey: vi.fn(),
  useCompleteBillingKeyIssue: vi.fn(),
  useRemovePaymentMethod: vi.fn(),
  useSetDefaultPaymentMethod: vi.fn(),
}))

function CurrentSearch() {
  return <output data-testid="current-search">{useLocation().search}</output>
}

function renderPage(initialEntry = '/payment-methods') {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <PaymentMethodsPage />
      <CurrentSearch />
    </MemoryRouter>,
  )
}

describe('PaymentMethodsPage', () => {
  const issue = vi.fn()
  const complete = vi.fn()
  const refetch = vi.fn()

  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubEnv('VITE_PORTONE_STORE_ID', 'store-test')
    vi.stubEnv('VITE_PORTONE_CHANNEL_KEY', 'channel-key-test')
    vi.mocked(useMe).mockReturnValue({
      data: { memberId: 31, email: 'guardian@example.com' },
      isError: false,
    } as ReturnType<typeof useMe>)
    vi.mocked(usePaymentMethods).mockReturnValue({
      data: [], isLoading: false, isError: false, refetch,
    } as unknown as ReturnType<typeof usePaymentMethods>)
    vi.mocked(useIssueBillingKey).mockReturnValue({
      mutateAsync: issue, isPending: false, isError: false,
    } as unknown as ReturnType<typeof useIssueBillingKey>)
    vi.mocked(useCompleteBillingKeyIssue).mockReturnValue({
      mutateAsync: complete, isPending: false, isError: false,
    } as unknown as ReturnType<typeof useCompleteBillingKeyIssue>)
    vi.mocked(useRemovePaymentMethod).mockReturnValue({ mutate: vi.fn(), isPending: false } as unknown as ReturnType<typeof useRemovePaymentMethod>)
    vi.mocked(useSetDefaultPaymentMethod).mockReturnValue({ mutate: vi.fn(), isPending: false, isError: false } as unknown as ReturnType<typeof useSetDefaultPaymentMethod>)
  })

  afterEach(() => vi.unstubAllEnvs())

  it('서버가 발급한 issueId와 콜백 주소로 PortOne 발급을 시작하고 iframe 결과는 전용 완료 API로 보낸다', async () => {
    issue.mockResolvedValue({ issueId: 'server-issued-id', redirectUrl: 'https://api.example/callback' })
    vi.mocked(PortOne.requestIssueBillingKey).mockResolvedValue({
      transactionType: 'ISSUE_BILLING_KEY', billingKey: 'billing-key-from-iframe',
    })
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: '카카오페이로 등록' }))

    await waitFor(() => expect(PortOne.requestIssueBillingKey).toHaveBeenCalledWith(expect.objectContaining({
      issueId: 'server-issued-id', redirectUrl: 'https://api.example/callback',
      customer: { customerId: 'doctorpet-member-31', email: 'guardian@example.com' },
    })))
    expect(complete).toHaveBeenCalledWith({ issueId: 'server-issued-id', billingKey: 'billing-key-from-iframe' })
  })

  it('모바일 콜백 결과에는 원문이 없고 성공 상태만 읽은 뒤 URL을 제거한다', async () => {
    renderPage('/payment-methods?billingKeyResult=success')

    await waitFor(() => expect(screen.getByText('결제수단을 등록했습니다.')).toBeInTheDocument())
    expect(refetch).toHaveBeenCalledTimes(1)
    expect(screen.getByTestId('current-search')).toHaveTextContent('')
    expect(screen.queryByText(/billing-key/i)).not.toBeInTheDocument()
  })
})
