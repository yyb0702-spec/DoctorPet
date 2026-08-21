import { StrictMode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as PortOne from '@portone/browser-sdk/v2'
import { useMe } from '@/features/members/hooks'
import {
  usePaymentMethods,
  useRegisterPaymentMethod,
  useRemovePaymentMethod,
  useSetDefaultPaymentMethod,
} from '@/features/payments/hooks'
import { PaymentMethodsPage } from './PaymentMethodsPage'

vi.mock('@portone/browser-sdk/v2', () => ({
  requestIssueBillingKey: vi.fn(),
}))

vi.mock('@/features/members/hooks', () => ({ useMe: vi.fn() }))

vi.mock('@/features/payments/hooks', () => ({
  usePaymentMethods: vi.fn(),
  useRegisterPaymentMethod: vi.fn(),
  useRemovePaymentMethod: vi.fn(),
  useSetDefaultPaymentMethod: vi.fn(),
}))

const registerBillingKey = vi.fn()
const PENDING_ISSUE_STORAGE_KEY = 'doctorpet:pending-billing-key-issue-id'

function storePendingIssue(issueId: string, memberId = 31) {
  window.sessionStorage.setItem(
    PENDING_ISSUE_STORAGE_KEY,
    JSON.stringify({ issueId, memberId }),
  )
}

function CurrentSearch() {
  return <output data-testid="current-search">{useLocation().search}</output>
}

function renderPage(initialEntry = '/payment-methods', strictMode = false) {
  const content = (
    <>
      <PaymentMethodsPage />
      <CurrentSearch />
    </>
  )

  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      {strictMode ? <StrictMode>{content}</StrictMode> : content}
    </MemoryRouter>,
  )
}

describe('PaymentMethodsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.sessionStorage.clear()
    vi.stubEnv('VITE_PORTONE_STORE_ID', 'store-test')
    vi.stubEnv('VITE_PORTONE_CHANNEL_KEY', 'channel-key-test')

    vi.mocked(useMe).mockReturnValue({
      data: {
        memberId: 31,
        email: 'guardian@example.com',
        nickname: '보호자',
        role: 'GUARDIAN',
        hospitalId: null,
      },
      isError: false,
    } as ReturnType<typeof useMe>)
    vi.mocked(usePaymentMethods).mockReturnValue({
      data: [],
      isLoading: false,
      isError: false,
    } as unknown as ReturnType<typeof usePaymentMethods>)
    vi.mocked(useRegisterPaymentMethod).mockReturnValue({
      mutate: registerBillingKey,
      isPending: false,
      isError: false,
    } as unknown as ReturnType<typeof useRegisterPaymentMethod>)
    vi.mocked(useRemovePaymentMethod).mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
    } as unknown as ReturnType<typeof useRemovePaymentMethod>)
    vi.mocked(useSetDefaultPaymentMethod).mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
      isError: false,
    } as unknown as ReturnType<typeof useSetDefaultPaymentMethod>)
  })

  afterEach(() => {
    window.sessionStorage.clear()
    vi.unstubAllEnvs()
  })

  it('StrictMode에서도 일치하는 모바일 인증 복귀 URL의 빌링키를 한 번만 등록하고 query를 제거한다', async () => {
    storePendingIssue('issue-from-kakaopay')
    renderPage(
      '/payment-methods?billingKey=billing-key-from-kakaopay&billingKeyIssueId=issue-from-kakaopay',
      true,
    )

    await waitFor(() => expect(registerBillingKey).toHaveBeenCalledTimes(1))
    expect(registerBillingKey).toHaveBeenCalledWith('billing-key-from-kakaopay')
    expect(screen.getByTestId('current-search')).toHaveTextContent('')
  })

  it('현재 탭에서 시작하지 않은 인증 결과는 등록하지 않는다', async () => {
    storePendingIssue('expected-issue')
    renderPage(
      '/payment-methods?billingKey=untrusted-key&billingKeyIssueId=other-issue',
    )

    await waitFor(() =>
      expect(screen.getByText('확인되지 않은 결제수단 인증 결과입니다. 다시 등록해 주세요.')).toBeInTheDocument(),
    )
    expect(registerBillingKey).not.toHaveBeenCalled()
    expect(screen.getByTestId('current-search')).toHaveTextContent('')
  })

  it('모바일 인증 실패 후 issueId를 소비해 같은 ID의 성공 callback을 등록하지 않는다', async () => {
    const issueId = 'cancelled-issue'
    storePendingIssue(issueId)
    const failedCallback = renderPage(
      `/payment-methods?code=USER_CANCELLED&message=인증을 취소했습니다.&billingKeyIssueId=${issueId}`,
    )

    await waitFor(() =>
      expect(screen.getByText('인증을 취소했습니다.')).toBeInTheDocument(),
    )
    expect(registerBillingKey).not.toHaveBeenCalled()
    expect(screen.getByTestId('current-search')).toHaveTextContent('')
    expect(window.sessionStorage.getItem(PENDING_ISSUE_STORAGE_KEY)).toBeNull()

    failedCallback.unmount()
    renderPage(`/payment-methods?billingKey=late-key&billingKeyIssueId=${issueId}`)

    await waitFor(() =>
      expect(screen.getByText('확인되지 않은 결제수단 인증 결과입니다. 다시 등록해 주세요.')).toBeInTheDocument(),
    )
    expect(registerBillingKey).not.toHaveBeenCalled()
  })

  it('현재 발급 요청과 다른 실패 callback은 진행 중인 issueId를 지우지 않는다', async () => {
    storePendingIssue('active-issue')
    renderPage(
      '/payment-methods?code=USER_CANCELLED&message=인증을 취소했습니다.&billingKeyIssueId=other-issue',
    )

    await waitFor(() =>
      expect(screen.getByText('인증을 취소했습니다.')).toBeInTheDocument(),
    )
    expect(window.sessionStorage.getItem(PENDING_ISSUE_STORAGE_KEY)).toBe(
      JSON.stringify({ issueId: 'active-issue', memberId: 31 }),
    )
  })

  it('인증 중 계정이 바뀐 callback은 등록하지 않고 발급 상태를 폐기한다', async () => {
    storePendingIssue('guardian-a-issue', 31)
    vi.mocked(useMe).mockReturnValue({
      data: {
        memberId: 42,
        email: 'other-guardian@example.com',
        nickname: '다른 보호자',
        role: 'GUARDIAN',
        hospitalId: null,
      },
      isError: false,
      isLoading: false,
    } as ReturnType<typeof useMe>)
    renderPage(
      '/payment-methods?billingKey=guardian-a-key&billingKeyIssueId=guardian-a-issue',
    )

    await waitFor(() =>
      expect(screen.getByText('인증을 시작한 계정과 현재 로그인 계정이 다릅니다. 다시 등록해 주세요.')).toBeInTheDocument(),
    )
    expect(registerBillingKey).not.toHaveBeenCalled()
    expect(window.sessionStorage.getItem(PENDING_ISSUE_STORAGE_KEY)).toBeNull()
  })

  it('실제 보호자 식별자와 모바일 복귀 URL을 넣어 카카오페이 빌링키 발급을 요청한다', async () => {
    vi.mocked(PortOne.requestIssueBillingKey).mockResolvedValue({
      transactionType: 'ISSUE_BILLING_KEY',
      billingKey: 'billing-key-from-iframe',
    })
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: '카카오페이로 등록' }))

    await waitFor(() =>
      expect(PortOne.requestIssueBillingKey).toHaveBeenCalledWith(
        expect.objectContaining({
          storeId: 'store-test',
          channelKey: 'channel-key-test',
          billingKeyMethod: 'EASY_PAY',
          issueId: expect.stringMatching(/^dp-bk-/),
          redirectUrl: expect.stringMatching(
            new RegExp(`^${window.location.origin}/payment-methods\\?billingKeyIssueId=dp-bk-`),
          ),
          customer: {
            customerId: 'doctorpet-member-31',
            email: 'guardian@example.com',
          },
        }),
      ),
    )
    expect(registerBillingKey).toHaveBeenCalledWith('billing-key-from-iframe')
  })
})
