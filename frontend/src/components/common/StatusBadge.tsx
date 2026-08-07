// 예약·결제·전체진행 상태를 한국어 라벨 배지로 렌더링한다.
import { Badge } from '@/components/ui/badge'
import {
  OVERALL_PROGRESS_META,
  PAYMENT_STATUS_META,
  RESERVATION_STATUS_META,
  resolveReservationBadge,
} from '@/lib/status'
import type {
  OverallProgress,
  PaymentStatus,
  ReservationProgressStatus,
  ReservationStatus,
} from '@/types/enums'

export function ReservationStatusBadge({
  status,
}: {
  status: ReservationStatus
}) {
  const meta = RESERVATION_STATUS_META[status]
  return <Badge variant={meta.variant}>{meta.label}</Badge>
}

export function PaymentStatusBadge({ status }: { status: PaymentStatus }) {
  const meta = PAYMENT_STATUS_META[status]
  return <Badge variant={meta.variant}>{meta.label}</Badge>
}

export function OverallProgressBadge({
  progress,
}: {
  progress: OverallProgress
}) {
  const meta = OVERALL_PROGRESS_META[progress]
  return <Badge variant={meta.variant}>{meta.label}</Badge>
}

// 백엔드 progressStatus(+paymentStatus 세분화) 기반 예약 진행 배지.
export function ReservationProgressBadge({
  progressStatus,
  paymentStatus,
}: {
  progressStatus: ReservationProgressStatus
  paymentStatus: PaymentStatus | null | undefined
}) {
  const meta = resolveReservationBadge(progressStatus, paymentStatus)
  return <Badge variant={meta.variant}>{meta.label}</Badge>
}
