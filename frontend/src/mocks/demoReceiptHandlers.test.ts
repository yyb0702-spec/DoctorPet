// dev:mock 영수증 핸들러가 보호자(GET /payments/:id/receipt)와 스태프
// (GET /hospital/payments/:id/receipt) 두 경로 모두에 등록돼 같은 데이터를 반환하는지 검증한다.
// 한쪽만 등록하면 dev:mock에서 스태프 영수증 버튼이 매칭 핸들러가 없어 네트워크 오류가 난다(PR #180 리뷰).
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { demoHandlers } from './demoHandlers'

const server = setupServer(...demoHandlers)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

// MSW는 상대경로 핸들러를 location.origin 기준으로 해석하므로 같은 origin으로 요청해야 매칭된다.
async function getReceipt(path: string) {
  const res = await fetch(`${location.origin}${path}`)
  return { status: res.status, body: await res.json() }
}

describe('dev:mock 영수증 핸들러 — 보호자·스태프 두 경로', () => {
  // 9006 = PAID(영수증 발급 대상), 9002 = OFFLINE_REQUIRED(발급 불가), 데이터는 src/mocks/data.ts.
  it('발급 대상 결제는 두 경로 모두 200으로 동일한 영수증을 반환한다', async () => {
    const guardian = await getReceipt('/api/payments/9006/receipt')
    const staff = await getReceipt('/api/hospital/payments/9006/receipt')

    expect(guardian.status).toBe(200)
    expect(staff.status).toBe(200)
    // 조회 권한(엔드포인트)만 다르고 영수증 데이터 자체는 동일해야 한다.
    expect(staff.body).toEqual(guardian.body)
    expect(guardian.body.data.paymentId).toBe(9006)
  })

  it('발급 불가 상태(OFFLINE_REQUIRED)는 두 경로 모두 409(PAYMENT_013)다', async () => {
    const guardian = await getReceipt('/api/payments/9002/receipt')
    const staff = await getReceipt('/api/hospital/payments/9002/receipt')

    expect(guardian.status).toBe(409)
    expect(staff.status).toBe(409)
    expect(staff.body.code).toBe('PAYMENT_013')
    expect(guardian.body.code).toBe('PAYMENT_013')
  })

  it('결제 목록의 PAID 행도 스태프 영수증을 발급한다', async () => {
    // 8702는 /staff/payments 전용 fixture다. 예약 상세 fixture만 찾으면 404가 되므로
    // 목록과 영수증이 같은 상태원을 봐야 한다(PR #211 후속 리뷰).
    const staff = await getReceipt('/api/hospital/payments/8702/receipt')

    expect(staff.status).toBe(200)
    expect(staff.body.data).toMatchObject({
      paymentId: 8702,
      reservationId: 7002,
      petName: '나비',
      status: 'PAID',
      paymentChannel: 'BILLING_KEY',
      totalAmount: 32000,
    })
  })
})
