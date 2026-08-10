// 로그인 화면.
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import { loginSchema, type LoginInput } from '@/features/auth/schema'
import { useLogin } from '@/features/auth/hooks'
import { ResendVerificationForm } from '@/features/auth/ResendVerificationForm'
import { memberApi } from '@/features/members/api'
import { memberKeys } from '@/features/members/hooks'
import { Field } from '@/components/common/Field'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { ApiError } from '@/lib/api/error'
import { MemberRole } from '@/types/enums'

interface LocationState {
  from?: { pathname: string }
}

export function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const queryClient = useQueryClient()
  const login = useLogin()
  const {
    register,
    handleSubmit,
    getValues,
    formState: { errors },
  } = useForm<LoginInput>({ resolver: zodResolver(loginSchema) })

  // 미인증 계정 로그인(403 EMAIL_NOT_VERIFIED)은 재발송 UX로 별도 처리.
  const emailNotVerified =
    login.error instanceof ApiError && login.error.code === 'MEMBER_009'

  const from = (location.state as LocationState | null)?.from?.pathname ?? '/'

  const onSubmit = handleSubmit((values) => {
    login.mutate(values, {
      onSuccess: async () => {
        // 로그인 응답에는 role이 없다 — 병원 스태프면 /staff로 보내기 위해
        // 여기서 내 정보를 한 번 조회하고, useMe()가 다시 부르지 않도록 캐시에 채운다.
        try {
          const me = await memberApi.getMe()
          queryClient.setQueryData(memberKeys.me, me)
          if (me.role === MemberRole.HOSPITAL_STAFF) {
            navigate('/staff', { replace: true })
            return
          }
        } catch {
          // 조회 실패해도 로그인 자체는 성공했으므로 보호자 기본 경로로 보낸다.
        }
        navigate(from, { replace: true })
      },
    })
  })

  return (
    <div className="mx-auto max-w-sm py-10">
      <Card>
        <CardHeader className="items-center text-center">
          <CardTitle className="text-xl">다시 만나서 반가워요</CardTitle>
          <p className="text-sm text-muted-foreground">
            예약과 결제 내역을 이어서 관리하세요.
          </p>
        </CardHeader>
        <CardContent>
          <form onSubmit={onSubmit} className="space-y-4">
            <Field label="이메일" htmlFor="email" error={errors.email?.message}>
              <Input
                id="email"
                type="email"
                autoComplete="email"
                {...register('email')}
              />
            </Field>
            <Field
              label="비밀번호"
              htmlFor="password"
              error={errors.password?.message}
            >
              <Input
                id="password"
                type="password"
                autoComplete="current-password"
                {...register('password')}
              />
            </Field>
            {login.isError && !emailNotVerified && (
              <p className="text-sm text-destructive">
                {login.error instanceof ApiError
                  ? login.error.message
                  : '로그인에 실패했습니다.'}
              </p>
            )}
            {emailNotVerified && (
              <div className="space-y-2 rounded-md border border-amber-300 bg-amber-50 p-3 dark:border-amber-500/40 dark:bg-amber-500/10">
                <p className="text-sm">
                  {login.error instanceof ApiError
                    ? login.error.message
                    : '이메일 인증이 필요합니다.'}
                </p>
                <ResendVerificationForm defaultEmail={getValues('email')} />
              </div>
            )}
            <Button type="submit" className="w-full" disabled={login.isPending}>
              {login.isPending ? '로그인 중…' : '로그인'}
            </Button>
            <p className="text-center text-sm">
              <Link
                to="/password-reset/request"
                className="text-muted-foreground hover:text-primary hover:underline"
              >
                비밀번호를 잊으셨나요?
              </Link>
            </p>
          </form>
          <p className="mt-4 text-center text-sm text-muted-foreground">
            아직 회원이 아니신가요?{' '}
            <Link to="/signup" className="font-medium text-primary hover:underline">
              회원가입
            </Link>
          </p>
        </CardContent>
      </Card>
    </div>
  )
}
