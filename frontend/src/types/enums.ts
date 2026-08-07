// 백엔드 상태·enum 계약 (SA §4·§5). erasableSyntaxOnly 때문에 const 객체 + 유니온으로 표현한다.

// 예약 상태 (SA §5-1). NO_SHOW_PENDING은 예약시각 경과 후 스케줄러가 NO_SHOW로
// 최종 확정하기 전 유예(기본 5분) 상태다.
export const ReservationStatus = {
  REQUESTED: 'REQUESTED',
  CONFIRMED: 'CONFIRMED',
  CHECKED_IN: 'CHECKED_IN',
  IN_TREATMENT: 'IN_TREATMENT',
  TREATMENT_COMPLETED: 'TREATMENT_COMPLETED',
  REJECTED: 'REJECTED',
  CANCELED: 'CANCELED',
  NO_SHOW_PENDING: 'NO_SHOW_PENDING',
  NO_SHOW: 'NO_SHOW',
} as const
export type ReservationStatus =
  (typeof ReservationStatus)[keyof typeof ReservationStatus]

// 결제 상태 (SA §5-2). REFUNDED는 MVP+ 고도화에서 도입(전액 환불, 이슈 #37).
export const PaymentStatus = {
  PENDING: 'PENDING',
  PAID: 'PAID',
  OFFLINE_REQUIRED: 'OFFLINE_REQUIRED',
  OFFLINE_PAID: 'OFFLINE_PAID',
  REFUNDED: 'REFUNDED',
} as const
export type PaymentStatus = (typeof PaymentStatus)[keyof typeof PaymentStatus]

// 결제 채널 (SA §4 payments)
export const PaymentChannel = {
  BILLING_KEY: 'BILLING_KEY',
  OFFLINE: 'OFFLINE',
} as const
export type PaymentChannel = (typeof PaymentChannel)[keyof typeof PaymentChannel]

// 슬롯 상태 (SA §5-3)
export const SlotStatus = {
  OPEN: 'OPEN',
  RESERVED: 'RESERVED',
} as const
export type SlotStatus = (typeof SlotStatus)[keyof typeof SlotStatus]

// 반려동물 종 (SA §4 pet_profiles)
export const PetSpecies = {
  DOG: 'DOG',
  CAT: 'CAT',
} as const
export type PetSpecies = (typeof PetSpecies)[keyof typeof PetSpecies]

// 회원 역할 (SA §6-2). 프론트는 GUARDIAN만 다룬다.
export const MemberRole = {
  GUARDIAN: 'GUARDIAN',
  HOSPITAL_STAFF: 'HOSPITAL_STAFF',
} as const
export type MemberRole = (typeof MemberRole)[keyof typeof MemberRole]

// 병원 제휴 상태 (SA §4 hospitals)
export const PartnershipStatus = {
  PARTNER: 'PARTNER',
  NON_PARTNER: 'NON_PARTNER',
} as const
export type PartnershipStatus =
  (typeof PartnershipStatus)[keyof typeof PartnershipStatus]

// 결제수단 상태 (SA §4 payment_methods)
export const PaymentMethodStatus = {
  ACTIVE: 'ACTIVE',
  EXPIRED: 'EXPIRED',
  DELETED: 'DELETED',
} as const
export type PaymentMethodStatus =
  (typeof PaymentMethodStatus)[keyof typeof PaymentMethodStatus]

// 진료역량 분류·화이트리스트 (SA §4 hospital_capabilities, 13개 값 고정)
export const CapabilityType = {
  SPECIES: 'SPECIES',
  EXAM: 'EXAM',
  TREATMENT: 'TREATMENT',
  EQUIPMENT: 'EQUIPMENT',
} as const
export type CapabilityType =
  (typeof CapabilityType)[keyof typeof CapabilityType]

export const CapabilityValue = {
  DOG: 'DOG',
  CAT: 'CAT',
  BLOOD_TEST: 'BLOOD_TEST',
  XRAY: 'XRAY',
  ULTRASOUND: 'ULTRASOUND',
  ORTHOPEDIC_CARE: 'ORTHOPEDIC_CARE',
  DENTAL_CARE: 'DENTAL_CARE',
  OPHTHALMIC_CARE: 'OPHTHALMIC_CARE',
  REHABILITATION: 'REHABILITATION',
  ONCOLOGY_CARE: 'ONCOLOGY_CARE',
  CT: 'CT',
  MRI: 'MRI',
  ENDOSCOPE: 'ENDOSCOPE',
} as const
export type CapabilityValue =
  (typeof CapabilityValue)[keyof typeof CapabilityValue]

// 알림 타입 (SA §4 notifications). "등"이므로 열려 있다.
export type NotificationType =
  | 'RESERVATION_CONFIRMED'
  | 'REJECTED'
  | 'PAYMENT_RESULT'
  | 'NO_SHOW'
  | (string & {})

// 예약 진행 상태 — 백엔드가 예약+결제 조합으로 계산해 내려주는 단일 상태
// (ReservationProgressStatus, PR #68). TREATMENT_COMPLETED + 결제완료면 PAYMENT_COMPLETED.
export const ReservationProgressStatus = {
  RESERVATION_REQUESTED: 'RESERVATION_REQUESTED',
  RESERVATION_CONFIRMED: 'RESERVATION_CONFIRMED',
  CHECKED_IN: 'CHECKED_IN',
  IN_TREATMENT: 'IN_TREATMENT',
  TREATMENT_COMPLETED: 'TREATMENT_COMPLETED',
  PAYMENT_COMPLETED: 'PAYMENT_COMPLETED',
  RESERVATION_REJECTED: 'RESERVATION_REJECTED',
  RESERVATION_CANCELED: 'RESERVATION_CANCELED',
  NO_SHOW_PENDING: 'NO_SHOW_PENDING',
  NO_SHOW: 'NO_SHOW',
} as const
export type ReservationProgressStatus =
  (typeof ReservationProgressStatus)[keyof typeof ReservationProgressStatus]

// 전체 진행 상태 (SA §5-4, 예약+결제 조합으로 계산되는 표시용 상태)
export const OverallProgress = {
  PAYMENT_COMPLETED: 'PAYMENT_COMPLETED', // 결제완료
  OUTSTANDING: 'OUTSTANDING', // 미수금(수납 필요)
  PAYMENT_IN_PROGRESS: 'PAYMENT_IN_PROGRESS', // 결제 진행중
  TREATMENT_DONE_UNBILLED: 'TREATMENT_DONE_UNBILLED', // 진료 완료(청구 전)
  REFUNDED: 'REFUNDED', // 환불 완료(MVP+ #37)
} as const
export type OverallProgress =
  (typeof OverallProgress)[keyof typeof OverallProgress]
