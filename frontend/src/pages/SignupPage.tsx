// 회원가입 화면.
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Link, useNavigate } from 'react-router-dom'
import { signupSchema, type SignupInput } from '@/features/auth/schema'
import { useSignup } from '@/features/auth/hooks'
import { Field } from '@/components/common/Field'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { ApiError } from '@/lib/api/error'

export function SignupPage() {
  const navigate = useNavigate()
  const signup = useSignup()
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<SignupInput>({ resolver: zodResolver(signupSchema) })

  const onSubmit = handleSubmit((values) => {
    signup.mutate(values, {
      // 가입 후 로그인 화면으로 이동(자동 로그인은 별도).
      onSuccess: () => navigate('/login', { replace: true }),
    })
  })

  return (
    <div className="mx-auto max-w-sm py-10">
      <Card>
        <CardHeader className="items-center text-center">
          <CardTitle className="text-xl">보호자 회원가입</CardTitle>
          <p className="text-sm text-muted-foreground">
            예약과 진료 내역을 안전하게 관리해요.
          </p>
        </CardHeader>
        <CardContent>
          <form onSubmit={onSubmit} className="space-y-4">
            {/* 백엔드 필드는 nickname. 와이어프레임 표기에 맞춰 라벨만 '이름'. */}
            <Field label="이름" htmlFor="nickname" error={errors.nickname?.message}>
              <Input id="nickname" placeholder="김보호" {...register('nickname')} />
            </Field>
            <Field label="전화번호" htmlFor="phone" error={errors.phone?.message}>
              <Input
                id="phone"
                type="tel"
                autoComplete="tel"
                placeholder="010-1234-5678"
                {...register('phone')}
              />
            </Field>
            <Field label="이메일" htmlFor="email" error={errors.email?.message}>
              <Input id="email" type="email" autoComplete="email" {...register('email')} />
            </Field>
            <Field label="비밀번호" htmlFor="password" error={errors.password?.message}>
              <Input
                id="password"
                type="password"
                autoComplete="new-password"
                {...register('password')}
              />
            </Field>
            {signup.isError && (
              <p className="text-sm text-destructive">
                {signup.error instanceof ApiError
                  ? signup.error.message
                  : '회원가입에 실패했습니다.'}
              </p>
            )}
            <Button type="submit" className="w-full" disabled={signup.isPending}>
              {signup.isPending ? '가입 중…' : '가입하기'}
            </Button>
          </form>
          <p className="mt-4 text-center text-sm text-muted-foreground">
            이미 계정이 있으신가요?{' '}
            <Link to="/login" className="font-medium text-primary hover:underline">
              로그인
            </Link>
          </p>
        </CardContent>
      </Card>
    </div>
  )
}
