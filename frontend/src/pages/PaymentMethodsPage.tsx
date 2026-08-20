// 결제수단 관리 — 목록 + 등록(PortOne 빌링키 발급 / 직접 입력) + 삭제.
import { useState } from 'react'
import { Trash2, CreditCard } from 'lucide-react'
import * as PortOne from '@portone/browser-sdk/v2'
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
import { ApiError } from '@/lib/api/error'

// 클라이언트 노출 값(비밀 아님). 콘솔 연동정보의 상점ID·결제(정기결제) 채널 키.
const PORTONE_STORE_ID = import.meta.env.VITE_PORTONE_STORE_ID as string | undefined
const PORTONE_CHANNEL_KEY = import.meta.env.VITE_PORTONE_CHANNEL_KEY as string | undefined

// 빌링키 원본을 텍스트로 입력·표시하는 UI는 운영 빌드에 노출하지 않는다 — 로컬
// 개발(DEV) + 명시적 옵트인 플래그를 모두 만족할 때만 렌더링한다(PR #127 리뷰).
const ALLOW_MANUAL_BILLING_KEY =
  import.meta.env.DEV && import.meta.env.VITE_ALLOW_MANUAL_BILLING_KEY === 'true'

export function PaymentMethodsPage() {
  const methodsQuery = usePaymentMethods()
  const registerMethod = useRegisterPaymentMethod()
  const removeMethod = useRemovePaymentMethod()
  const setDefaultMethod = useSetDefaultPaymentMethod()
  const [billingKey, setBillingKey] = useState('')
  const [issuing, setIssuing] = useState(false)
  const [sdkError, setSdkError] = useState<string | null>(null)

  const portoneReady = Boolean(PORTONE_STORE_ID && PORTONE_CHANNEL_KEY)

  // PortOne 결제창으로 카카오페이 빌링키를 발급받아 그대로 등록한다(발급→등록 원스텝).
  // 빌링키 원본은 서버로만 전달하고 화면·상태에 보관하지 않는다(등록 후 서버가 brand·last4만 반환).
  const issueWithKakaoPay = async () => {
    if (!portoneReady) {
      setSdkError('PortOne 설정(VITE_PORTONE_STORE_ID / VITE_PORTONE_CHANNEL_KEY)이 없습니다.')
      return
    }
    setSdkError(null)
    setIssuing(true)
    try {
      const res = await PortOne.requestIssueBillingKey({
        storeId: PORTONE_STORE_ID!,
        channelKey: PORTONE_CHANNEL_KEY!,
        billingKeyMethod: 'EASY_PAY',
        easyPay: { easyPayProvider: 'KAKAOPAY' },
        issueId: `dp-${Date.now()}`,
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
      setIssuing(false)
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
          <Button
            className="w-full"
            disabled={!portoneReady || issuing || registerMethod.isPending}
            onClick={issueWithKakaoPay}
          >
            {issuing
              ? '발급창 여는 중…'
              : registerMethod.isPending
                ? '등록 중…'
                : '카카오페이로 등록'}
          </Button>
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
