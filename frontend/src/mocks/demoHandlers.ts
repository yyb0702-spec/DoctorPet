// 데모 목 핸들러 — 백엔드 없이 전체 UI를 체험하기 위해 "실연동" 엔드포인트까지 mock 한다.
// VITE_FULL_MOCK=true (npm run dev:mock)일 때만 browser.ts가 이 핸들러를 얹는다.
// 기본 개발(npm run dev)에서는 얹지 않으므로 실연동은 그대로 백엔드로 간다.
import { http, HttpResponse } from 'msw'
import {
  hospitalOfSlot,
  mockHospitals,
  mockNotifications,
  mockReservationDetail,
  mockReservations,
} from './data'
import type {
  HospitalDetail,
  HospitalSummary,
} from '@/features/hospitals/types'
import type { PaymentMethod } from '@/features/payments/types'
import type { Pet } from '@/features/pets/api'

const BASE = '/api'

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
  { petId: 1, name: '초코', species: 'DOG', age: 3, weight: 5.2, neutered: true },
  { petId: 2, name: '나비', species: 'CAT', age: 2, weight: 3.8, neutered: false },
]

// 결제수단 in-memory 저장소.
let demoMethods: PaymentMethod[] = [
  {
    id: 1,
    cardBrand: 'SHINHAN',
    cardLast4: '1234',
    status: 'ACTIVE',
    createdAt: new Date(Date.now() - 86_400_000).toISOString(),
  },
]
let methodSeq = 2
let reservationSeq = 6000

export const demoHandlers = [
  // --- 인증 ---
  http.post(`${BASE}/auth/signup`, () => ok({ memberId: 1 }, 201)),
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
      return fail('COMMON_400', '닉네임은 1~255자여야 합니다.', 400)
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
    if (detail) return ok(detail)
    const summary = mockHospitals.find((h) => h.hospitalId === id)
    if (summary) return ok(synthDetail(summary))
    return fail('HOSPITAL_NOT_FOUND', '병원을 찾을 수 없습니다.', 404)
  }),

  // --- 결제수단 ---
  http.get(`${BASE}/payment-methods`, () => ok(demoMethods)),
  http.post(`${BASE}/payment-methods`, () => {
    const method: PaymentMethod = {
      id: methodSeq++,
      cardBrand: 'KB',
      cardLast4: String(1000 + Math.floor(methodSeq * 7)).slice(-4),
      status: 'ACTIVE',
      createdAt: new Date().toISOString(),
    }
    demoMethods.push(method)
    return ok(method, 201)
  }),
  http.delete(`${BASE}/payment-methods/:id`, ({ params }) => {
    demoMethods = demoMethods.filter((m) => m.id !== Number(params.id))
    return new HttpResponse(null, { status: 204 })
  }),

  // --- 펫 프로필 (dev:mock 오프라인 전용. 실연동(npm run dev)에선 백엔드로 감) ---
  http.get(`${BASE}/pets`, () => ok(demoPets)),
  http.get(`${BASE}/pets/:petId`, ({ params }) => {
    const pet = demoPets.find((p) => p.petId === Number(params.petId))
    return pet
      ? ok(pet)
      : fail('PET_001', '펫을 찾을 수 없습니다.', 404)
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
    }
    demoPets.push(pet)
    return ok(pet, 201)
  }),
  http.patch(`${BASE}/pets/:petId`, async ({ params, request }) => {
    const pet = demoPets.find((p) => p.petId === Number(params.petId))
    if (!pet) return fail('PET_001', '펫을 찾을 수 없습니다.', 404)
    Object.assign(pet, await request.json())
    return ok(pet)
  }),
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
      petNameSnapshot: string
      petSpeciesSnapshot: string
    }
    const hospitalId = hospitalOfSlot(body.slotId)
    const reservationId = reservationSeq++
    const now = new Date().toISOString()
    // 내 예약 목록에도 반영해 흐름이 이어지게 한다.
    mockReservations.unshift({
      reservationId,
      hospitalId,
      hospitalName: hospitalDetails[hospitalId]?.name ?? '병원',
      petId: body.petId,
      petName: body.petNameSnapshot,
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
]
