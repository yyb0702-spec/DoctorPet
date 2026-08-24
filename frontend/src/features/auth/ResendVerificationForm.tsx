// 인증 메일 재발송 폼 — 이메일 인증 실패/미인증 로그인 안내에서 공용으로 쓴다.
import { useState } from 'react'
import { useResendVerificationEmail } from './hooks'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'

export function ResendVerificationForm({
  defaultEmail = '',
}: {
  defaultEmail?: string
}) {
  const [email, setEmail] = useState(defaultEmail)
  const resend = useResendVerificationEmail()
  const valid = /\S+@\S+\.\S+/.test(email)

  if (resend.isSuccess) {
    return (
      <p className="text-sm text-muted-foreground">
        인증 메일을 다시 보냈습니다. 메일함을 확인해 주세요.
      </p>
    )
  }

  return (
    <div className="space-y-2">
      <Input
        type="email"
        placeholder="가입한 이메일"
        autoComplete="email"
        value={email}
        onChange={(e) => setEmail(e.target.value)}
      />
      <Button
        size="sm"
        className="w-full"
        disabled={!valid || resend.isPending}
        onClick={() => resend.mutate(email)}
      >
        {resend.isPending ? '전송 중…' : '인증 메일 재발송'}
      </Button>
    </div>
  )
}
