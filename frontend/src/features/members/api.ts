// 회원 API (SA §8-1). GET/PATCH/DELETE /api/members/me. 실연동 대상.
import { http } from '@/lib/api/client'
import type { MemberRole } from '@/types/enums'

// MemberResponse (백엔드 실제 필드 기준).
export interface Member {
  memberId: number
  email: string
  nickname: string
  role: MemberRole
  hospitalId: number | null
}

export const memberApi = {
  getMe: () => http.get<Member>('/members/me'),
  // 닉네임 수정. NotBlank·최대 255자(백엔드 NicknameUpdateRequest).
  updateNickname: (nickname: string) =>
    http.patch<Member>('/members/me', { nickname }),
  // 회원 탈퇴. 활성 예약(CONFIRMED·CHECKED_IN)·미수금 보유 시 409 MEMBER_008로 보류된다.
  withdraw: () => http.delete<void>('/members/me'),
}
