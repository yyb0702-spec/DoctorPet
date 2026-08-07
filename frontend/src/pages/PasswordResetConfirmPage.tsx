// 비밀번호 재설정 확인 화면 — 이메일 링크의 ?token= 를 받아 새 비밀번호를 설정한다.
// 토큰이 없으면(직접 접근) 안내만 보여주고, 무효/만료 토큰은 백엔드가 400(MEMBER_010)으로 응답한다.
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Link, useSearchParams } from 'react-router-dom'
import {
  passwordResetConfirmSchema,
  type PasswordResetConfirmInput,
} from '@/features/auth/schema'
import { usePasswordResetConfirm } from '@/features/auth/hooks'
import { Field } from '@/components/common/Field'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { ApiError } from '@/lib/api/error'

export function PasswordResetConfirmPage() {
  const [searchParams] = useSearchParams()
  const token = searchParams.get('token') ?? ''
  const confirm = usePasswordResetConfirm()
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<PasswordResetConfirmInput>({
    resolver: zodResolver(passwordResetConfirmSchema),
  })

  const onSubmit = handleSubmit((values) => {
    confirm.mutate({ token, newPassword: values.newPassword })
  })

  return (
    <div className="mx-auto max-w-sm py-10">
      <Card>
        <CardHeader className="items-center text-center">
          <CardTitle className="text-xl">새 비밀번호 설정</CardTitle>
        </CardHeader>
        <CardContent>
          {!token ? (
            <div className="space-y-4 text-center">
              <p className="text-sm text-destructive">
                유효하지 않은 접근입니다. 재설정 메일의 링크로 다시 접속해 주세요.
              </p>
              <Button asChild variant="outline" className="w-full">
                <Link to="/password-reset/request">재설정 링크 다시 받기</Link>
              </Button>
            </div>
          ) : confirm.isSuccess ? (
            <div className="space-y-4 text-center">
              <p className="text-sm">
                비밀번호가 변경되었습니다. 새 비밀번호로 로그인해 주세요.
              </p>
              <Button asChild className="w-full">
                <Link to="/login">로그인하러 가기</Link>
              </Button>
            </div>
          ) : (
            <form onSubmit={onSubmit} className="space-y-4">
              <Field
                label="새 비밀번호"
                htmlFor="newPassword"
                error={errors.newPassword?.message}
              >
                <Input
                  id="newPassword"
                  type="password"
                  autoComplete="new-password"
                  {...register('newPassword')}
                />
              </Field>
              <Field
                label="새 비밀번호 확인"
                htmlFor="confirmPassword"
                error={errors.confirmPassword?.message}
              >
                <Input
                  id="confirmPassword"
                  type="password"
                  autoComplete="new-password"
                  {...register('confirmPassword')}
                />
              </Field>
              {confirm.isError && (
                <p className="text-sm text-destructive">
                  {confirm.error instanceof ApiError
                    ? confirm.error.message
                    : '비밀번호 변경에 실패했습니다.'}
                </p>
              )}
              <Button
                type="submit"
                className="w-full"
                disabled={confirm.isPending}
              >
                {confirm.isPending ? '변경 중…' : '비밀번호 변경'}
              </Button>
            </form>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
