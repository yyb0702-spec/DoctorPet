// signup 스키마 검증 — 특히 비밀번호 UTF-8 바이트 상한(백엔드 @MaxUtf8Bytes(72) 미러링).
import { describe, expect, it } from 'vitest'
import { signupSchema } from './schema'

const base = {
  email: 'a@test.com',
  nickname: '보호자',
  phone: '010-1234-5678',
}

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

describe('signupSchema phone', () => {
  it.each(['010-1234-5678', '01012345678', '010-123-4567'])(
    '허용되는 휴대폰 형식 통과: %s',
    (phone) => {
      expect(
        signupSchema.safeParse({ ...base, password: 'password123', phone })
          .success,
      ).toBe(true)
    },
  )

  it.each([
    '010-12345678',
    '0101234-5678',
    '02-123-4567',
    '010-1234-567',
    '',
  ])('허용되지 않는 전화번호 형식 거절: %s', (phone) => {
    expect(
      signupSchema.safeParse({ ...base, password: 'password123', phone }).success,
    ).toBe(false)
  })
})
