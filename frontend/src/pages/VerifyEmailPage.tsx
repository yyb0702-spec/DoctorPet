// 이메일 인증 화면 — 메일 링크의 ?token= 을 받아 마운트 시 백엔드로 검증한다.
// 기존엔 메일 링크가 백엔드 API를 직접 가리켰으나, 이 페이지가 대신 fetch로 호출하는 구조.
import { Link, useSearchParams } from 'react-router-dom'
import { useVerifyEmail } from '@/features/auth/hooks'
import { ResendVerificationForm } from '@/features/auth/ResendVerificationForm'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { PageLoader } from '@/components/common/States'
import { ApiError } from '@/lib/api/error'

export function VerifyEmailPage() {
  const [searchParams] = useSearchParams()
  const token = searchParams.get('token') ?? ''
  const verify = useVerifyEmail(token)

  return (
    <div className="mx-auto max-w-sm py-10">
      <Card>
        <CardHeader className="items-center text-center">
          <CardTitle className="text-xl">이메일 인증</CardTitle>
        </CardHeader>
        <CardContent>
          {!token ? (
            <div className="space-y-4 text-center">
              <p className="text-sm text-destructive">
                유효하지 않은 접근입니다. 인증 메일의 링크로 다시 접속해 주세요.
              </p>
              <ResendVerificationForm />
            </div>
          ) : verify.isSuccess ? (
            <div className="space-y-4 text-center">
              <p className="text-sm">
                이메일 인증이 완료되었습니다. 이제 로그인할 수 있어요.
              </p>
              <Button asChild className="w-full">
                <Link to="/login">로그인하러 가기</Link>
              </Button>
            </div>
          ) : verify.isError ? (
            <div className="space-y-4 text-center">
              <p className="text-sm text-destructive">
                {verify.error instanceof ApiError
                  ? verify.error.message
                  : '이메일 인증에 실패했습니다.'}
              </p>
              <p className="text-sm text-muted-foreground">
                링크가 만료됐다면 인증 메일을 다시 받아 주세요.
              </p>
              <ResendVerificationForm />
            </div>
          ) : (
            <PageLoader label="이메일 인증 중…" />
          )}
        </CardContent>
      </Card>
    </div>
  )
}
