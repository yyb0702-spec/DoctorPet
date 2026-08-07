// 비밀번호 재설정 요청 화면 — 이메일 입력 → 재설정 링크 발송 안내.
// 백엔드는 계정 존재 여부를 노출하지 않으려 항상 200을 준다(성공 여부로 가입 여부를 알 수 없음).
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Link } from 'react-router-dom'
import {
  passwordResetRequestSchema,
  type PasswordResetRequestInput,
} from '@/features/auth/schema'
import { usePasswordResetRequest } from '@/features/auth/hooks'
import { Field } from '@/components/common/Field'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { ApiError } from '@/lib/api/error'

export function PasswordResetRequestPage() {
  const request = usePasswordResetRequest()
  const {
    register,
    handleSubmit,
    getValues,
    formState: { errors },
  } = useForm<PasswordResetRequestInput>({
    resolver: zodResolver(passwordResetRequestSchema),
  })

  const onSubmit = handleSubmit((values) => {
    request.mutate(values.email)
  })

  return (
    <div className="mx-auto max-w-sm py-10">
      <Card>
        <CardHeader className="items-center text-center">
          <CardTitle className="text-xl">비밀번호 재설정</CardTitle>
          <p className="text-sm text-muted-foreground">
            가입한 이메일로 재설정 링크를 보내드려요.
          </p>
        </CardHeader>
        <CardContent>
          {request.isSuccess ? (
            <div className="space-y-4 text-center">
              <p className="text-sm">
                <span className="font-medium">{getValues('email')}</span> 로 가입된
                계정이 있다면 재설정 링크를 보냈습니다. 메일함을 확인해 주세요.
              </p>
              <Button asChild variant="outline" className="w-full">
                <Link to="/login">로그인으로 돌아가기</Link>
              </Button>
            </div>
          ) : (
            <form onSubmit={onSubmit} className="space-y-4">
              <Field label="이메일" htmlFor="email" error={errors.email?.message}>
                <Input
                  id="email"
                  type="email"
                  autoComplete="email"
                  {...register('email')}
                />
              </Field>
              {request.isError && (
                <p className="text-sm text-destructive">
                  {request.error instanceof ApiError
                    ? request.error.message
                    : '요청에 실패했습니다. 잠시 후 다시 시도해 주세요.'}
                </p>
              )}
              <Button
                type="submit"
                className="w-full"
                disabled={request.isPending}
              >
                {request.isPending ? '전송 중…' : '재설정 링크 보내기'}
              </Button>
              <p className="text-center text-sm text-muted-foreground">
                <Link
                  to="/login"
                  className="font-medium text-primary hover:underline"
                >
                  로그인으로 돌아가기
                </Link>
              </p>
            </form>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
