// MSW mock 시드 데이터. 백엔드 미구현 API의 계약 검증·화면 개발용.
import type {
  DateAvailability,
  HospitalSlotLookup,
  HospitalSummary,
  Slot,
  SlotAvailabilityStatus,
} from '@/features/hospitals/types'
import type {
  ReservationDetail,
  ReservationListItem,
} from '@/features/reservations/types'
import type { PaymentRecord } from '@/features/payments/types'
import type { Pet } from '@/features/pets/api'
import type { Notification } from '@/features/notifications/types'

// 검색 페이지네이션·제휴 우선 채움 시연용 추가 병원(id 4~25). 제휴 8 + 비제휴 14.
const EXTRA_HOSPITAL_SEED: Array<[string, boolean]> = [
  ['튼튼동물메디컬', true],
  ['연세동물병원', true],
  ['24시 종합동물병원', true],
  ['우리동네동물병원', true],
  ['그린펫동물병원', true],
  ['해맑은동물병원', true],
  ['미소동물의료센터', true],
  ['든든펫클리닉', true],
  ['봄날동물병원', false],
  ['하나동물병원', false],
  ['펫케어의원', false],
  ['다솜동물병원', false],
  ['라온펫동물병원', false],
  ['가온동물병원', false],
  ['새봄동물병원', false],
  ['참사랑동물병원', false],
  ['필동물병원', false],
  ['온누리동물병원', false],
  ['제일동물병원', false],
  ['행복한동물병원', false],
  ['금빛동물병원', false],
  ['늘푸른동물병원', false],
]
const EXTRA_REGIONS = [
  '서울 강남구',
  '서울 서초구',
  '서울 마포구',
  '경기 성남시 분당구',
  '경기 수원시 영통구',
  '인천 연수구',
]

function extraHospital(seedIndex: number): HospitalSummary {
  const [name, partner] = EXTRA_HOSPITAL_SEED[seedIndex]
  const id = seedIndex + 4
  const region = EXTRA_REGIONS[seedIndex % EXTRA_REGIONS.length]
  return {
    hospitalId: id,
    name,
    address: `${region} 로 ${100 + id}`,
    distanceKm: Math.round((1 + seedIndex * 0.7) * 10) / 10,
    businessStatus: 'OPEN',
    partnershipStatus: partner ? 'PARTNER' : 'NON_PARTNER',
    reservationAvailable: partner,
    partnershipBadge: partner ? null : '제휴 전 병원',
    openNow: partner ? true : null,
    favorite: false,
  }
}

export const mockHospitals: HospitalSummary[] = [
  {
    hospitalId: 1,
    name: '행복동물메디컬센터',
    address: '서울 강남구 테헤란로 123',
    distanceKm: 1.2,
    businessStatus: 'OPEN',
    partnershipStatus: 'PARTNER',
    reservationAvailable: true,
    partnershipBadge: null,
    openNow: true,
    favorite: false,
  },
  {
    hospitalId: 2,
    name: '24시 우리동물병원',
    address: '서울 송파구 올림픽로 200',
    distanceKm: 3.4,
    businessStatus: 'OPEN',
    partnershipStatus: 'PARTNER',
    reservationAvailable: true,
    partnershipBadge: null,
    openNow: true,
    favorite: false,
  },
  {
    hospitalId: 3,
    name: '반려동물사랑의원',
    address: '경기 성남시 분당구 판교로 55',
    distanceKm: 5.0,
    businessStatus: 'OPEN',
    partnershipStatus: 'NON_PARTNER',
    reservationAvailable: false,
    partnershipBadge: '제휴 전 병원',
    openNow: null,
    favorite: false,
  },
  ...EXTRA_HOSPITAL_SEED.map((_, i) => extraHospital(i)),
]

// --- 슬롯 조회 mock (실계약 HospitalSlotLookupResponse 형태) ---
// 제휴 병원만 슬롯을 준다(mockHospitals에서 파생). 비제휴는 빈 lookup.
const PARTNER_HOSPITAL_IDS = mockHospitals
  .filter((h) => h.partnershipStatus === 'PARTNER')
  .map((h) => h.hospitalId)
const MOCK_SLOT_HOURS = [10, 11, 14, 15, 16]
const LOOKUP_DATE_RANGE_DAYS = 14
const UNAVAILABLE_DAY_OFFSET = 3 // 데모용으로 만실 처리할 날(예약 가능일 아님)

// 로컬 yyyy-MM-dd.
function ymd(d: Date): string {
  return d.toLocaleDateString('en-CA')
}

// 백엔드 LocalDateTime 형식("yyyy-MM-ddTHH:mm:ss", 타임존 없음).
function localIso(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(
    d.getHours(),
  )}:${p(d.getMinutes())}:00`
}

// slotId → 소속 병원 역추적 (slotId는 hospitalId*1_000_000 + ... 로 인코딩).
export function hospitalOfSlot(slotId: number): number {
  return Math.floor(slotId / 1_000_000)
}

function buildSlots(hospitalId: number, date: string, fullyReserved: boolean): Slot[] {
  const base = new Date(`${date}T00:00:00`)
  const now = new Date()
  const isToday = date === ymd(now)
  const mmdd = Number(date.replace(/-/g, '').slice(4)) // MMDD
  // 예약된 슬롯을 날짜마다 다른 시간대로 하나씩 둔다(항상 11시가 아니라 자연스럽게).
  const reservedIndex = mmdd % MOCK_SLOT_HOURS.length
  return MOCK_SLOT_HOURS.map((hour, i) => {
    const start = new Date(base)
    start.setHours(hour, 0, 0, 0)
    const end = new Date(start)
    end.setMinutes(30)
    let availabilityStatus: SlotAvailabilityStatus = 'AVAILABLE'
    if (fullyReserved || i === reservedIndex) availabilityStatus = 'RESERVED'
    else if (isToday && hour <= now.getHours() + 1)
      availabilityStatus = 'LEAD_TIME_CLOSED'
    return {
      slotId: hospitalId * 1_000_000 + mmdd * 10 + i,
      startAt: localIso(start),
      endAt: localIso(end),
      availabilityStatus,
    }
  })
}

export function buildSlotLookup(hospitalId: number, date: string): HospitalSlotLookup {
  if (!PARTNER_HOSPITAL_IDS.includes(hospitalId)) {
    return { selectedDate: date, dateAvailabilities: [], slots: [] }
  }
  const today = new Date()
  const days = Array.from({ length: LOOKUP_DATE_RANGE_DAYS }, (_, offset) => {
    const d = new Date(today)
    d.setDate(d.getDate() + offset)
    return { date: ymd(d), forced: offset === UNAVAILABLE_DAY_OFFSET }
  })
  // 백엔드처럼 reservationAvailable을 실제 AVAILABLE 슬롯 유무로 계산(정합성).
  const dateAvailabilities: DateAvailability[] = days.map(({ date: d, forced }) => ({
    date: d,
    reservationAvailable: buildSlots(hospitalId, d, forced).some(
      (s) => s.availabilityStatus === 'AVAILABLE',
    ),
  }))
  const entry = days.find((x) => x.date === date)
  const slots = entry ? buildSlots(hospitalId, date, entry.forced) : []
  return { selectedDate: date, dateAvailabilities, slots }
}

// 예약 시연 시간 — 지금 시각(새벽 등)이 아니라 업무시간(hour:00)에 맞춘 dayOffset일차 슬롯.
function apptStart(dayOffset: number, hour: number): string {
  const d = new Date()
  d.setDate(d.getDate() + dayOffset)
  d.setHours(hour, 0, 0, 0)
  return d.toISOString()
}
function apptEnd(dayOffset: number, hour: number): string {
  return new Date(
    new Date(apptStart(dayOffset, hour)).getTime() + 1_800_000,
  ).toISOString()
}

export const mockReservations: ReservationListItem[] = [
  {
    reservationId: 5001,
    hospitalId: 1,
    hospitalName: '행복동물메디컬센터',
    petId: 1,
    petName: '초코',
    reservedAt: apptStart(1, 14),
    reservationStatus: 'CONFIRMED',
    paymentStatus: null,
    progressStatus: 'RESERVATION_CONFIRMED',
  },
  {
    reservationId: 5002,
    hospitalId: 2,
    hospitalName: '24시 우리동물병원',
    petId: 2,
    petName: '나비',
    reservedAt: apptStart(-2, 10),
    reservationStatus: 'TREATMENT_COMPLETED',
    paymentStatus: 'OFFLINE_REQUIRED',
    progressStatus: 'TREATMENT_COMPLETED',
  },
  // 중간 상태(내원 완료) — 진행 스텝 2단계.
  {
    reservationId: 5003,
    hospitalId: 1,
    hospitalName: '행복동물메디컬센터',
    petId: 1,
    petName: '초코',
    reservedAt: apptStart(0, 10),
    reservationStatus: 'CHECKED_IN',
    paymentStatus: null,
    progressStatus: 'CHECKED_IN',
  },
  // 중간 상태(진료 중) — 진행 스텝 3단계.
  {
    reservationId: 5004,
    hospitalId: 2,
    hospitalName: '24시 우리동물병원',
    petId: 2,
    petName: '나비',
    reservedAt: apptStart(0, 11),
    reservationStatus: 'IN_TREATMENT',
    paymentStatus: null,
    progressStatus: 'IN_TREATMENT',
  },
  // 노쇼 — 진행 스텝 없이 배너만.
  {
    reservationId: 5005,
    hospitalId: 1,
    hospitalName: '행복동물메디컬센터',
    petId: 1,
    petName: '초코',
    reservedAt: apptStart(-3, 15),
    reservationStatus: 'NO_SHOW',
    paymentStatus: null,
    progressStatus: 'NO_SHOW',
  },
  // 진료·빌링키 결제 완료.
  {
    reservationId: 5006,
    hospitalId: 2,
    hospitalName: '24시 우리동물병원',
    petId: 2,
    petName: '나비',
    reservedAt: apptStart(-4, 16),
    reservationStatus: 'TREATMENT_COMPLETED',
    paymentStatus: 'PAID',
    progressStatus: 'PAYMENT_COMPLETED',
  },
  // 진료 후 자동 청구 실패 → 현장 수납 완료(오프라인 정산).
  {
    reservationId: 5007,
    hospitalId: 1,
    hospitalName: '행복동물메디컬센터',
    petId: 1,
    petName: '초코',
    reservedAt: apptStart(-5, 14),
    reservationStatus: 'TREATMENT_COMPLETED',
    paymentStatus: 'OFFLINE_PAID',
    progressStatus: 'PAYMENT_COMPLETED',
  },
]

export const mockReservationDetail: Record<number, ReservationDetail> = {
  5001: {
    reservationId: 5001,
    reservationStatus: 'CONFIRMED',
    paymentStatus: null,
    progressStatus: 'RESERVATION_CONFIRMED',
    hospital: {
      hospitalId: 1,
      name: '행복동물메디컬센터',
      address: '서울 강남구 테헤란로 123',
      phoneNumber: '02-1234-5678',
    },
    petSnapshot: { petId: 1, name: '초코', species: 'DOG' },
    slot: {
      slotId: 101,
      startAt: apptStart(1, 14),
      endAt: apptEnd(1, 14),
    },
    rejectionReason: null,
    createdAt: new Date(Date.now() - 3_600_000).toISOString(),
    updatedAt: new Date(Date.now() - 1_800_000).toISOString(),
  },
  5002: {
    reservationId: 5002,
    reservationStatus: 'TREATMENT_COMPLETED',
    paymentStatus: 'OFFLINE_REQUIRED',
    progressStatus: 'TREATMENT_COMPLETED',
    hospital: {
      hospitalId: 2,
      name: '24시 우리동물병원',
      address: '서울 송파구 올림픽로 200',
      phoneNumber: '02-9876-5432',
    },
    petSnapshot: { petId: 2, name: '나비', species: 'CAT' },
    slot: {
      slotId: 201,
      startAt: apptStart(-2, 10),
      endAt: apptEnd(-2, 10),
    },
    rejectionReason: null,
    createdAt: new Date(Date.now() - 190_000_000).toISOString(),
    updatedAt: new Date(Date.now() - 180_000_000).toISOString(),
  },
  5003: {
    reservationId: 5003,
    reservationStatus: 'CHECKED_IN',
    paymentStatus: null,
    progressStatus: 'CHECKED_IN',
    hospital: {
      hospitalId: 1,
      name: '행복동물메디컬센터',
      address: '서울 강남구 테헤란로 123',
      phoneNumber: '02-1234-5678',
    },
    petSnapshot: { petId: 1, name: '초코', species: 'DOG' },
    slot: {
      slotId: 103,
      startAt: apptStart(0, 10),
      endAt: apptEnd(0, 10),
    },
    rejectionReason: null,
    createdAt: new Date(Date.now() - 90_000_000).toISOString(),
    updatedAt: new Date(Date.now() - 3_600_000).toISOString(),
  },
  5004: {
    reservationId: 5004,
    reservationStatus: 'IN_TREATMENT',
    paymentStatus: null,
    progressStatus: 'IN_TREATMENT',
    hospital: {
      hospitalId: 2,
      name: '24시 우리동물병원',
      address: '서울 송파구 올림픽로 200',
      phoneNumber: '02-9876-5432',
    },
    petSnapshot: { petId: 2, name: '나비', species: 'CAT' },
    slot: {
      slotId: 202,
      startAt: apptStart(0, 11),
      endAt: apptEnd(0, 11),
    },
    rejectionReason: null,
    createdAt: new Date(Date.now() - 100_000_000).toISOString(),
    updatedAt: new Date(Date.now() - 1_800_000).toISOString(),
  },
  5005: {
    reservationId: 5005,
    reservationStatus: 'NO_SHOW',
    paymentStatus: null,
    progressStatus: 'NO_SHOW',
    hospital: {
      hospitalId: 1,
      name: '행복동물메디컬센터',
      address: '서울 강남구 테헤란로 123',
      phoneNumber: '02-1234-5678',
    },
    petSnapshot: { petId: 1, name: '초코', species: 'DOG' },
    slot: {
      slotId: 104,
      startAt: apptStart(-3, 15),
      endAt: apptEnd(-3, 15),
    },
    rejectionReason: null,
    createdAt: new Date(Date.now() - 280_000_000).toISOString(),
    updatedAt: new Date(Date.now() - 255_000_000).toISOString(),
  },
  5006: {
    reservationId: 5006,
    reservationStatus: 'TREATMENT_COMPLETED',
    paymentStatus: 'PAID',
    progressStatus: 'PAYMENT_COMPLETED',
    hospital: {
      hospitalId: 2,
      name: '24시 우리동물병원',
      address: '서울 송파구 올림픽로 200',
      phoneNumber: '02-9876-5432',
    },
    petSnapshot: { petId: 2, name: '나비', species: 'CAT' },
    slot: {
      slotId: 203,
      startAt: apptStart(-4, 16),
      endAt: apptEnd(-4, 16),
    },
    rejectionReason: null,
    createdAt: new Date(Date.now() - 360_000_000).toISOString(),
    updatedAt: new Date(Date.now() - 344_000_000).toISOString(),
  },
  5007: {
    reservationId: 5007,
    reservationStatus: 'TREATMENT_COMPLETED',
    paymentStatus: 'OFFLINE_PAID',
    progressStatus: 'PAYMENT_COMPLETED',
    hospital: {
      hospitalId: 1,
      name: '행복동물메디컬센터',
      address: '서울 강남구 테헤란로 123',
      phoneNumber: '02-1234-5678',
    },
    petSnapshot: { petId: 1, name: '초코', species: 'DOG' },
    slot: {
      slotId: 105,
      startAt: apptStart(-5, 14),
      endAt: apptEnd(-5, 14),
    },
    rejectionReason: null,
    createdAt: new Date(Date.now() - 450_000_000).toISOString(),
    updatedAt: new Date(Date.now() - 430_000_000).toISOString(),
  },
}

// 예약당 결제 내역(배열). 5001은 진료 전이라 결제 없음(빈 배열),
// 5002는 빌링키 자동 청구 실패로 오프라인 수납 대기(OFFLINE_REQUIRED) 1건.
export const mockPaymentByReservation: Record<number, PaymentRecord[]> = {
  5001: [],
  5002: [
    {
      paymentId: 9002,
      reservationId: 5002,
      status: 'OFFLINE_REQUIRED',
      paymentChannel: 'BILLING_KEY',
      amount: 85000,
      cardBrandSnapshot: 'SHINHAN',
      cardLast4Snapshot: '1234',
      createdAt: new Date(Date.now() - 170_000_000).toISOString(),
      paidAt: null,
      failedAt: new Date(Date.now() - 169_500_000).toISOString(),
      offlineSettledAt: null,
      refundedAt: null,
    },
  ],
  // 빌링키 자동 결제 성공.
  5006: [
    {
      paymentId: 9006,
      reservationId: 5006,
      status: 'PAID',
      paymentChannel: 'BILLING_KEY',
      amount: 62000,
      cardBrandSnapshot: 'KB',
      cardLast4Snapshot: '5678',
      createdAt: new Date(Date.now() - 344_000_000).toISOString(),
      paidAt: new Date(Date.now() - 343_800_000).toISOString(),
      failedAt: null,
      offlineSettledAt: null,
      refundedAt: null,
    },
  ],
  // 자동 청구 실패 후 병원 현장 수납 완료(오프라인 정산).
  5007: [
    {
      paymentId: 9007,
      reservationId: 5007,
      status: 'OFFLINE_PAID',
      paymentChannel: 'OFFLINE',
      amount: 73000,
      cardBrandSnapshot: 'SHINHAN',
      cardLast4Snapshot: '1234',
      createdAt: new Date(Date.now() - 430_000_000).toISOString(),
      paidAt: null,
      failedAt: new Date(Date.now() - 429_500_000).toISOString(),
      offlineSettledAt: new Date(Date.now() - 428_000_000).toISOString(),
      refundedAt: null,
    },
  ],
}

export const mockPets: Pet[] = [
  {
    petId: 1,
    name: '초코',
    species: 'DOG',
    age: 3,
    weight: 5.2,
    neutered: true,
    imageUrl: null,
  },
  {
    petId: 2,
    name: '나비',
    species: 'CAT',
    age: 2,
    weight: 3.8,
    neutered: false,
    imageUrl: null,
  },
]

export const mockNotifications: Notification[] = [
  {
    id: 1,
    type: 'RESERVATION_CONFIRMED',
    content: '행복동물메디컬센터 예약이 확정되었습니다.',
    resourceType: 'RESERVATION',
    resourceId: 5001,
    isRead: false,
    readAt: null,
    createdAt: new Date(Date.now() - 1_800_000).toISOString(),
  },
  {
    id: 2,
    type: 'PAYMENT_RESULT',
    content: '진료비 결제에 실패했습니다. 현장 수납이 필요합니다.',
    resourceType: 'PAYMENT',
    resourceId: 9002,
    isRead: false,
    readAt: null,
    createdAt: new Date(Date.now() - 169_000_000).toISOString(),
  },
  {
    id: 3,
    type: 'RESERVATION_WAITLIST_OFFERED',
    content: '예약 대기열 승급 제안이 도착했습니다. 마감 전에 수락해 주세요.',
    resourceType: 'RESERVATION_WAITLIST',
    resourceId: 2,
    isRead: false,
    readAt: null,
    createdAt: new Date(Date.now() - 120_000).toISOString(),
  },
]
