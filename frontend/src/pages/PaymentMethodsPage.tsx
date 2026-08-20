// 결제수단 관리 — 목록 + 등록(PortOne 빌링키 발급 / 직접 입력) + 삭제.
import { useState } from 'react'
import { Trash2, CreditCard } from 'lucide-react'
import * as PortOne from '@portone/browser-sdk/v2'
import type { EasyPayProvider } from '@portone/browser-sdk/v2'
import {
  usePaymentMethods,
  useRegisterPaymentMethod,
  useRemovePaymentMethod,
} from '@/features/payments/hooks'
import { Field } from '@/components/common/Field'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { EmptyState, ErrorState, PageLoader } from '@/components/common/States'
import { ApiError } from '@/lib/api/error'

// 클라이언트 노출 값(비밀 아님). 콘솔 연동정보의 상점ID·결제(정기결제) 채널 키.
const PORTONE_STORE_ID = import.meta.env.VITE_PORTONE_STORE_ID as string | undefined
const PORTONE_CHANNEL_KEY = import.meta.env.VITE_PORTONE_CHANNEL_KEY as string | undefined

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

export function PaymentMethodsPage() {
  const methodsQuery = usePaymentMethods()
  const registerMethod = useRegisterPaymentMethod()
  const removeMethod = useRemovePaymentMethod()
  const [billingKey, setBillingKey] = useState('')
  // 어떤 등록 버튼을 눌렀는지 추적한다(버튼별 로딩 라벨·중복 클릭 방지용).
  const [issuingKey, setIssuingKey] = useState<string | null>(null)
  const [sdkError, setSdkError] = useState<string | null>(null)

  const portoneReady = Boolean(PORTONE_STORE_ID && PORTONE_CHANNEL_KEY)

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
    setSdkError(null)
    setIssuingKey(key)
    try {
      const res = await PortOne.requestIssueBillingKey({
        storeId: PORTONE_STORE_ID!,
        channelKey: PORTONE_CHANNEL_KEY!,
        ...request,
        issueId,
        issueName: 'DoctorPet 결제수단',
        // TODO: 실제 로그인 보호자 정보로 대체(회원정보 조회 연동). 지금은 발급에 필요한 최소 정보만 채운다.
        customer: {
          fullName: '보호자',
          phoneNumber: '010-0000-0000',
          email: 'guardian@doctorpet.example',
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

  const registerErrorMessage =
    sdkError ??
    (registerMethod.isError
      ? registerMethod.error instanceof ApiError
        ? registerMethod.error.message
        : '등록에 실패했습니다.'
      : null)

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
          {methodsQuery.data?.map((m) => (
            <Card key={m.id}>
              <CardContent className="flex items-center justify-between p-5">
                <div className="flex items-center gap-3">
                  <CreditCard className="h-5 w-5 text-primary" />
                  <div className="space-y-0.5">
                    <div className="flex items-center gap-2">
                      <span className="font-medium">
                        {m.cardBrand ?? '카드'} ****{m.cardLast4 ?? '****'}
                      </span>
                      {m.status !== 'ACTIVE' && (
                        <Badge variant="muted">{m.status}</Badge>
                      )}
                    </div>
                  </div>
                </div>
                <Button
                  variant="ghost"
                  size="icon"
                  aria-label="삭제"
                  disabled={removeMethod.isPending}
                  onClick={() => removeMethod.mutate(m.id)}
                >
                  <Trash2 className="h-4 w-4 text-destructive" />
                </Button>
              </CardContent>
            </Card>
          ))}
        </div>
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
                !portoneReady || issuingKey !== null || registerMethod.isPending
              }
              onClick={() =>
                issueBillingKey(provider, `dp-${Date.now()}`, {
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
