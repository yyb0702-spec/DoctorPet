// 전체 진행상태 조합 계산 검증 (SA §5-4).
import { describe, expect, it } from 'vitest'
import {
  computeOverallProgress,
  PROGRESS_STATUS_META,
  RESERVATION_STATUS_META,
} from './status'
import {
  OverallProgress,
  PaymentStatus,
  ReservationProgressStatus,
  ReservationStatus,
} from '@/types/enums'

// 계약 테스트: 백엔드 enum에 값이 추가됐는데 배지 매핑을 안 채우면(NO_SHOW_PENDING
// 누락 사례) meta.variant 접근에서 런타임 오류가 난다. 여기서 컴파일 타임(Record
// 완전성)이 아니라 런타임에도 걸리도록 값 목록 자체를 순회해 검증한다.
describe('상태 배지 매핑 완전성', () => {
  it('ReservationStatus의 모든 값에 배지 매핑이 있다', () => {
    for (const status of Object.values(ReservationStatus)) {
      expect(RESERVATION_STATUS_META[status]).toBeDefined()
    }
  })

  it('ReservationProgressStatus의 모든 값에 배지 매핑이 있다', () => {
    for (const status of Object.values(ReservationProgressStatus)) {
      expect(PROGRESS_STATUS_META[status]).toBeDefined()
    }
  })
})

describe('computeOverallProgress', () => {
  it('진료 완료 전이면 null (예약 상태가 곧 진행상태)', () => {
    expect(
      computeOverallProgress(ReservationStatus.CONFIRMED, null),
    ).toBeNull()
    expect(
      computeOverallProgress(ReservationStatus.REQUESTED, PaymentStatus.PENDING),
    ).toBeNull()
  })

  it('진료 완료 + 결제 없음 → 청구 전', () => {
    expect(
      computeOverallProgress(ReservationStatus.TREATMENT_COMPLETED, null),
    ).toBe(OverallProgress.TREATMENT_DONE_UNBILLED)
  })

  it('진료 완료 + PAID/OFFLINE_PAID → 결제완료', () => {
    expect(
      computeOverallProgress(
        ReservationStatus.TREATMENT_COMPLETED,
        PaymentStatus.PAID,
      ),
    ).toBe(OverallProgress.PAYMENT_COMPLETED)
    expect(
      computeOverallProgress(
        ReservationStatus.TREATMENT_COMPLETED,
        PaymentStatus.OFFLINE_PAID,
      ),
    ).toBe(OverallProgress.PAYMENT_COMPLETED)
  })

  it('진료 완료 + OFFLINE_REQUIRED → 미수금', () => {
    expect(
      computeOverallProgress(
        ReservationStatus.TREATMENT_COMPLETED,
        PaymentStatus.OFFLINE_REQUIRED,
      ),
    ).toBe(OverallProgress.OUTSTANDING)
  })

  it('진료 완료 + PENDING → 결제 진행중', () => {
    expect(
      computeOverallProgress(
        ReservationStatus.TREATMENT_COMPLETED,
        PaymentStatus.PENDING,
      ),
    ).toBe(OverallProgress.PAYMENT_IN_PROGRESS)
  })

  it('진료 완료 + REFUNDED → 환불 완료', () => {
    expect(
      computeOverallProgress(
        ReservationStatus.TREATMENT_COMPLETED,
        PaymentStatus.REFUNDED,
      ),
    ).toBe(OverallProgress.REFUNDED)
  })
})
