// signup 스키마 검증 — 특히 비밀번호 UTF-8 바이트 상한(백엔드 @MaxUtf8Bytes(72) 미러링).
import { describe, expect, it } from 'vitest'
import { signupSchema } from './schema'

const base = { email: 'a@test.com', nickname: '보호자' }

describe('signupSchema password', () => {
  it('8자 미만은 거절', () => {
    expect(signupSchema.safeParse({ ...base, password: 'short7!' }).success).toBe(
      false,
    )
  })

  it('ASCII 72바이트 이하는 통과', () => {
    expect(
      signupSchema.safeParse({ ...base, password: 'a'.repeat(72) }).success,
    ).toBe(true)
  })

  it('한글 24자(72바이트)는 통과, 25자(75바이트)는 거절', () => {
    expect(
      signupSchema.safeParse({ ...base, password: '가'.repeat(24) }).success,
    ).toBe(true)
    expect(
      signupSchema.safeParse({ ...base, password: '가'.repeat(25) }).success,
    ).toBe(false)
  })
})
