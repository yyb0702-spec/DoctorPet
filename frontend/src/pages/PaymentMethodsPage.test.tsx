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
  beforeEach(() => {
    vi.clearAllMocks()
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
    vi.unstubAllEnvs()
  })

  it('모바일 인증 복귀 URL의 빌링키를 한 번 등록하고 주소창 query를 제거한다', async () => {
    renderPage('/payment-methods?billingKey=billing-key-from-kakaopay')

    await waitFor(() =>
      expect(registerBillingKey).toHaveBeenCalledWith('billing-key-from-kakaopay'),
    )
    expect(screen.getByTestId('current-search')).toHaveTextContent('')
  })

  it('모바일 인증 실패 사유를 표시하고 URL query는 남기지 않는다', async () => {
    renderPage('/payment-methods?code=USER_CANCELLED&message=인증을 취소했습니다.')

    await waitFor(() =>
      expect(screen.getByText('인증을 취소했습니다.')).toBeInTheDocument(),
    )
    expect(registerBillingKey).not.toHaveBeenCalled()
    expect(screen.getByTestId('current-search')).toHaveTextContent('')
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
          redirectUrl: `${window.location.origin}/payment-methods`,
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
