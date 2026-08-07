// 전체 진행상태 조합 계산 검증 (SA §5-4).
import { describe, expect, it } from 'vitest'
import { computeOverallProgress } from './status'
import { OverallProgress, PaymentStatus, ReservationStatus } from '@/types/enums'

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
