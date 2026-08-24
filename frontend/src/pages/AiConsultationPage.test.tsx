import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { AiConsultationPage } from './AiConsultationPage'
import { useAiConsultation } from '@/features/ai/hooks'
import type { AiConsultationResult } from '@/features/ai/types'
import type { HospitalSummary } from '@/features/hospitals/types'

vi.mock('@/features/ai/hooks', () => ({ useAiConsultation: vi.fn() }))

const recommendedHospital: HospitalSummary = {
  hospitalId: 1,
  name: '도담동물병원',
  address: '서울시 강남구',
  distanceKm: 1.2,
  businessStatus: 'OPEN',
  partnershipStatus: 'PARTNER',
  reservationAvailable: true,
  partnershipBadge: null,
  openNow: true,
  favorite: false,
}

const otherHospital: HospitalSummary = {
  ...recommendedHospital,
  hospitalId: 2,
  name: '행복동물병원',
}

function result(
  overrides: Partial<AiConsultationResult> = {},
): AiConsultationResult {
  return {
    structured: {
      possibleFocusAreas: ['소화기'],
      requiredCapabilities: ['BLOOD_TEST'],
      urgencyLevel: 'LOW',
      preVisitCheckpoints: [],
      recommendVetVisit: true,
    },
    hospitals: [recommendedHospital, otherHospital],
    recommendations: [
      {
        hospital: recommendedHospital,
        recommendationScore: 5,
        recommendationReason: '필요한 진료 역량을 보유하고 있습니다.',
        evidence: [{ type: 'CAPABILITY', value: '필요 진료역량 보유' }],
      },
    ],
    disclaimer: '',
    message: '',
    fallback: false,
    locationRequired: false,
    locationRecommended: false,
    ...overrides,
  }
}

function renderPage(data: AiConsultationResult) {
  vi.mocked(useAiConsultation).mockReturnValue({
    data,
    mutate: vi.fn(),
    isPending: false,
    isError: false,
    error: null,
  } as unknown as ReturnType<typeof useAiConsultation>)

  return render(
    <MemoryRouter>
      <AiConsultationPage />
    </MemoryRouter>,
  )
}

describe('AiConsultationPage 추천 병원 표시', () => {
  it('AI 추천과 추천 근거를 먼저 보여주고, 중복 병원은 그 외 목록에서 제외한다', () => {
    renderPage(result())

    expect(
      screen.getByRole('heading', { name: 'AI 추천 병원' }),
    ).toBeInTheDocument()
    expect(
      screen.getByText('필요한 진료 역량을 보유하고 있습니다.'),
    ).toBeInTheDocument()
    expect(screen.getByText('필요 진료역량 보유')).toBeInTheDocument()
    expect(
      screen.getByRole('heading', { name: '그 외 병원' }),
    ).toBeInTheDocument()
    expect(screen.getAllByText('도담동물병원')).toHaveLength(1)
    expect(screen.getByText('행복동물병원')).toBeInTheDocument()
  })

  it('위치가 필수인 응답에서는 검색 실패가 아니라 위치 입력 안내를 보여준다', () => {
    renderPage(
      result({
        hospitals: [],
        recommendations: [],
        locationRequired: true,
        locationRecommended: false,
      }),
    )

    expect(
      screen.getByText(
        /맞춤 병원 추천을 받으려면 지역이나 현재 위치가 필요해요/,
      ),
    ).toBeInTheDocument()
    expect(
      screen.queryByText(
        '조건에 맞는 병원을 찾지 못했어요. 병원 검색에서 직접 찾아보세요.',
      ),
    ).not.toBeInTheDocument()
  })
})
