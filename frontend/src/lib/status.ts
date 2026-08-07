// 예약·결제 상태의 표시 라벨·배지색 매핑과 전체 진행상태 조합 계산 (SA §5).
import {
  OverallProgress,
  PaymentStatus,
  ReservationProgressStatus,
  ReservationStatus,
} from '@/types/enums'

type BadgeVariant =
  | 'default'
  | 'secondary'
  | 'destructive'
  | 'outline'
  | 'success'
  | 'warning'
  | 'muted'

interface StatusMeta {
  label: string
  variant: BadgeVariant
}

// 예약 상태 (SA §5-1)
export const RESERVATION_STATUS_META: Record<ReservationStatus, StatusMeta> = {
  [ReservationStatus.REQUESTED]: { label: '승인 대기', variant: 'warning' },
  [ReservationStatus.CONFIRMED]: { label: '예약 확정', variant: 'default' },
  [ReservationStatus.CHECKED_IN]: { label: '내원 완료', variant: 'secondary' },
  [ReservationStatus.IN_TREATMENT]: { label: '진료 중', variant: 'secondary' },
  [ReservationStatus.TREATMENT_COMPLETED]: {
    label: '진료 완료',
    variant: 'success',
  },
  [ReservationStatus.REJECTED]: { label: '거절됨', variant: 'destructive' },
  [ReservationStatus.CANCELED]: { label: '취소됨', variant: 'muted' },
  [ReservationStatus.NO_SHOW]: { label: '노쇼', variant: 'destructive' },
}

// 결제 상태 (SA §5-2)
export const PAYMENT_STATUS_META: Record<PaymentStatus, StatusMeta> = {
  [PaymentStatus.PENDING]: { label: '결제 진행중', variant: 'warning' },
  [PaymentStatus.PAID]: { label: '결제 완료', variant: 'success' },
  [PaymentStatus.OFFLINE_REQUIRED]: {
    label: '현장 수납 필요',
    variant: 'destructive',
  },
  [PaymentStatus.OFFLINE_PAID]: { label: '현장 수납 완료', variant: 'success' },
  [PaymentStatus.REFUNDED]: { label: '환불 완료', variant: 'muted' },
}

// 예약 진행상태 (백엔드 ReservationProgressStatus, PR #68)
export const PROGRESS_STATUS_META: Record<
  ReservationProgressStatus,
  StatusMeta
> = {
  [ReservationProgressStatus.RESERVATION_REQUESTED]: {
    label: '승인 대기',
    variant: 'warning',
  },
  [ReservationProgressStatus.RESERVATION_CONFIRMED]: {
    label: '예약 확정',
    variant: 'default',
  },
  [ReservationProgressStatus.CHECKED_IN]: {
    label: '내원 완료',
    variant: 'secondary',
  },
  [ReservationProgressStatus.IN_TREATMENT]: {
    label: '진료 중',
    variant: 'secondary',
  },
  [ReservationProgressStatus.TREATMENT_COMPLETED]: {
    label: '진료 완료',
    variant: 'secondary',
  },
  [ReservationProgressStatus.PAYMENT_COMPLETED]: {
    label: '결제 완료',
    variant: 'success',
  },
  [ReservationProgressStatus.RESERVATION_REJECTED]: {
    label: '거절됨',
    variant: 'destructive',
  },
  [ReservationProgressStatus.RESERVATION_CANCELED]: {
    label: '취소됨',
    variant: 'muted',
  },
  [ReservationProgressStatus.NO_SHOW]: { label: '노쇼', variant: 'destructive' },
}

// 목록/상세 배지 — 백엔드 progressStatus를 기본으로 쓰되, 진료완료(미결제)는
// paymentStatus로 미수금/결제진행중/청구전을 세분화한다 (SA §5-4, 서버가 안 나누는 부분 보완).
export function resolveReservationBadge(
  progressStatus: ReservationProgressStatus,
  paymentStatus: PaymentStatus | null | undefined,
): StatusMeta {
  if (progressStatus === ReservationProgressStatus.TREATMENT_COMPLETED) {
    if (paymentStatus === PaymentStatus.OFFLINE_REQUIRED) {
      return { label: '미수금(수납 필요)', variant: 'destructive' }
    }
    if (paymentStatus === PaymentStatus.PENDING) {
      return { label: '결제 진행중', variant: 'warning' }
    }
    if (paymentStatus === PaymentStatus.REFUNDED) {
      return { label: '환불 완료', variant: 'muted' }
    }
    return { label: '진료 완료(청구 전)', variant: 'secondary' }
  }
  return PROGRESS_STATUS_META[progressStatus]
}

// 전체 진행상태 (SA §5-4)
export const OVERALL_PROGRESS_META: Record<OverallProgress, StatusMeta> = {
  [OverallProgress.PAYMENT_COMPLETED]: {
    label: '결제완료',
    variant: 'success',
  },
  [OverallProgress.OUTSTANDING]: {
    label: '미수금(수납 필요)',
    variant: 'destructive',
  },
  [OverallProgress.PAYMENT_IN_PROGRESS]: {
    label: '결제 진행중',
    variant: 'warning',
  },
  [OverallProgress.TREATMENT_DONE_UNBILLED]: {
    label: '진료 완료(청구 전)',
    variant: 'secondary',
  },
  [OverallProgress.REFUNDED]: { label: '환불 완료', variant: 'muted' },
}

// 예약·결제 상태를 조합해 전체 진행상태를 계산한다 (SA §5-4).
// 진료 완료 전이면 예약 상태가 곧 진행상태이므로 null을 반환한다.
export function computeOverallProgress(
  reservationStatus: ReservationStatus,
  paymentStatus: PaymentStatus | null | undefined,
): OverallProgress | null {
  if (reservationStatus !== ReservationStatus.TREATMENT_COMPLETED) {
    return null
  }
  if (!paymentStatus) return OverallProgress.TREATMENT_DONE_UNBILLED
  switch (paymentStatus) {
    case PaymentStatus.PAID:
    case PaymentStatus.OFFLINE_PAID:
      return OverallProgress.PAYMENT_COMPLETED
    case PaymentStatus.OFFLINE_REQUIRED:
      return OverallProgress.OUTSTANDING
    case PaymentStatus.PENDING:
      return OverallProgress.PAYMENT_IN_PROGRESS
    case PaymentStatus.REFUNDED:
      return OverallProgress.REFUNDED
    default:
      return OverallProgress.TREATMENT_DONE_UNBILLED
  }
}
