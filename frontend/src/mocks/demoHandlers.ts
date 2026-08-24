// 데모 목 핸들러 — 백엔드 없이 전체 UI를 체험하기 위해 "실연동" 엔드포인트까지 mock 한다.
// VITE_FULL_MOCK=true (npm run dev:mock)일 때만 browser.ts가 이 핸들러를 얹는다.
// 기본 개발(npm run dev)에서는 얹지 않으므로 실연동은 그대로 백엔드로 간다.
import { http, HttpResponse, type HttpResponseResolver } from 'msw'
import {
  hospitalOfSlot,
  mockHospitals,
  mockNotifications,
  mockPaymentByReservation,
  mockReservationDetail,
  mockReservations,
} from './data'
import type {
  HospitalDetail,
  HospitalSummary,
} from '@/features/hospitals/types'
import type { PaymentMethod, Receipt } from '@/features/payments/types'
import type { Pet } from '@/features/pets/api'
import { shiftDateKey, todaySeoulKey } from '@/lib/seoulTime'

const BASE = '/api'
const SIGNUP_PHONE_PATTERN = /^01(?:0|1|[6-9])(?:-\d{3,4}-\d{4}|\d{3,4}\d{4})$/

// 영수증을 제공하는 결제 상태(백엔드와 동일, PR #158).
const RECEIPT_STATUSES = new Set(['PAID', 'OFFLINE_PAID', 'REFUNDED'])

// 상세를 따로 정의하지 않은 병원(4~25)은 검색 요약에서 최소 상세를 합성한다(클릭 404 방지).
function synthDetail(h: HospitalSummary): HospitalDetail {
  const partner = h.partnershipStatus === 'PARTNER'
  return {
    hospitalId: h.hospitalId,
    name: h.name,
    address: h.address,
    phoneNumber: partner ? '02-000-0000' : null,
    businessStatus: h.businessStatus,
    partnershipStatus: h.partnershipStatus,
    partnershipNotice: partner
      ? null
      : '아직 제휴 전 병원이라 온라인 예약을 지원하지 않습니다.',
    openNow: partner ? true : null,
    surgeryAvailable: partner ? true : null,
    hospitalizationAvailable: partner ? false : null,
    nightCare: partner ? false : null,
    emergency: partner ? false : null,
    businessHours: partner
      ? [
          { dayOfWeek: 'MONDAY', closed: false, openTime: '09:00', closeTime: '18:00' },
          { dayOfWeek: 'TUESDAY', closed: false, openTime: '09:00', closeTime: '18:00' },
          { dayOfWeek: 'WEDNESDAY', closed: false, openTime: '09:00', closeTime: '18:00' },
          { dayOfWeek: 'THURSDAY', closed: false, openTime: '09:00', closeTime: '18:00' },
          { dayOfWeek: 'FRIDAY', closed: false, openTime: '09:00', closeTime: '18:00' },
          { dayOfWeek: 'SATURDAY', closed: false, openTime: '10:00', closeTime: '14:00' },
          { dayOfWeek: 'SUNDAY', closed: true, openTime: null, closeTime: null },
        ]
      : null,
    capabilities: partner ? ['DOG', 'CAT', 'XRAY'] : null,
    reservationResponseRate: partner ? 92 : null,
    averageApprovalMinutes: partner ? 24 : null,
    averageRating: null,
    reviewCount: 0,
    favorite: false,
  }
}

function ok<T>(data: T, status = 200) {
  return HttpResponse.json(
    { code: 'SUCCESS', message: '요청이 성공했습니다.', data },
    { status },
  )
}

function fail(code: string, message: string, status: number) {
  return HttpResponse.json({ code, message, data: null }, { status })
}

// 제휴 병원 상세(1·2) + 비제휴(3). 검색 목 데이터와 id를 맞춘다.
const hospitalDetails: Record<number, HospitalDetail> = {
  1: {
    hospitalId: 1,
    name: '행복동물메디컬센터',
    address: '서울 강남구 테헤란로 123',
    phoneNumber: '02-1234-5678',
    businessStatus: 'OPEN',
    partnershipStatus: 'PARTNER',
    partnershipNotice: null,
    openNow: true,
    surgeryAvailable: true,
    hospitalizationAvailable: true,
    nightCare: false,
    emergency: false,
    businessHours: [
      { dayOfWeek: 'MONDAY', closed: false, openTime: '09:00', closeTime: '18:00' },
      { dayOfWeek: 'TUESDAY', closed: false, openTime: '09:00', closeTime: '18:00' },
      { dayOfWeek: 'WEDNESDAY', closed: false, openTime: '09:00', closeTime: '18:00' },
      { dayOfWeek: 'THURSDAY', closed: false, openTime: '09:00', closeTime: '18:00' },
      { dayOfWeek: 'FRIDAY', closed: false, openTime: '09:00', closeTime: '18:00' },
      { dayOfWeek: 'SATURDAY', closed: false, openTime: '10:00', closeTime: '14:00' },
      { dayOfWeek: 'SUNDAY', closed: true, openTime: null, closeTime: null },
    ],
    capabilities: ['DOG', 'CAT', 'XRAY', 'ULTRASOUND', 'DENTAL_CARE'],
    reservationResponseRate: 96,
    averageApprovalMinutes: 18,
    averageRating: 4.5,
    reviewCount: 2,
    favorite: false,
  },
  2: {
    hospitalId: 2,
    name: '24시 우리동물병원',
    address: '서울 송파구 올림픽로 200',
    phoneNumber: '02-9876-5432',
    businessStatus: 'OPEN',
    partnershipStatus: 'PARTNER',
    partnershipNotice: null,
    openNow: true,
    surgeryAvailable: true,
    hospitalizationAvailable: true,
    nightCare: true,
    emergency: true,
    businessHours: [
      { dayOfWeek: 'MONDAY', closed: false, openTime: '00:00', closeTime: '23:59' },
      { dayOfWeek: 'TUESDAY', closed: false, openTime: '00:00', closeTime: '23:59' },
      { dayOfWeek: 'WEDNESDAY', closed: false, openTime: '00:00', closeTime: '23:59' },
      { dayOfWeek: 'THURSDAY', closed: false, openTime: '00:00', closeTime: '23:59' },
      { dayOfWeek: 'FRIDAY', closed: false, openTime: '00:00', closeTime: '23:59' },
      { dayOfWeek: 'SATURDAY', closed: false, openTime: '00:00', closeTime: '23:59' },
      { dayOfWeek: 'SUNDAY', closed: false, openTime: '00:00', closeTime: '23:59' },
    ],
    capabilities: ['DOG', 'CAT', 'BLOOD_TEST', 'CT', 'ONCOLOGY_CARE'],
    reservationResponseRate: 88,
    averageApprovalMinutes: 31,
    averageRating: null,
    reviewCount: 0,
    favorite: false,
  },
  3: {
    hospitalId: 3,
    name: '반려동물사랑의원',
    address: '경기 성남시 분당구 판교로 55',
    phoneNumber: null,
    businessStatus: 'OPEN',
    partnershipStatus: 'NON_PARTNER',
    partnershipNotice: '아직 제휴 전 병원이라 온라인 예약을 지원하지 않습니다.',
    openNow: null,
    surgeryAvailable: null,
    hospitalizationAvailable: null,
    nightCare: null,
    emergency: null,
    businessHours: null,
    capabilities: null,
    reservationResponseRate: null,
    averageApprovalMinutes: null,
    averageRating: null,
    reviewCount: 0,
    favorite: false,
  },
}

// 회원 in-memory 저장소 (dev:mock 오프라인 전용).
let demoMember = {
  memberId: 1,
  email: 'guardian@doctorpet.dev',
  nickname: '김보호',
  role: 'GUARDIAN' as const,
  hospitalId: null as number | null,
}

// 펫 in-memory 저장소 (dev:mock 오프라인 전용).
const demoPets: Pet[] = [
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

// 결제수단 in-memory 저장소.
// 카카오페이 등 간편결제 빌링키는 cardBrand·cardLast4가 비어 올 수 있어(실서버가 그렇게 준다)
// 화면에 전부 "카드 ****"로 똑같이 뜬다 — 여러 개 등록 시 구분이 안 되는 실제 상황을 시드로 재현한다.
let demoMethods: PaymentMethod[] = [
  {
    id: 1,
    cardBrand: 'SHINHAN',
    cardLast4: '1234',
    status: 'ACTIVE',
    // 회원별 활성 기본 결제수단은 최대 1건(PR #152). 첫 수단을 기본으로 시드한다.
    isDefault: true,
    createdAt: new Date(Date.now() - 3 * 86_400_000).toISOString(),
  },
  {
    id: 2,
    cardBrand: null,
    cardLast4: null,
    status: 'ACTIVE',
    isDefault: false,
    createdAt: new Date(Date.now() - 2 * 86_400_000).toISOString(),
  },
  {
    id: 3,
    cardBrand: null,
    cardLast4: null,
    status: 'ACTIVE',
    isDefault: false,
    createdAt: new Date(Date.now() - 86_400_000).toISOString(),
  },
]
let methodSeq = 4
let reservationSeq = 6000

// 병원 후기 in-memory 저장소 (dev:mock 오프라인 전용). 병원 상세의 평균 평점·후기 수는
// 이 데이터에서 계산한다(withReviewAggregate) — 고정값을 두면 작성·삭제 뒤 목록과 상단 집계가
// 어긋나 평점 무효화 흐름을 mock으로 확인할 수 없다(PR #189 리뷰 P2).
const demoReviews = [
  {
    reviewId: 1,
    reservationId: 9001,
    hospitalId: 1,
    memberId: 2,
    rating: 5,
    content: '대기 없이 바로 봐주셨고 설명이 아주 자세했어요. 아이도 편안해 보였습니다.',
    createdAt: new Date(Date.now() - 3 * 86_400_000).toISOString(),
    updatedAt: new Date(Date.now() - 3 * 86_400_000).toISOString(),
  },
  {
    reviewId: 2,
    reservationId: 9002,
    hospitalId: 1,
    memberId: 3,
    rating: 4,
    content: '진료는 만족스러웠지만 주차가 조금 불편했어요.',
    createdAt: new Date(Date.now() - 10 * 86_400_000).toISOString(),
    updatedAt: new Date(Date.now() - 9 * 86_400_000).toISOString(),
  },
]
let reviewSeq = 3
// 예약의 후기 작성 기회(reservations.reviewed_at)를 흉내 낸다. 후기를 지워도 여기서는 빠지지
// 않는다 — 작성 기회는 복구되지 않아 재작성이 항상 409다(SA §8-3).
const demoReviewedReservations = new Set<number>([9001, 9002])

// 병원 상세 응답의 후기 집계. 백엔드는 avg(rating)을 소수 1자리 HALF_UP으로 반올림하고,
// 후기가 없으면 averageRating이 null이다(ReviewQueryService).
function withReviewAggregate(detail: HospitalDetail): HospitalDetail {
  const ratings = demoReviews
    .filter((r) => r.hospitalId === detail.hospitalId)
    .map((r) => r.rating)
  if (ratings.length === 0) {
    return { ...detail, averageRating: null, reviewCount: 0 }
  }
  const sum = ratings.reduce((acc, rating) => acc + rating, 0)
  return {
    ...detail,
    averageRating: Math.round((sum / ratings.length) * 10) / 10,
    reviewCount: ratings.length,
  }
}

/*
  찜 in-memory 저장소 (dev:mock 오프라인 전용). 검색 목록은 공용 handlers.ts가 mockHospitals를
  그대로 내려주므로, 토글 때 그 배열의 favorite도 함께 갈아끼워 검색·상세·찜목록 세 화면의
  하트가 어긋나지 않게 한다.
*/
const demoFavorites = new Set<number>([2])
mockHospitals.forEach((h) => {
  h.favorite = demoFavorites.has(h.hospitalId)
})

function withFavorite(detail: HospitalDetail): HospitalDetail {
  return { ...detail, favorite: demoFavorites.has(detail.hospitalId) }
}

// --- 병원 스태프 운영(진료시간·진료역량·임시휴진) in-memory 저장소 ---
// 백엔드 규칙과 같은 날짜 경계를 쓴다: 발효일·휴진일은 모두 "오늘 다음 날부터"만 허용한다.
// 날짜 경계는 백엔드와 같은 Asia/Seoul 기준이어야 한다 — toISOString(UTC)으로 자르면 하루가
// 밀려 "오늘은 등록 불가" 같은 판정이 목에서만 다르게 나온다.
function mockDateKey(offsetDays: number): string {
  return shiftDateKey(todaySeoulKey(), offsetDays)
}

const demoOperatingHours = {
  scheduleId: 1,
  updatedAt: '2026-01-01T00:00:00',
  effectiveFrom: mockDateKey(-30),
  days: [
    { dayOfWeek: 'MONDAY', periods: [{ startTime: '09:00', endTime: '18:00' }] },
    { dayOfWeek: 'TUESDAY', periods: [{ startTime: '09:00', endTime: '18:00' }] },
    {
      dayOfWeek: 'WEDNESDAY',
      periods: [
        { startTime: '09:00', endTime: '13:00' },
        { startTime: '14:00', endTime: '18:00' },
      ],
    },
    { dayOfWeek: 'THURSDAY', periods: [{ startTime: '09:00', endTime: '18:00' }] },
    { dayOfWeek: 'FRIDAY', periods: [{ startTime: '09:00', endTime: '18:00' }] },
    { dayOfWeek: 'SATURDAY', periods: [{ startTime: '10:00', endTime: '14:00' }] },
    { dayOfWeek: 'SUNDAY', periods: [] },
  ] as { dayOfWeek: string; periods: { startTime: string; endTime: string }[] }[],
}
let demoScheduledOperatingHours: typeof demoOperatingHours[] = []
let demoOperatingHoursSequence = 2

let demoCapabilities: string[] = ['DOG', 'CAT', 'XRAY']

// 등록된 임시휴진 날짜. 목록 조회 API가 없으므로 목도 조회 경로를 두지 않는다.
const demoClosures = new Set<string>()
// 예약이 있어 휴진 등록이 거부되는 날짜(HOSPITAL_008 흐름 확인용).
const demoReservedClosureDate = mockDateKey(3)

// 예약 대기열 in-memory 저장소 (dev:mock 오프라인 전용). WAITING·OFFERED·종료 상태를 한 건씩 시드.
// slotId(501~503)는 표시용 가짜 값이라 hospitalOfSlot() 인코딩 규칙(hospitalId*1_000_000+…)을
// 따르지 않는다. 응답 계약엔 hospitalId가 없어 여기 얹지 않고, 수락 시 병원을 역산할 별도
// 조회 테이블을 둔다(PR #188 리뷰: hospitalOfSlot(501)=0이 되어 병원명이 플레이스홀더로 깨지던 문제).
const demoWaitlists = [
  {
    waitlistId: 1,
    slotId: 501,
    status: 'WAITING' as string,
    requestedAt: new Date(Date.now() - 3_600_000).toISOString(),
    offeredAt: null as string | null,
    offerExpiresAt: null as string | null,
    respondedAt: null as string | null,
    canceledAt: null as string | null,
  },
  {
    waitlistId: 2,
    slotId: 502,
    status: 'OFFERED' as string,
    requestedAt: new Date(Date.now() - 7_200_000).toISOString(),
    offeredAt: new Date(Date.now() - 60_000).toISOString(),
    offerExpiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
    respondedAt: null as string | null,
    canceledAt: null as string | null,
  },
  {
    waitlistId: 3,
    slotId: 503,
    status: 'EXPIRED' as string,
    requestedAt: new Date(Date.now() - 172_800_000).toISOString(),
    offeredAt: new Date(Date.now() - 86_400_000).toISOString(),
    offerExpiresAt: new Date(Date.now() - 86_000_000).toISOString(),
    respondedAt: null as string | null,
    canceledAt: null as string | null,
  },
]
let waitlistSeq = 4
// waitlistId → hospitalId. 시드 501/502/503은 위 이유로 slotId에서 역산할 수 없어 직접 매핑하고,
// 실제 플로우로 새로 생성되는 항목은 register 핸들러가 hospitalOfSlot(slotId)로 채워 넣는다.
const demoWaitlistHospitalId: Record<number, number> = { 1: 1, 2: 2, 3: 1 }

// 보호자(GET /payments/:id/receipt)와 스태프(GET /hospital/payments/:id/receipt)는 동일한 영수증
// 데이터를 쓴다 — 조회 권한(엔드포인트)만 다르므로 같은 빌더를 두 경로에 등록한다(PR #180 리뷰).
// 한쪽만 등록하면 dev:mock에서 스태프 영수증 버튼이 매칭 핸들러가 없어 네트워크 오류가 난다.
// 백엔드는 "결제가 없다"(PAYMENT_005 404)와 "발급 불가 상태다"(PAYMENT_013 409)를 구분하므로
// (PaymentReceiptService) 목도 그대로 나눈다 — 한쪽으로 뭉개면 검증한 오류 처리가 실연동과 달라진다.
const receiptResolver: HttpResponseResolver<{ paymentId: string }> = ({ params }) => {
  const paymentId = Number(params.paymentId)
  const record = Object.values(mockPaymentByReservation)
    .flat()
    .find((p) => p.paymentId === paymentId)
  if (!record) {
    return fail('PAYMENT_005', '결제 정보를 찾을 수 없습니다.', 404)
  }
  if (!RECEIPT_STATUSES.has(record.status)) {
    return fail('PAYMENT_013', '영수증을 발급할 수 있는 결제가 아닙니다.', 409)
  }
  const detail = mockReservationDetail[record.reservationId]
  const receipt: Receipt = {
    paymentId: record.paymentId,
    reservationId: record.reservationId,
    hospitalId: detail?.hospital.hospitalId ?? 1,
    guardianMemberId: 1,
    petId: detail?.petSnapshot.petId ?? 1,
    petName: detail?.petSnapshot.name ?? '초코',
    petSpecies: detail?.petSnapshot.species ?? 'DOG',
    status: record.status,
    paymentChannel: record.paymentChannel,
    paidAt: record.paidAt,
    offlineSettledAt: record.offlineSettledAt,
    cardBrandSnapshot: record.cardBrandSnapshot,
    cardLast4Snapshot: record.cardLast4Snapshot,
    // 데모용 항목 — 진찰료 + 검사비로 총액을 구성한다(항목 표 시연).
    items: [
      { name: '진찰료', quantity: 1, unitPrice: 15000, amount: 15000 },
      {
        name: '검사·처치',
        quantity: 1,
        unitPrice: record.amount - 15000,
        amount: record.amount - 15000,
      },
    ],
    totalAmount: record.amount,
    refundStatus: record.refundedAt ? 'COMPLETED' : null,
    refundedAt: record.refundedAt,
  }
  return ok(receipt)
}

export const demoHandlers = [
  // --- 인증 ---
  http.post(`${BASE}/auth/signup`, async ({ request }) => {
    const body = (await request.json()) as { phone?: unknown }
    const phone = body.phone
    if (typeof phone !== 'string' || phone.trim().length === 0) {
      return fail('COMMON_001', '입력값이 올바르지 않습니다.', 400)
    }
    if (!SIGNUP_PHONE_PATTERN.test(phone)) {
      return fail('COMMON_001', '입력값이 올바르지 않습니다.', 400)
    }
    return ok({ memberId: 1 }, 201)
  }),
  http.post(`${BASE}/auth/login`, async ({ request }) => {
    const body = (await request.json()) as { email?: string }
    // 데모: 이 이메일은 미인증 계정으로 403(EMAIL_NOT_VERIFIED) UX를 재현한다.
    if (body.email === 'unverified@doctorpet.dev') {
      return fail(
        'MEMBER_009',
        '이메일 인증이 필요합니다. 가입 시 발송된 메일의 링크를 확인해주세요.',
        403,
      )
    }
    return ok({
      accessToken: 'demo-access-token',
      refreshToken: 'demo-refresh-token',
    })
  }),
  http.post(`${BASE}/auth/reissue`, () =>
    ok({ accessToken: 'demo-access-token-2', refreshToken: 'demo-refresh-token-2' }),
  ),
  http.post(`${BASE}/auth/logout`, () => ok(null)),
  // 비밀번호 재설정 — 요청은 항상 200(계정 노출 방지). 확인은 token 유효성만 흉내.
  http.post(`${BASE}/auth/password-reset/request`, () => ok(null)),
  http.post(`${BASE}/auth/password-reset/confirm`, async ({ request }) => {
    const body = (await request.json()) as { token?: string }
    // 데모에서 'expired' 토큰은 만료 에러(MEMBER_010)를 재현한다.
    if (!body.token || body.token === 'expired') {
      return fail('MEMBER_010', '유효하지 않거나 만료된 링크입니다. 다시 요청해주세요.', 400)
    }
    return ok(null)
  }),
  // 이메일 인증 — token 유효성만 흉내(만료/누락은 400). 재발송은 항상 200.
  http.get(`${BASE}/auth/verify-email`, ({ request }) => {
    const token = new URL(request.url).searchParams.get('token')
    if (!token || token === 'expired') {
      return fail('MEMBER_010', '유효하지 않거나 만료된 링크입니다. 다시 요청해주세요.', 400)
    }
    return ok(null)
  }),
  http.post(`${BASE}/auth/verify-email/resend`, () => ok(null)),
  // AI 증상 상담 (실엔드포인트지만 dev:mock 오프라인용). 증상 키워드로 응급도를 흉내낸다.
  http.post(`${BASE}/ai/consultations`, async ({ request }) => {
    const body = (await request.json()) as {
      symptomText?: string
      latitude?: number
    }
    const text = body.symptomText ?? ''
    const emergency = /응급|피|경련|호흡|쓰러|의식|중독|발작/.test(text)
    const urgencyLevel = emergency
      ? 'HIGH'
      : text.length > 40
        ? 'MODERATE'
        : 'LOW'
    const partners = mockHospitals.filter(
      (h) => h.partnershipStatus === 'PARTNER',
    )
    return ok({
      structured: {
        possibleFocusAreas: emergency ? ['호흡기', '순환기'] : ['소화기'],
        requiredCapabilities: emergency ? ['XRAY', 'BLOOD_TEST'] : ['BLOOD_TEST'],
        urgencyLevel,
        preVisitCheckpoints: [
          '마지막 식사 시간',
          '구토·설사 횟수',
          '평소와 다른 행동',
        ],
        recommendVetVisit: urgencyLevel !== 'LOW',
      },
      hospitals: partners,
      disclaimer: '본 정보는 참고용이며 수의사의 진료를 대체하지 않습니다.',
      message: emergency
        ? '응급 가능성이 있어요. 가까운 병원에 바로 연락해 주세요.'
        : '아래 분석과 병원을 참고해 주세요.',
      fallback: false,
      locationRecommended: body.latitude == null,
    })
  }),
  http.get(`${BASE}/members/me`, () => ok(demoMember)),

  // --- 알림 (실계약: NotificationPageResponse, page 0-base) ---
  http.get(`${BASE}/notifications`, ({ request }) => {
    const url = new URL(request.url)
    const isReadParam = url.searchParams.get('isRead')
    const page = Number(url.searchParams.get('page') ?? '0')
    const size = Number(url.searchParams.get('size') ?? '20')
    let filtered = mockNotifications
    if (isReadParam != null) {
      filtered = filtered.filter((n) => n.isRead === (isReadParam === 'true'))
    }
    const totalElements = filtered.length
    const totalPages = Math.max(1, Math.ceil(totalElements / size))
    const content = filtered.slice(page * size, page * size + size)
    return ok({
      content,
      page,
      size,
      totalElements,
      totalPages,
      first: page === 0,
      last: page >= totalPages - 1,
    })
  }),
  // 미읽음 개수 — 목록 페이지 크기와 무관하게 전체 기준으로 센다(실 백엔드 count 쿼리와 같은 의미).
  http.get(`${BASE}/notifications/unread-count`, () =>
    ok({ unreadCount: mockNotifications.filter((n) => !n.isRead).length }),
  ),
  // 모두 읽음 — 미읽음만 갱신하고 갱신 건수를 돌려준다(멱등: 두 번째 호출은 0건).
  http.patch(`${BASE}/notifications/read-all`, () => {
    const unread = mockNotifications.filter((n) => !n.isRead)
    const readAt = new Date().toISOString()
    unread.forEach((n) => {
      n.isRead = true
      n.readAt = readAt
    })
    return ok({ updatedCount: unread.length })
  }),
  // 전체 삭제 — 하드 삭제라 목록을 비우고 삭제 건수를 돌려준다(멱등: 두 번째 호출은 0건).
  http.delete(`${BASE}/notifications`, () => {
    const deletedCount = mockNotifications.length
    mockNotifications.splice(0, mockNotifications.length)
    return ok({ deletedCount })
  }),
  http.patch(`${BASE}/notifications/:notificationId/read`, ({ params }) => {
    const target = mockNotifications.find(
      (n) => n.id === Number(params.notificationId),
    )
    if (target) {
      target.isRead = true
      target.readAt = new Date().toISOString()
    }
    return ok(null)
  }),
  // 닉네임 수정 (PATCH /members/me).
  http.patch(`${BASE}/members/me`, async ({ request }) => {
    const body = (await request.json()) as { nickname?: string }
    const nickname = (body.nickname ?? '').trim()
    if (!nickname || nickname.length > 255) {
      return fail('COMMON_001', '입력값이 올바르지 않습니다.', 400)
    }
    demoMember = { ...demoMember, nickname }
    return ok(demoMember)
  }),
  // 회원 탈퇴 (DELETE /members/me). 데모에서는 항상 성공(Void).
  http.delete(`${BASE}/members/me`, () => ok(null)),

  // --- 병원 상세 ---
  http.get(`${BASE}/hospitals/:hospitalId`, ({ params }) => {
    const id = Number(params.hospitalId)
    const detail = hospitalDetails[id]
    if (detail) return ok(withFavorite(withReviewAggregate(detail)))
    const summary = mockHospitals.find((h) => h.hospitalId === id)
    if (summary) return ok(withFavorite(withReviewAggregate(synthDetail(summary))))
    return fail('HOSPITAL_001', '병원 정보를 찾을 수 없습니다.', 404)
  }),

  // --- 찜(관심 병원) ---
  // 추가는 멱등이다(이미 찜한 병원에 다시 PUT해도 200).
  http.put(`${BASE}/hospitals/:hospitalId/favorite`, ({ params }) => {
    const id = Number(params.hospitalId)
    const known =
      hospitalDetails[id] || mockHospitals.some((h) => h.hospitalId === id)
    if (!known) return fail('HOSPITAL_001', '병원 정보를 찾을 수 없습니다.', 404)
    demoFavorites.add(id)
    const summary = mockHospitals.find((h) => h.hospitalId === id)
    if (summary) summary.favorite = true
    return ok(null)
  }),
  // 해제는 204 + 본문 없음(백엔드와 동일).
  http.delete(`${BASE}/hospitals/:hospitalId/favorite`, ({ params }) => {
    const id = Number(params.hospitalId)
    demoFavorites.delete(id)
    const summary = mockHospitals.find((h) => h.hospitalId === id)
    if (summary) summary.favorite = false
    return new HttpResponse(null, { status: 204 })
  }),
  // 내 찜 목록 — page는 1-base(검색과 같은 규약).
  http.get(`${BASE}/members/me/favorite-hospitals`, ({ request }) => {
    const url = new URL(request.url)
    const page = Number(url.searchParams.get('page') ?? '1')
    const size = Number(url.searchParams.get('size') ?? '20')
    const all = [...demoFavorites].map((id) => {
      const detail = hospitalDetails[id]
      const summary = mockHospitals.find((h) => h.hospitalId === id)
      return {
        hospitalId: id,
        name: detail?.name ?? summary?.name ?? `병원 ${id}`,
        address: detail?.address ?? summary?.address ?? '주소 미확인',
        businessStatus: detail?.businessStatus ?? summary?.businessStatus ?? 'OPEN',
        partnershipStatus:
          detail?.partnershipStatus ?? summary?.partnershipStatus ?? 'NON_PARTNER',
        favorite: true,
        // 백엔드는 오프셋 없는 LocalDateTime을 준다 — 목도 같은 모양으로 맞춘다(Z 붙은 ISO 아님).
        favoritedAt: new Date().toISOString().slice(0, 19),
      }
    })
    const totalElements = all.length
    const totalPages = Math.max(1, Math.ceil(totalElements / size))
    return ok({
      content: all.slice((page - 1) * size, page * size),
      page,
      size,
      totalElements,
      totalPages,
      first: page === 1,
      last: page >= totalPages,
    })
  }),

  // 병원 스태프 회원 진료·결제 이력 — 예약으로 회원을 해석해 자병원 이력만 페이지로 (SA §8-6).
  http.get(`${BASE}/hospital/reservations/:reservationId/member-history`, ({ request }) => {
    const url = new URL(request.url)
    const page = Number(url.searchParams.get('page') ?? '0')
    const size = Number(url.searchParams.get('size') ?? '20')
    const all = [
      {
        reservationId: 9101,
        reservedAt: `${shiftDateKey(todaySeoulKey(), -2)}T10:00:00`,
        petName: '초코',
        petSpecies: 'DOG',
        reservationStatus: 'TREATMENT_COMPLETED',
        paymentId: 8801,
        paymentStatus: 'PAID',
        amount: 45000,
      },
      {
        reservationId: 9102,
        reservedAt: `${shiftDateKey(todaySeoulKey(), -20)}T14:00:00`,
        petName: '초코',
        petSpecies: 'DOG',
        reservationStatus: 'CANCELED',
        paymentId: null,
        paymentStatus: null,
        amount: null,
      },
    ]
    const totalElements = all.length
    const totalPages = Math.max(1, Math.ceil(totalElements / size))
    return ok({
      content: all.slice(page * size, page * size + size),
      page,
      size,
      totalElements,
      totalPages,
      first: page === 0,
      last: page >= totalPages - 1,
    })
  }),

  // --- 병원 스태프 운영: 진료시간 (현재·예정 GET, PUT /hospital/operating-hours) ---
  http.get(`${BASE}/hospital/operating-hours`, () => ok(demoOperatingHours)),
  http.get(`${BASE}/hospital/operating-hours/scheduled`, () =>
    ok(demoScheduledOperatingHours),
  ),
  http.put(`${BASE}/hospital/operating-hours`, async ({ request }) => {
    const body = (await request.json()) as {
      desiredEffectiveFrom: string
      saveMode: 'CREATE' | 'UPDATE'
      targetScheduleId?: number
      expectedUpdatedAt?: string
      days: { dayOfWeek: string; periods: { startTime: string; endTime: string }[] }[]
    }
    if (body.desiredEffectiveFrom <= mockDateKey(0)) {
      return fail(
        'HOSPITAL_005',
        '진료시간은 요청일 다음 날부터 적용할 수 있습니다.',
        400,
      )
    }
    if (body.days?.length !== 7) {
      return fail('HOSPITAL_006', '요일별 진료시간이 올바르지 않습니다.', 400)
    }
    // 시작·종료가 같은 구간만 목에서 거른다(겹침 검사는 화면이 먼저 막는다).
    const invalid = body.days.some((day) =>
      day.periods.some((period) => period.startTime === period.endTime),
    )
    if (invalid) {
      return fail('HOSPITAL_006', '요일별 진료시간이 올바르지 않습니다.', 400)
    }
    // 발효일이 발행창(오늘+13일) 안이면 서버가 예약을 보고 뒤로 미룰 수 있다 —
    // 목은 예약 있는 날(demoReservedClosureDate) 다음 날로 미루는 경우를 재현한다.
    const pushedTo = mockDateKey(4)
    const effectiveFrom =
      body.desiredEffectiveFrom <= demoReservedClosureDate
        ? pushedTo
        : body.desiredEffectiveFrom
    const existingSchedule = demoScheduledOperatingHours.find(
      (schedule) => schedule.effectiveFrom === effectiveFrom,
    )
    if (body.saveMode === 'CREATE' && existingSchedule) {
      return fail(
        'HOSPITAL_016',
        '진료시간이 다른 변경으로 갱신되었습니다. 새로고침 후 다시 시도해 주세요.',
        409,
      )
    }
    if (
      body.saveMode === 'UPDATE' &&
      (!existingSchedule ||
        existingSchedule.scheduleId !== body.targetScheduleId ||
        existingSchedule.updatedAt !== body.expectedUpdatedAt)
    ) {
      return fail(
        'HOSPITAL_016',
        '진료시간이 다른 변경으로 갱신되었습니다. 새로고침 후 다시 시도해 주세요.',
        409,
      )
    }
    const savedSchedule = {
      scheduleId: existingSchedule?.scheduleId ?? demoOperatingHoursSequence++,
      updatedAt: new Date().toISOString(),
      effectiveFrom,
      days: body.days,
    }
    demoScheduledOperatingHours = [
      ...demoScheduledOperatingHours.filter(
        (schedule) => schedule.effectiveFrom !== effectiveFrom,
      ),
      savedSchedule,
    ].sort((first, second) =>
      first.effectiveFrom.localeCompare(second.effectiveFrom),
    )
    return ok(savedSchedule)
  }),

  // --- 병원 스태프 운영: 진료역량 (GET/PUT /hospital/capabilities) ---
  http.get(`${BASE}/hospital/capabilities`, () =>
    ok({ capabilities: [...demoCapabilities].sort() }),
  ),
  http.put(`${BASE}/hospital/capabilities`, async ({ request }) => {
    const body = (await request.json()) as { capabilities: string[] }
    const requested = body.capabilities ?? []
    if (new Set(requested).size !== requested.length) {
      return fail('HOSPITAL_012', '진료 역량을 중복해서 입력할 수 없습니다.', 400)
    }
    demoCapabilities = [...requested]
    // 저장 결과가 보호자 병원 상세 태그로 이어지는지 목에서도 확인할 수 있게 상세에 반영한다.
    const detail = hospitalDetails[demoMember.hospitalId ?? 1]
    if (detail) detail.capabilities = [...requested] as typeof detail.capabilities
    return ok({ capabilities: [...demoCapabilities].sort() })
  }),

  // --- 병원 스태프 운영: 임시휴진 (POST/DELETE /hospital/temporary-closures) ---
  http.post(`${BASE}/hospital/temporary-closures`, async ({ request }) => {
    const body = (await request.json()) as { businessDate: string }
    const businessDate = body.businessDate
    if (businessDate <= mockDateKey(0)) {
      return fail(
        'HOSPITAL_007',
        '임시 휴무는 요청일 다음 날부터 등록할 수 있습니다.',
        400,
      )
    }
    if (demoClosures.has(businessDate)) {
      return fail('HOSPITAL_009', '이미 임시 휴무로 등록된 영업일입니다.', 409)
    }
    if (businessDate === demoReservedClosureDate) {
      return fail(
        'HOSPITAL_008',
        '예약이 있는 영업일은 임시 휴무로 등록할 수 없습니다.',
        409,
      )
    }
    demoClosures.add(businessDate)
    return ok({ businessDate }, 201)
  }),
  // 취소는 204 + 본문 없음(백엔드와 동일). 마감 검사가 존재 검사보다 먼저다.
  http.delete(`${BASE}/hospital/temporary-closures/:businessDate`, ({ params }) => {
    const businessDate = String(params.businessDate)
    if (businessDate <= mockDateKey(0)) {
      return fail(
        'HOSPITAL_011',
        '임시 휴무는 휴무 영업일 전날까지만 취소할 수 있습니다.',
        400,
      )
    }
    if (!demoClosures.has(businessDate)) {
      return fail('HOSPITAL_010', '임시 휴무 정보를 찾을 수 없습니다.', 404)
    }
    demoClosures.delete(businessDate)
    return new HttpResponse(null, { status: 204 })
  }),

  // --- 결제수단 ---
  http.get(`${BASE}/payment-methods`, () => ok(demoMethods)),
  http.post(`${BASE}/payment-methods`, () => {
    // 실 백엔드(saveAsFirstDefaultOrNonDefault)는 활성 수단이 하나도 없을 때만 신규 카드를
    // 기본값으로 만든다 — "기본값 없음"이 곧 첫 등록은 아니므로 기준은 활성 수단의 존재다.
    const hasActive = demoMethods.some((m) => m.status === 'ACTIVE')
    const method: PaymentMethod = {
      id: methodSeq++,
      cardBrand: 'KB',
      cardLast4: String(1000 + Math.floor(methodSeq * 7)).slice(-4),
      status: 'ACTIVE',
      isDefault: !hasActive,
      createdAt: new Date().toISOString(),
    }
    demoMethods.push(method)
    return ok(method, 201)
  }),
  http.delete(`${BASE}/payment-methods/:id`, ({ params }) => {
    demoMethods = demoMethods.filter((m) => m.id !== Number(params.id))
    return new HttpResponse(null, { status: 204 })
  }),
  // 기본 결제수단 지정 — 기존 기본을 해제하고 대상만 지정한다(서버와 동일하게 항상 1건 유지).
  http.patch(`${BASE}/payment-methods/:id/default`, ({ params }) => {
    const id = Number(params.id)
    const target = demoMethods.find((m) => m.id === id)
    if (!target) {
      return fail('PAYMENT_METHOD_003', '결제수단을 찾을 수 없습니다.', 404)
    }
    if (target.status !== 'ACTIVE') {
      return fail(
        'PAYMENT_METHOD_004',
        '활성 상태의 결제수단만 지정할 수 있습니다.',
        409,
      )
    }
    demoMethods.forEach((m) => {
      m.isDefault = m.id === id
    })
    return ok(target)
  }),

  // --- JSON 영수증 (dev:mock 오프라인 전용) — 보호자·스태프 공통 빌더(위 receiptResolver) ---
  http.get(`${BASE}/payments/:paymentId/receipt`, receiptResolver),
  http.get(`${BASE}/hospital/payments/:paymentId/receipt`, receiptResolver),

  // --- 펫 프로필 (dev:mock 오프라인 전용. 실연동(npm run dev)에선 백엔드로 감) ---
  http.get(`${BASE}/pets`, () => ok(demoPets)),
  http.get(`${BASE}/pets/:petId`, ({ params }) => {
    const pet = demoPets.find((p) => p.petId === Number(params.petId))
    return pet
      ? ok(pet)
      : fail('PET_001', '존재하지 않는 반려동물입니다.', 404)
  }),
  http.post(`${BASE}/pets`, async ({ request }) => {
    const b = (await request.json()) as Record<string, unknown>
    const pet = {
      petId: Math.max(0, ...demoPets.map((p) => p.petId)) + 1,
      name: String(b.name ?? ''),
      species: (b.species as 'DOG' | 'CAT') ?? 'DOG',
      age: Number(b.age ?? 0),
      weight: Number(b.weight ?? 0),
      neutered: Boolean(b.neutered),
      imageUrl: null,
    }
    demoPets.push(pet)
    return ok(pet, 201)
  }),
  http.patch(`${BASE}/pets/:petId`, async ({ params, request }) => {
    const pet = demoPets.find((p) => p.petId === Number(params.petId))
    if (!pet) return fail('PET_001', '존재하지 않는 반려동물입니다.', 404)
    const body = (await request.json()) as Record<string, unknown>
    /*
      서버는 imageUrl이 그 펫에게 발급한 key 접두사와 맞는지 확인하고 아니면 PET_002로 거절한다
      (PetService.update → isManagedFileUrl). 목이 무검증으로 받으면 그 거부 UX를 오프라인에서
      볼 수 없어 같은 조건을 흉내낸다.
    */
    if (
      typeof body.imageUrl === 'string' &&
      !body.imageUrl.startsWith(`/__mock-upload/pets/${pet.petId}/`)
    ) {
      return fail(
        'PET_002',
        '허용되지 않은 이미지 URL입니다. 발급받은 업로드 URL로 업로드한 이미지만 저장할 수 있습니다.',
        400,
      )
    }
    Object.assign(pet, body)
    return ok(pet)
  }),
  /*
    프로필 이미지 업로드 URL 발급 + 그 URL로의 PUT까지 목으로 받는다. 발급 응답의 uploadUrl은
    같은 오리진(/__mock-upload/…)으로 만들어 MSW가 가로챌 수 있게 한다 — 실 백엔드(fake 스토리지)는
    localhost:9000을 주고 실제 업로드는 되지 않으므로, 3단계 흐름 자체는 dev:mock에서만 끝까지 돈다.
  */
  http.post(`${BASE}/pets/:petId/image/upload-url`, async ({ params, request }) => {
    const pet = demoPets.find((p) => p.petId === Number(params.petId))
    if (!pet) return fail('PET_001', '존재하지 않는 반려동물입니다.', 404)
    const body = (await request.json()) as { contentType?: string }
    const allowed = ['image/jpeg', 'image/png', 'image/webp']
    if (!body.contentType || !allowed.includes(body.contentType)) {
      return fail('COMMON_001', '입력값이 올바르지 않습니다.', 400)
    }
    const extension = body.contentType.split('/')[1]
    const key = `pets/${pet.petId}/${Date.now()}.${extension}`
    return ok({
      uploadUrl: `/__mock-upload/${key}`,
      imageUrl: `/__mock-upload/${key}`,
      expiresInSeconds: 300,
    })
  }),
  http.put('/__mock-upload/*', () => new HttpResponse(null, { status: 200 })),
  http.delete(`${BASE}/pets/:petId`, ({ params }) => {
    const i = demoPets.findIndex((p) => p.petId === Number(params.petId))
    if (i >= 0) demoPets.splice(i, 1)
    return new HttpResponse(null, { status: 204 })
  }),

  // --- 예약 요청/취소 ---
  http.post(`${BASE}/reservations`, async ({ request }) => {
    const body = (await request.json()) as {
      petId: number
      slotId: number
      paymentMethodId: number
    }
    const hospitalId = hospitalOfSlot(body.slotId)
    const reservationId = reservationSeq++
    const now = new Date().toISOString()
    // 실제 백엔드처럼 petId로 조회해 스냅샷을 만든다(클라이언트 입력을 신뢰하지 않는다).
    const pet = demoPets.find((p) => p.petId === body.petId)
    // 내 예약 목록에도 반영해 흐름이 이어지게 한다.
    mockReservations.unshift({
      reservationId,
      hospitalId,
      hospitalName: hospitalDetails[hospitalId]?.name ?? '병원',
      petId: body.petId,
      petName: pet?.name ?? '반려동물',
      reservedAt: new Date(Date.now() + 86_400_000).toISOString(),
      reservationStatus: 'REQUESTED',
      paymentStatus: null,
      progressStatus: 'RESERVATION_REQUESTED',
    })
    return ok(
      {
        reservationId,
        petId: body.petId,
        hospitalId,
        slotId: body.slotId,
        status: 'REQUESTED',
        requestedAt: now,
      },
      201,
    )
  }),
  http.patch(`${BASE}/reservations/:reservationId/cancel`, ({ params }) => {
    const id = Number(params.reservationId)
    const target = mockReservations.find((r) => r.reservationId === id)
    if (target) {
      target.reservationStatus = 'CANCELED'
      target.progressStatus = 'RESERVATION_CANCELED'
    }
    // mockReservationDetail도 있으면 함께 갱신.
    const detail = mockReservationDetail[id]
    if (detail) {
      detail.reservationStatus = 'CANCELED'
      detail.progressStatus = 'RESERVATION_CANCELED'
    }
    return ok(null)
  }),

  /*
    예약 결제수단 재지정. 서버 거절 조건을 그대로 흉내낸다 — 진료 시작 후면 RESERVATION_018,
    결제 선기록이 있으면 RESERVATION_019다. 재지정 결과는 응답 계약(Void)에 드러나지 않으므로
    목에서도 상태를 따로 보관하지 않는다.
  */
  http.patch(
    `${BASE}/reservations/:reservationId/payment-method`,
    async ({ params, request }) => {
      const id = Number(params.reservationId)
      const body = (await request.json()) as { paymentMethodId?: number }
      const method = demoMethods.find((m) => m.id === body.paymentMethodId)
      if (!method) {
        return fail('PAYMENT_METHOD_003', '결제수단을 찾을 수 없습니다.', 404)
      }
      if (method.status !== 'ACTIVE') {
        return fail(
          'PAYMENT_METHOD_004',
          '활성 상태의 결제수단만 지정할 수 있습니다.',
          409,
        )
      }
      const detail = mockReservationDetail[id]
      const status = detail?.reservationStatus
      if (status && status !== 'REQUESTED' && status !== 'CONFIRMED') {
        return fail(
          'RESERVATION_018',
          '현재 예약 상태에서는 결제수단을 변경할 수 없습니다.',
          409,
        )
      }
      if (mockPaymentByReservation[id]?.length) {
        return fail(
          'RESERVATION_019',
          '결제가 시작된 예약은 결제수단을 변경할 수 없습니다.',
          409,
        )
      }
      return ok(null)
    },
  ),
  // --- 병원 후기 (dev:mock 오프라인 전용) ---
  http.get(`${BASE}/hospitals/:hospitalId/reviews`, ({ params, request }) => {
    const hospitalId = Number(params.hospitalId)
    const url = new URL(request.url)
    const page = Number(url.searchParams.get('page') ?? '1')
    const size = Number(url.searchParams.get('size') ?? '20')
    // 최신순(백엔드는 createdAt·id 내림차순).
    const all = demoReviews
      .filter((r) => r.hospitalId === hospitalId)
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    const totalPages = Math.ceil(all.length / size)
    const content = all.slice((page - 1) * size, page * size).map((r) => ({
      reviewId: r.reviewId,
      rating: r.rating,
      content: r.content,
      createdAt: r.createdAt,
      updatedAt: r.updatedAt,
    }))
    return ok({
      content,
      page,
      size,
      totalElements: all.length,
      totalPages,
      first: page <= 1,
      last: page >= totalPages,
    })
  }),
  // 내 후기 상태 조회 — 후기와 현재 작성 가능 여부를 함께 준다(SA §8-3).
  http.get(`${BASE}/reservations/:reservationId/review`, ({ params }) => {
    const reservationId = Number(params.reservationId)
    const review = demoReviews.find((r) => r.reservationId === reservationId)
    if (review) return ok({ review, reviewable: false })
    const paid = (mockPaymentByReservation[reservationId] ?? []).some(
      (p) => p.status === 'PAID' || p.status === 'OFFLINE_PAID',
    )
    return ok({
      review: null,
      reviewable: !demoReviewedReservations.has(reservationId) && paid,
    })
  }),
  http.post(`${BASE}/reservations/:reservationId/reviews`, async ({ params, request }) => {
    const reservationId = Number(params.reservationId)
    const body = (await request.json()) as { rating: number; content: string }
    // 예약당 1회(백엔드 uk_reviews_reservation_id + reservations.reviewed_at).
    if (demoReviewedReservations.has(reservationId)) {
      return fail('REVIEW_004', '이미 리뷰 작성 기회를 사용한 예약입니다.', 409)
    }
    // 결제 완료 예약만 작성 가능(백엔드 REVIEW_003).
    const paid = (mockPaymentByReservation[reservationId] ?? []).some(
      (p) => p.status === 'PAID' || p.status === 'OFFLINE_PAID',
    )
    if (!paid) {
      return fail('REVIEW_003', '결제가 완료된 예약만 리뷰를 작성할 수 있습니다.', 409)
    }
    const now = new Date().toISOString()
    const created = {
      reviewId: reviewSeq++,
      reservationId,
      hospitalId: mockReservationDetail[reservationId]?.hospital.hospitalId ?? 1,
      memberId: demoMember.memberId,
      rating: body.rating,
      content: body.content,
      createdAt: now,
      updatedAt: now,
    }
    demoReviews.unshift(created)
    demoReviewedReservations.add(reservationId)
    return ok(created, 201)
  }),
  http.put(`${BASE}/reviews/:reviewId`, async ({ params, request }) => {
    const target = demoReviews.find((r) => r.reviewId === Number(params.reviewId))
    if (!target) {
      return fail('REVIEW_005', '리뷰를 찾을 수 없습니다.', 404)
    }
    const body = (await request.json()) as { rating: number; content: string }
    target.rating = body.rating
    target.content = body.content
    target.updatedAt = new Date().toISOString()
    return ok(target)
  }),
  http.delete(`${BASE}/reviews/:reviewId`, ({ params }) => {
    const index = demoReviews.findIndex(
      (r) => r.reviewId === Number(params.reviewId),
    )
    if (index < 0) {
      return fail('REVIEW_005', '리뷰를 찾을 수 없습니다.', 404)
    }
    // 후기만 지우고 작성 기회는 소진된 채로 남긴다(백엔드도 reviewed_at을 되돌리지 않는다).
    demoReviews.splice(index, 1)
    return ok(null)
  }),

  // --- 예약 대기열 (dev:mock 오프라인 전용) ---
  http.get(`${BASE}/reservation-waitlists`, () => ok(demoWaitlists)),
  http.get(`${BASE}/reservation-waitlists/:waitlistId`, ({ params }) => {
    const target = demoWaitlists.find(
      (w) => w.waitlistId === Number(params.waitlistId),
    )
    if (!target) {
      return fail('WAITLIST_003', '예약 대기열을 찾을 수 없습니다.', 404)
    }
    return ok(target)
  }),
  http.post(`${BASE}/reservation-waitlists`, async ({ request }) => {
    const body = (await request.json()) as { slotId: number }
    if (
      demoWaitlists.some(
        (w) =>
          w.slotId === body.slotId &&
          (w.status === 'WAITING' || w.status === 'OFFERED'),
      )
    ) {
      return fail('WAITLIST_002', '이미 해당 예약 슬롯의 대기열에 등록했습니다.', 409)
    }
    const created = {
      waitlistId: waitlistSeq++,
      slotId: body.slotId,
      status: 'WAITING' as const,
      requestedAt: new Date().toISOString(),
      offeredAt: null,
      offerExpiresAt: null,
      respondedAt: null,
      canceledAt: null,
    }
    demoWaitlists.unshift(created)
    demoWaitlistHospitalId[created.waitlistId] = hospitalOfSlot(body.slotId)
    return ok(created, 201)
  }),
  http.delete(`${BASE}/reservation-waitlists/:waitlistId`, ({ params }) => {
    const target = demoWaitlists.find(
      (w) => w.waitlistId === Number(params.waitlistId),
    )
    if (!target) {
      return fail('WAITLIST_003', '예약 대기열을 찾을 수 없습니다.', 404)
    }
    if (target.status !== 'WAITING') {
      return fail('WAITLIST_006', 'WAITING 상태의 예약 대기열만 취소할 수 있습니다.', 409)
    }
    target.status = 'CANCELED'
    target.canceledAt = new Date().toISOString()
    return ok(null)
  }),
  http.post(
    `${BASE}/reservation-waitlists/:waitlistId/accept`,
    async ({ params, request }) => {
      const target = demoWaitlists.find(
        (w) => w.waitlistId === Number(params.waitlistId),
      )
      if (!target || target.status !== 'OFFERED') {
        return fail('WAITLIST_004', '현재 응답할 수 있는 승급 제안이 없습니다.', 409)
      }
      const body = (await request.json()) as {
        petId: number
        paymentMethodId: number
      }
      const hospitalId =
        demoWaitlistHospitalId[target.waitlistId] ?? hospitalOfSlot(target.slotId)
      const reservationId = reservationSeq++
      const now = new Date().toISOString()
      target.status = 'ACCEPTED'
      target.respondedAt = now
      // 실 백엔드처럼 예약을 생성한다 — 내 예약 목록·상세로 흐름이 이어지게 반영.
      const pet = demoPets.find((p) => p.petId === body.petId)
      mockReservations.unshift({
        reservationId,
        hospitalId,
        hospitalName: hospitalDetails[hospitalId]?.name ?? '병원',
        petId: body.petId,
        petName: pet?.name ?? '반려동물',
        reservedAt: new Date(Date.now() + 86_400_000).toISOString(),
        reservationStatus: 'REQUESTED',
        paymentStatus: null,
        progressStatus: 'RESERVATION_REQUESTED',
      })
      return ok(
        {
          reservationId,
          petId: body.petId,
          hospitalId,
          slotId: target.slotId,
          status: 'REQUESTED',
          requestedAt: now,
        },
        201,
      )
    },
  ),
  http.patch(`${BASE}/reservation-waitlists/:waitlistId/reject`, ({ params }) => {
    const target = demoWaitlists.find(
      (w) => w.waitlistId === Number(params.waitlistId),
    )
    if (!target || target.status !== 'OFFERED') {
      return fail('WAITLIST_004', '현재 응답할 수 있는 승급 제안이 없습니다.', 409)
    }
    target.status = 'REJECTED'
    target.respondedAt = new Date().toISOString()
    return ok(null)
  }),
]
