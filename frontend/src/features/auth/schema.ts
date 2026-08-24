// 인증 폼 검증 스키마 (백엔드 검증 미러링, SA §8-1).
import { z } from 'zod'

export const loginSchema = z.object({
  email: z.string().min(1, '이메일을 입력해 주세요.').email('올바른 이메일 형식이 아닙니다.'),
  password: z.string().min(1, '비밀번호를 입력해 주세요.'),
})
export type LoginInput = z.infer<typeof loginSchema>

// UTF-8 바이트 길이 (백엔드 @MaxUtf8Bytes 미러링용).
const utf8Bytes = (s: string) => new TextEncoder().encode(s).length

export const signupSchema = z.object({
  email: z
    .string()
    .min(1, '이메일을 입력해 주세요.')
    .email('올바른 이메일 형식이 아닙니다.')
    .max(255, '이메일은 255자를 초과할 수 없습니다.'),
  // 백엔드: @Size(min=8) + @MaxUtf8Bytes(72). 상한은 "문자 수"가 아니라 UTF-8 "바이트" 기준이라
  // 바이트로 검사해야 한글 비밀번호(1자=3바이트)의 서버 400을 프론트에서 미리 막는다.
  password: z
    .string()
    .min(8, '비밀번호는 8자 이상이어야 합니다.')
    .refine((v) => utf8Bytes(v) <= 72, {
      message: '비밀번호가 너무 깁니다. (UTF-8 기준 72바이트 이하)',
    }),
  // 백엔드 한도는 255자지만 UX상 50자로 둔다(실사용 닉네임은 이보다 짧다).
  nickname: z
    .string()
    .min(1, '닉네임을 입력해 주세요.')
    .max(50, '닉네임은 50자 이하여야 합니다.'),
  phone: z
    .string()
    .min(1, '전화번호를 입력해 주세요.')
    .regex(
      /^01(?:0|1|[6-9])(?:-\d{3,4}-\d{4}|\d{3,4}\d{4})$/,
      '전화번호 형식이 올바르지 않습니다. (예: 010-1234-5678)',
    ),
})
export type SignupInput = z.infer<typeof signupSchema>

// 비밀번호 재설정 요청 — 이메일만. (백엔드는 항상 200으로 응답)
export const passwordResetRequestSchema = z.object({
  email: z
    .string()
    .min(1, '이메일을 입력해 주세요.')
    .email('올바른 이메일 형식이 아닙니다.'),
})
export type PasswordResetRequestInput = z.infer<
  typeof passwordResetRequestSchema
>

// 비밀번호 재설정 확인 — 새 비밀번호(백엔드 @Size(min=8)+@MaxUtf8Bytes(72) 미러링) + 확인 입력.
export const passwordResetConfirmSchema = z
  .object({
    newPassword: z
      .string()
      .min(8, '비밀번호는 8자 이상이어야 합니다.')
      .refine((v) => utf8Bytes(v) <= 72, {
        message: '비밀번호가 너무 깁니다. (UTF-8 기준 72바이트 이하)',
      }),
    confirmPassword: z.string().min(1, '비밀번호를 한 번 더 입력해 주세요.'),
  })
  .refine((v) => v.newPassword === v.confirmPassword, {
    message: '비밀번호가 일치하지 않습니다.',
    path: ['confirmPassword'],
  })
export type PasswordResetConfirmInput = z.infer<
  typeof passwordResetConfirmSchema
>
