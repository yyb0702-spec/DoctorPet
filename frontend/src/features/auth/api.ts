// 인증 API (SA §8-1). 실연동 대상(develop 존재).
import { http } from '@/lib/api/client'
import type { LoginInput, SignupInput } from './schema'

export interface TokenPair {
  accessToken: string
  refreshToken: string
}

export const authApi = {
  signup: (input: SignupInput) =>
    http.post<{ memberId: number }>('/auth/signup', input),
  login: (input: LoginInput) => http.post<TokenPair>('/auth/login', input),
  logout: () => http.post<void>('/auth/logout'),
  // 비밀번호 재설정 요청 — 계정 노출 방지로 백엔드는 항상 200.
  requestPasswordReset: (email: string) =>
    http.post<void>('/auth/password-reset/request', { email }),
  // 비밀번호 재설정 확인 — 이메일 링크의 token + 새 비밀번호. 무효/만료 token은 400 MEMBER_010.
  confirmPasswordReset: (token: string, newPassword: string) =>
    http.post<void>('/auth/password-reset/confirm', { token, newPassword }),
  // 이메일 인증 — 메일 링크의 token을 프론트가 받아 이 API로 검증. 무효/만료 token은 400 MEMBER_010.
  verifyEmail: (token: string) =>
    http.get<void>(`/auth/verify-email?token=${encodeURIComponent(token)}`),
  // 인증 메일 재발송 — 계정 노출 방지로 백엔드는 항상 200.
  resendVerificationEmail: (email: string) =>
    http.post<void>('/auth/verify-email/resend', { email }),
}
