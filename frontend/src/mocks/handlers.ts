// MSW 핸들러 — 백엔드 미구현 API만 계약 기준으로 mock 한다.
// 실연동 대상(auth, members/me, hospitals/{id}, payment-methods, reservations POST/cancel)은
// 여기서 다루지 않고 dev 프록시로 백엔드에 그대로 넘긴다(main.tsx onUnhandledRequest:'bypass').
import { http, HttpResponse } from 'msw'
import {
  buildSlotLookup,
  mockPaymentByReservation,
  mockReservationDetail,
  mockReservations,
  mockHospitals,
} from './data'

// 백엔드 공통 성공 응답 포맷 (SA §7).
function ok<T>(data: T) {
  return HttpResponse.json({
    code: 'SUCCESS',
    message: '요청이 성공했습니다.',
    data,
  })
}

const BASE = '/api'

export const handlers = [
  // 병원 검색 — 실 계약(HospitalSearchPageResponse) 형태로 mock (dev:mock 오프라인용).
  http.get(`${BASE}/hospitals`, ({ request }) => {
    const url = new URL(request.url)
    const keyword = url.searchParams.get('keyword')?.trim()
    const partnerOnly = url.searchParams.get('partnerOnly') === 'true'
    const page = Number(url.searchParams.get('page') ?? '1') // 1-base
    const size = Number(url.searchParams.get('size') ?? '20')

    let filtered = mockHospitals
    if (keyword) filtered = filtered.filter((h) => h.name.includes(keyword))
    if (partnerOnly)
      filtered = filtered.filter((h) => h.partnershipStatus === 'PARTNER')

    // 최초 진입은 제휴 우선 정렬 후 비제휴로 남은 자리를 채운다(SA v1.14).
    // 복사본을 정렬해 원본(mockHospitals)을 변형하지 않는다.
    filtered = [...filtered].sort(
      (a, b) =>
        (a.partnershipStatus === 'PARTNER' ? 0 : 1) -
        (b.partnershipStatus === 'PARTNER' ? 0 : 1),
    )

    const totalElements = filtered.length
    const totalPages = Math.max(1, Math.ceil(totalElements / size))
    const start = (page - 1) * size
    const content = filtered.slice(start, start + size)

    return ok({
      content,
      page,
      size,
      totalElements,
      totalPages,
      first: page === 1,
      last: page >= totalPages,
    })
  }),

  // 병원 슬롯 — 실계약(HospitalSlotLookupResponse). date 필수.
  http.get(`${BASE}/hospitals/:hospitalId/slots`, ({ params, request }) => {
    const id = Number(params.hospitalId)
    const date =
      new URL(request.url).searchParams.get('date') ??
      new Date().toLocaleDateString('en-CA')
    return ok(buildSlotLookup(id, date))
  }),

  // 예약 목록 (실 계약: ReservationPageResponse, page 0-base)
  http.get(`${BASE}/reservations`, ({ request }) => {
    const url = new URL(request.url)
    const status = url.searchParams.get('status')
    const page = Number(url.searchParams.get('page') ?? '0')
    const size = Number(url.searchParams.get('size') ?? '20')
    let filtered = mockReservations
    if (status)
      filtered = filtered.filter((r) => r.reservationStatus === status)
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

  // 예약 상세 (실 계약: 중첩 ReservationDetailResponse)
  http.get(`${BASE}/reservations/:reservationId`, ({ params }) => {
    const id = Number(params.reservationId)
    const detail = mockReservationDetail[id]
    if (detail) return ok(detail)
    // 목록에만 있는(데모 중 새로 만든) 예약은 목록 항목으로 상세를 합성한다.
    const item = mockReservations.find((r) => r.reservationId === id)
    if (item) {
      return ok({
        reservationId: item.reservationId,
        reservationStatus: item.reservationStatus,
        paymentStatus: item.paymentStatus,
        progressStatus: item.progressStatus,
        hospital: {
          hospitalId: item.hospitalId,
          name: item.hospitalName,
          address: '주소 정보',
          phoneNumber: null,
        },
        petSnapshot: { petId: item.petId, name: item.petName, species: 'DOG' },
        slot: {
          slotId: 0,
          startAt: item.reservedAt,
          endAt: new Date(
            new Date(item.reservedAt).getTime() + 1_800_000,
          ).toISOString(),
        },
        rejectionReason: null,
        createdAt: item.reservedAt,
        updatedAt: item.reservedAt,
      })
    }
    return HttpResponse.json(
      {
        code: 'RESERVATION_NOT_FOUND',
        message: '예약을 찾을 수 없습니다.',
        data: null,
      },
      { status: 404 },
    )
  }),

  // 예약별 결제 내역 (실계약: PaymentHistoryResponse[] — 예약당 여러 건 가능)
  http.get(`${BASE}/reservations/:reservationId/payments`, ({ params }) => {
    const id = Number(params.reservationId)
    return ok(mockPaymentByReservation[id] ?? [])
  }),

  // 알림(/notifications)은 백엔드가 실제 제공한다(PR #87). npm run dev에선 백엔드로,
  // dev:mock 오프라인에선 demoHandlers가 페이지 형태로 목한다.

  // 펫 프로필(/pets)은 백엔드가 실제 제공한다(확인 완료). 목으로 덮으면 실데이터를 가리므로
  // 여기서 mock 하지 않는다. full-mock 모드에서도 /pets는 프록시로 백엔드에 간다.
]
