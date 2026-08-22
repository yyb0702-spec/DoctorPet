// 결제수단 관리 — PortOne SDK 발급은 서버의 단기·1회성 issueId에 귀속한다.
import { useEffect, useState } from 'react'
import { Trash2, CreditCard } from 'lucide-react'
import * as PortOne from '@portone/browser-sdk/v2'
import type { EasyPayProvider } from '@portone/browser-sdk/v2'
import { useLocation, useNavigate } from 'react-router-dom'
import {
  useCompleteBillingKeyIssue,
  useIssueBillingKey,
  usePaymentMethods,
  useRemovePaymentMethod,
  useSetDefaultPaymentMethod,
} from '@/features/payments/hooks'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { EmptyState, ErrorState, PageLoader } from '@/components/common/States'
import { useMe } from '@/features/members/hooks'
import { paymentMethodPrimaryLabel } from '@/features/payments/methodLabel'
import { cn } from '@/lib/utils'
import { ApiError } from '@/lib/api/error'

// 등록 시각 부제 — 카드사·뒷자리가 비어 같은 이름으로 보이는 수단을 서로 구분할 수 있게 한다.
// 같은 날 여러 개를 등록할 수 있어 날짜만으로는 안 겹치므로 시각까지 적는다(선택 목록의 구분 규칙과 동일).
function registeredAtLabel(createdAt: string): string {
  return `${new Date(createdAt).toLocaleString('ko-KR')} 등록`
}

function getPortOneConfig() {
  return {
    storeId: import.meta.env.VITE_PORTONE_STORE_ID as string | undefined,
    channelKey: import.meta.env.VITE_PORTONE_CHANNEL_KEY as string | undefined,
  }
}

const EASY_PAY_METHODS: { provider: EasyPayProvider; label: string }[] = [
  { provider: 'KAKAOPAY', label: '카카오페이' },
]

function errorMessage(error: unknown, fallback: string) {
  return error instanceof ApiError ? error.message : fallback
}

function getCallbackMessage(state: unknown) {
  if (
    typeof state === 'object' &&
    state !== null &&
    'billingKeyCallbackMessage' in state &&
    typeof state.billingKeyCallbackMessage === 'string'
  ) {
    return state.billingKeyCallbackMessage
  }
  return null
}

export function PaymentMethodsPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const meQuery = useMe()
  const methodsQuery = usePaymentMethods()
  const refetchMethods = methodsQuery.refetch
  const issue = useIssueBillingKey()
  const complete = useCompleteBillingKeyIssue()
  const removeMethod = useRemovePaymentMethod()
  const setDefaultMethod = useSetDefaultPaymentMethod()
  const [issuingKey, setIssuingKey] = useState<string | null>(null)
  const [sdkError, setSdkError] = useState<string | null>(null)
  const [issueSuccessMessage, setIssueSuccessMessage] = useState<string | null>(null)
  const { storeId, channelKey } = getPortOneConfig()
  const portoneReady = Boolean(storeId && channelKey)
  const callbackResult = new URLSearchParams(location.search).get('billingKeyResult')
  const callbackMessage =
    getCallbackMessage(location.state) ??
    (callbackResult
      ? callbackResult === 'success'
        ? '결제수단을 등록했습니다.'
        : '결제수단 인증에 실패했습니다. 다시 등록해 주세요.'
      : null)

  // 서버 콜백은 빌링키가 아닌 성공·실패 상태만 프런트로 돌려준다.
  useEffect(() => {
    if (!callbackResult) return
    if (callbackResult === 'success') refetchMethods()
    navigate('/payment-methods', {
      replace: true,
      state: { billingKeyCallbackMessage: callbackMessage },
    })
  }, [callbackMessage, callbackResult, navigate, refetchMethods])

  // 기본 변경 성공 안내는 잠시 뒤 자동으로 걷어낸다. React Query mutation의 isSuccess는
  // 다음 mutate/reset 전까지 계속 true라, 그대로 두면 안내 문구가 화면에 영구 잔류한다.
  useEffect(() => {
    if (!setDefaultMethod.isSuccess) return
    const timer = setTimeout(() => setDefaultMethod.reset(), 3000)
    return () => clearTimeout(timer)
  }, [setDefaultMethod.isSuccess, setDefaultMethod.reset])

  const issueBillingKey = async (
    key: string,
    request: {
      billingKeyMethod: 'EASY_PAY'
      easyPay: { easyPayProvider: EasyPayProvider }
    },
  ) => {
    if (!portoneReady || !meQuery.data) return
    setSdkError(null)
    setIssueSuccessMessage(null)
    setIssuingKey(key)
    try {
      const issued = await issue.mutateAsync()
      const res = await PortOne.requestIssueBillingKey({
        storeId: storeId!,
        channelKey: channelKey!,
        ...request,
        issueId: issued.issueId,
        issueName: 'DoctorPet 결제수단',
        redirectUrl: issued.redirectUrl,
        customer: {
          customerId: `doctorpet-member-${meQuery.data.memberId}`,
          email: meQuery.data.email,
        },
      })
      // 모바일은 서버 콜백으로 이동하고, PC iframe 응답은 issue 전용 인증 body로만 보낸다.
      if (!res) return
      if ('code' in res && res.code) {
        setSdkError(res.message ?? '빌링키 발급에 실패했습니다.')
        return
      }
      if ('billingKey' in res && res.billingKey) {
        await complete.mutateAsync({
          issueId: issued.issueId,
          billingKey: res.billingKey,
        })
        setIssueSuccessMessage('결제수단을 등록했습니다.')
        return
      }
      setSdkError('응답에서 billingKey를 찾지 못했습니다.')
    } catch (error) {
      setSdkError(error instanceof Error ? error.message : '빌링키 발급 중 오류가 발생했습니다.')
    } finally {
      setIssuingKey(null)
    }
  }

  const sortedMethods = [...(methodsQuery.data ?? [])].sort(
    (a, b) => Number(b.isDefault) - Number(a.isDefault),
  )
  const setDefaultErrorMessage = setDefaultMethod.isError
    ? errorMessage(setDefaultMethod.error, '기본 결제수단 지정에 실패했습니다.')
    : null
  const registerErrorMessage =
    sdkError ??
    (issue.isError
      ? errorMessage(issue.error, '결제수단 인증 요청을 시작하지 못했습니다.')
      : complete.isError
        ? errorMessage(complete.error, '결제수단 등록에 실패했습니다.')
        : null)

  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <div className="space-y-3">
        <h1 className="text-2xl font-bold">결제수단</h1>
        {methodsQuery.isLoading && <PageLoader />}
        {methodsQuery.isError && <ErrorState onRetry={() => methodsQuery.refetch()} />}
        {methodsQuery.data && methodsQuery.data.length === 0 && (
          <EmptyState message="등록된 결제수단이 없습니다." />
        )}
        <div className="grid gap-3">
          {sortedMethods.map((method) => (
            <Card
              key={method.id}
              className={cn(method.isDefault && 'border-primary ring-1 ring-primary')}
            >
              <CardContent className="flex items-center justify-between p-5">
                <div className="flex items-center gap-3">
                  <CreditCard className="h-5 w-5 text-primary" />
                  <div className="space-y-0.5">
                    <div className="flex items-center gap-2">
                      <span className="font-medium">
                        {paymentMethodPrimaryLabel(method)}
                      </span>
                      {method.isDefault && <Badge>기본</Badge>}
                      {method.status !== 'ACTIVE' && (
                        <Badge variant="muted">{method.status}</Badge>
                      )}
                    </div>
                    <p className="text-xs text-muted-foreground">
                      {registeredAtLabel(method.createdAt)}
                    </p>
                  </div>
                </div>
                <div className="flex items-center gap-1">
                  {method.status === 'ACTIVE' && !method.isDefault && (
                    <Button
                      variant="ghost"
                      size="sm"
                      className="text-xs"
                      disabled={setDefaultMethod.isPending}
                      onClick={() => setDefaultMethod.mutate(method.id)}
                    >
                      기본으로 지정
                    </Button>
                  )}
                  <Button
                    variant="ghost"
                    size="icon"
                    aria-label="삭제"
                    disabled={removeMethod.isPending}
                    onClick={() => removeMethod.mutate(method.id)}
                  >
                    <Trash2 className="h-4 w-4 text-destructive" />
                  </Button>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
        {setDefaultErrorMessage && <p className="text-sm text-destructive">{setDefaultErrorMessage}</p>}
        {setDefaultMethod.isSuccess && (
          <p className="text-sm text-primary">기본 결제수단을 변경했습니다.</p>
        )}
      </div>

      <Card className="h-fit">
        <CardHeader>
          <CardTitle>결제수단 등록</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <p className="text-sm text-muted-foreground">
            카카오페이로 등록하면 PortOne 결제창에서 인증 후 빌링키가 자동 발급·등록됩니다
            (인증서 불필요).
          </p>
          {EASY_PAY_METHODS.map(({ provider, label }) => (
            <Button
              key={provider}
              className="w-full"
              disabled={
                !portoneReady ||
                !meQuery.data ||
                issuingKey !== null ||
                issue.isPending ||
                complete.isPending
              }
              onClick={() =>
                issueBillingKey(provider, {
                  billingKeyMethod: 'EASY_PAY',
                  easyPay: { easyPayProvider: provider },
                })
              }
            >
              {issuingKey === provider || issue.isPending
                ? '인증 준비 중…'
                : complete.isPending
                  ? '등록 중…'
                  : `${label}로 등록`}
            </Button>
          ))}
          {!portoneReady && (
            <p className="text-xs text-muted-foreground">
              PortOne 설정이 없어 결제창 발급이 비활성화되었습니다(.env의 VITE_PORTONE_* 설정 필요).
            </p>
          )}
          {portoneReady && meQuery.isError && (
            <p className="text-xs text-destructive">
              보호자 정보를 불러오지 못해 결제수단을 등록할 수 없습니다. 잠시 후 다시 시도해 주세요.
            </p>
          )}
          {registerErrorMessage && <p className="text-sm text-destructive">{registerErrorMessage}</p>}
          {(issueSuccessMessage ?? callbackMessage) && (
            <p className="text-sm">{issueSuccessMessage ?? callbackMessage}</p>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
