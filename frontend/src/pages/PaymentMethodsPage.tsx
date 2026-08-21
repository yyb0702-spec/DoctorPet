// 결제수단 관리 — 목록 + 등록(PortOne 빌링키 발급 / 직접 입력) + 삭제.
import { useEffect, useState } from 'react'
import { Trash2, CreditCard } from 'lucide-react'
import * as PortOne from '@portone/browser-sdk/v2'
import type { EasyPayProvider } from '@portone/browser-sdk/v2'
import { useLocation, useNavigate } from 'react-router-dom'
import {
  usePaymentMethods,
  useRegisterPaymentMethod,
  useRemovePaymentMethod,
  useSetDefaultPaymentMethod,
} from '@/features/payments/hooks'
import { Field } from '@/components/common/Field'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { EmptyState, ErrorState, PageLoader } from '@/components/common/States'
import { useMe } from '@/features/members/hooks'
import { ApiError } from '@/lib/api/error'

// 클라이언트 노출 값(비밀 아님). 콘솔 연동정보의 상점ID·결제(정기결제) 채널 키.
function getPortOneConfig() {
  return {
    storeId: import.meta.env.VITE_PORTONE_STORE_ID as string | undefined,
    channelKey: import.meta.env.VITE_PORTONE_CHANNEL_KEY as string | undefined,
  }
}

// 빌링키 원본을 텍스트로 입력·표시하는 UI는 운영 빌드에 노출하지 않는다 — 로컬
// 개발(DEV) + 명시적 옵트인 플래그를 모두 만족할 때만 렌더링한다(PR #127 리뷰).
const ALLOW_MANUAL_BILLING_KEY =
  import.meta.env.DEV && import.meta.env.VITE_ALLOW_MANUAL_BILLING_KEY === 'true'

// 현재 .env의 VITE_PORTONE_CHANNEL_KEY는 "카카오페이 다이렉트" 채널 하나뿐이다 — PortOne에서
// 간편결제 다이렉트 채널은 PG 계약 자체가 그 수단 하나로 고정돼 있어, easyPayProvider를
// NAVERPAY·TOSSPAY 등으로 바꿔 보내도 채널이 무시하고 카카오페이 인증창을 그대로 띄운다.
// billingKeyMethod=CARD도 이 채널이 지원하지 않아 PortOne이 400으로 거절한다. 다른 수단을
// 실제로 쓰려면 콘솔에서 해당 PG(또는 채널 그룹)를 추가 연동해 channelKey/channelGroupId를
// 늘려야 하므로, 그 전까지는 실제로 동작하는 카카오페이만 노출한다.
const EASY_PAY_METHODS: { provider: EasyPayProvider; label: string }[] = [
  { provider: 'KAKAOPAY', label: '카카오페이' },
]

// 카카오페이는 모바일에서 REDIRECTION 방식만 지원한다. 인증 결과는 이 경로의 query로 되돌아오며,
// 빌링키는 등록 요청을 시작한 뒤 즉시 주소창에서 제거한다(새로고침에 의한 중복 등록·노출 방지).
const BILLING_KEY_CALLBACK_PATH = '/payment-methods'

function getRedirectErrorMessage(state: unknown) {
  if (
    typeof state === 'object' &&
    state !== null &&
    'portoneErrorMessage' in state &&
    typeof state.portoneErrorMessage === 'string'
  ) {
    return state.portoneErrorMessage
  }
  return null
}

export function PaymentMethodsPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const meQuery = useMe()
  const methodsQuery = usePaymentMethods()
  const registerMethod = useRegisterPaymentMethod()
  const removeMethod = useRemovePaymentMethod()
  const setDefaultMethod = useSetDefaultPaymentMethod()
  const [billingKey, setBillingKey] = useState('')
  // 어떤 등록 버튼을 눌렀는지 추적한다(버튼별 로딩 라벨·중복 클릭 방지용).
  const [issuingKey, setIssuingKey] = useState<string | null>(null)
  const [sdkError, setSdkError] = useState<string | null>(null)

  const { storeId, channelKey } = getPortOneConfig()
  const portoneReady = Boolean(storeId && channelKey)
  const redirectErrorMessage = getRedirectErrorMessage(location.state)
  const registerBillingKey = registerMethod.mutate

  useEffect(() => {
    const params = new URLSearchParams(location.search)
    const billingKeyFromRedirect = params.get('billingKey')
    const portoneErrorCode = params.get('code')

    // 일반 진입과 PC iframe 응답은 URL query가 없으므로 아무 작업도 하지 않는다.
    if (!billingKeyFromRedirect && !portoneErrorCode) return

    // 먼저 query를 지워야 네트워크 지연 중 새로고침해도 같은 빌링키를 두 번 등록하지 않는다.
    if (portoneErrorCode) {
      navigate(BILLING_KEY_CALLBACK_PATH, {
        replace: true,
        state: {
          portoneErrorMessage:
            params.get('message') ?? '카카오페이 인증에 실패했습니다.',
        },
      })
      return
    }

    navigate(BILLING_KEY_CALLBACK_PATH, { replace: true })
    registerBillingKey(billingKeyFromRedirect!)
  }, [location.search, navigate, registerBillingKey])

  // PortOne 결제창으로 빌링키를 발급받아 그대로 등록한다(발급→등록 원스텝). 카드 등록(CARD)과
  // 간편결제(EASY_PAY+provider) 모두 이 함수 하나로 처리한다.
  // 빌링키 원본은 서버로만 전달하고 화면·상태에 보관하지 않는다(등록 후 서버가 brand·last4만 반환).
  const issueBillingKey = async (
    key: string,
    issueId: string,
    request:
      | { billingKeyMethod: 'CARD' }
      | { billingKeyMethod: 'EASY_PAY'; easyPay: { easyPayProvider: EasyPayProvider } },
  ) => {
    if (!portoneReady) {
      setSdkError('PortOne 설정(VITE_PORTONE_STORE_ID / VITE_PORTONE_CHANNEL_KEY)이 없습니다.')
      return
    }
    if (!meQuery.data) {
      setSdkError('보호자 정보를 확인한 뒤 다시 시도해 주세요.')
      return
    }
    // 이전 모바일 인증 실패 메시지는 이번 새 요청과 무관하므로 라우트 상태에서 비운다.
    if (redirectErrorMessage) {
      navigate(BILLING_KEY_CALLBACK_PATH, { replace: true })
    }
    setSdkError(null)
    setIssuingKey(key)
    try {
      const res = await PortOne.requestIssueBillingKey({
        storeId: storeId!,
        channelKey: channelKey!,
        ...request,
        issueId,
        issueName: 'DoctorPet 결제수단',
        redirectUrl: `${window.location.origin}${BILLING_KEY_CALLBACK_PATH}`,
        // 회원 API가 제공하는 실제 로그인 보호자 식별자·이메일만 전달한다. 전화번호 응답 계약이 없는데
        // 임의의 번호를 보내면 PG 기록이 오염되므로 넣지 않는다.
        customer: {
          customerId: `doctorpet-member-${meQuery.data.memberId}`,
          email: meQuery.data.email,
        },
      })
      if (!res) {
        setSdkError('발급창 응답이 없습니다.')
        return
      }
      if ('code' in res && res.code) {
        setSdkError(res.message ?? '빌링키 발급에 실패했습니다.')
        return
      }
      if ('billingKey' in res && res.billingKey) {
        registerMethod.mutate(res.billingKey)
      } else {
        setSdkError('응답에서 billingKey를 찾지 못했습니다.')
      }
    } catch (e) {
      setSdkError(e instanceof Error ? e.message : '빌링키 발급 중 오류가 발생했습니다.')
    } finally {
      setIssuingKey(null)
    }
  }

  const handleRegisterManual = () => {
    if (!billingKey.trim()) return
    registerMethod.mutate(billingKey.trim(), {
      onSuccess: () => setBillingKey(''),
    })
  }

  // 기본 결제수단을 맨 위로 올린다(서버 정렬은 등록순) — 자동 결제에 쓰이는 수단이라 먼저 보여야 한다.
  const sortedMethods = [...(methodsQuery.data ?? [])].sort(
    (a, b) => Number(b.isDefault) - Number(a.isDefault),
  )

  const setDefaultErrorMessage = setDefaultMethod.isError
    ? setDefaultMethod.error instanceof ApiError
      ? setDefaultMethod.error.message
      : '기본 결제수단 지정에 실패했습니다.'
    : null

  const registerErrorMessage =
    sdkError ??
    (registerMethod.isError
      ? registerMethod.error instanceof ApiError
        ? registerMethod.error.message
        : '등록에 실패했습니다.'
      : redirectErrorMessage)

  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <div className="space-y-3">
        <h1 className="text-2xl font-bold">결제수단</h1>
        {methodsQuery.isLoading && <PageLoader />}
        {methodsQuery.isError && (
          <ErrorState onRetry={() => methodsQuery.refetch()} />
        )}
        {methodsQuery.data && methodsQuery.data.length === 0 && (
          <EmptyState message="등록된 결제수단이 없습니다." />
        )}
        <div className="grid gap-3">
          {sortedMethods.map((m) => (
            <Card key={m.id}>
              <CardContent className="flex items-center justify-between p-5">
                <div className="flex items-center gap-3">
                  <CreditCard className="h-5 w-5 text-primary" />
                  <div className="space-y-0.5">
                    <div className="flex items-center gap-2">
                      <span className="font-medium">
                        {m.cardBrand ?? '카드'} ****{m.cardLast4 ?? '****'}
                      </span>
                      {m.isDefault && <Badge>기본</Badge>}
                      {m.status !== 'ACTIVE' && (
                        <Badge variant="muted">{m.status}</Badge>
                      )}
                    </div>
                  </div>
                </div>
                <div className="flex items-center gap-1">
                  {/*
                    기본 지정은 ACTIVE 수단만 가능하다(서버도 ACTIVE·본인 소유만 받는다). 이미 기본인
                    수단에는 버튼을 두지 않는다 — 기본은 회원당 1건이라 해제라는 동작이 없다.
                  */}
                  {m.status === 'ACTIVE' && !m.isDefault && (
                    <Button
                      variant="ghost"
                      size="sm"
                      className="text-xs"
                      disabled={setDefaultMethod.isPending}
                      onClick={() => setDefaultMethod.mutate(m.id)}
                    >
                      기본으로 지정
                    </Button>
                  )}
                  <Button
                    variant="ghost"
                    size="icon"
                    aria-label="삭제"
                    disabled={removeMethod.isPending}
                    onClick={() => removeMethod.mutate(m.id)}
                  >
                    <Trash2 className="h-4 w-4 text-destructive" />
                  </Button>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
        {setDefaultErrorMessage && (
          <p className="text-sm text-destructive">{setDefaultErrorMessage}</p>
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
                registerMethod.isPending
              }
              onClick={() =>
                issueBillingKey(provider, `dp-bk-${crypto.randomUUID()}`, {
                  billingKeyMethod: 'EASY_PAY',
                  easyPay: { easyPayProvider: provider },
                })
              }
            >
              {issuingKey === provider
                ? '발급창 여는 중…'
                : registerMethod.isPending
                  ? '등록 중…'
                  : `${label}로 등록`}
            </Button>
          ))}
          {!portoneReady && (
            <p className="text-xs text-muted-foreground">
              PortOne 설정이 없어 결제창 발급이 비활성화되었습니다(.env의 VITE_PORTONE_* 설정 필요).
              {ALLOW_MANUAL_BILLING_KEY && ' 아래에서 빌링키를 직접 입력해 등록할 수 있습니다.'}
            </p>
          )}
          {portoneReady && meQuery.isError && (
            <p className="text-xs text-destructive">
              보호자 정보를 불러오지 못해 결제수단을 등록할 수 없습니다. 잠시 후 다시 시도해 주세요.
            </p>
          )}

          {ALLOW_MANUAL_BILLING_KEY && (
            <div className="border-t pt-4">
              <p className="mb-2 text-xs text-muted-foreground">
                또는 발급된 빌링키를 직접 입력(개발·목용)
              </p>
              <Field label="빌링키" htmlFor="billingKey">
                <Input
                  id="billingKey"
                  value={billingKey}
                  onChange={(e) => setBillingKey(e.target.value)}
                  placeholder="billing-key-xxx"
                />
              </Field>
              <Button
                className="mt-2 w-full"
                variant="secondary"
                disabled={!billingKey.trim() || registerMethod.isPending}
                onClick={handleRegisterManual}
              >
                {registerMethod.isPending ? '등록 중…' : '직접 입력으로 등록'}
              </Button>
            </div>
          )}

          {registerErrorMessage && (
            <p className="text-sm text-destructive">{registerErrorMessage}</p>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
