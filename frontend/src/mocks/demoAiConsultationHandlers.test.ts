// dev:mock AI 상담도 위치 신호와 병원 목록 정책을 실 API 계약처럼 유지한다.
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { demoHandlers } from './demoHandlers'

const server = setupServer(...demoHandlers)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

describe('dev:mock AI 상담', () => {
  it('응급인데 위치와 지역이 없으면 전국 병원 대신 위치 권장만 반환한다', async () => {
    const response = await fetch(`${location.origin}/api/ai/consultations`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        symptomText: '호흡이 곤란하고 쓰러졌어요',
        species: 'DOG',
      }),
    })
    const body = (await response.json()) as {
      data: {
        hospitals: unknown[]
        recommendations: unknown[]
        locationRequired: boolean
        locationRecommended: boolean
      }
    }

    expect(response.status).toBe(200)
    expect(body.data.hospitals).toEqual([])
    expect(body.data.recommendations).toEqual([])
    expect(body.data.locationRequired).toBe(false)
    expect(body.data.locationRecommended).toBe(true)
  })
})
