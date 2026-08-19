// 만석 슬롯에 대기 신청 버튼을 막아야 하는지·왜 막는지 판별.
// 백엔드 reactivateTerminalIfSlotReserved는 CANCELED·REJECTED·EXPIRED만 재활성화하고,
// ACCEPTED 이력이 있으면 existsByMemberIdAndSlotId가 걸려 항상 WAITLIST_002(409)다 — 그래서
// WAITING·OFFERED("대기중")와 ACCEPTED("예약완료")를 구분해야 버튼을 잘못 노출하지 않는다(PR #188 리뷰).
import type { Waitlist } from './types'
import { WaitlistStatus } from './types'

export type WaitlistBlockReason = 'PENDING' | 'ACCEPTED' | null

export function waitlistBlockReason(
  waitlists: Waitlist[],
  slotId: number,
): WaitlistBlockReason {
  const forSlot = waitlists.filter((w) => w.slotId === slotId)
  if (forSlot.some((w) => w.status === WaitlistStatus.ACCEPTED)) return 'ACCEPTED'
  if (
    forSlot.some(
      (w) =>
        w.status === WaitlistStatus.WAITING || w.status === WaitlistStatus.OFFERED,
    )
  ) {
    return 'PENDING'
  }
  return null
}
