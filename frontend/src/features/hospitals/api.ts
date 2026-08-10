// 병원 API. 상세·검색·슬롯 모두 실연동.
import { http } from '@/lib/api/client'
import type { PageResponse } from '@/types/api'
import type {
  HospitalDetail,
  HospitalSearchParams,
  HospitalSlotLookup,
  HospitalSummary,
} from './types'

function toQuery(params: HospitalSearchParams): string {
  const sp = new URLSearchParams()
  const set = (k: string, v: string | number | boolean | undefined) => {
    if (v !== undefined && v !== '') sp.set(k, String(v))
  }
  set('keyword', params.keyword)
  set('region', params.region)
  set('latitude', params.latitude)
  set('longitude', params.longitude)
  set('radiusKm', params.radiusKm)
  params.requiredCapabilities?.forEach((c) =>
    sp.append('requiredCapabilities', c),
  )
  params.supportedSpecies?.forEach((s) => sp.append('supportedSpecies', s))
  set('surgery', params.surgery)
  set('hospitalization', params.hospitalization)
  set('nightCare', params.nightCare)
  set('emergency', params.emergency)
  set('partnerOnly', params.partnerOnly)
  set('openNow', params.openNow)
  set('page', params.page)
  set('size', params.size)
  set('sort', params.sort)
  const q = sp.toString()
  return q ? `?${q}` : ''
}

export const hospitalApi = {
  // 실연동 (develop 존재)
  getDetail: (hospitalId: number) =>
    http.get<HospitalDetail>(`/hospitals/${hospitalId}`),
  // 실연동 (PR #67). 페이지 응답.
  search: (params: HospitalSearchParams) =>
    http.get<PageResponse<HospitalSummary>>(`/hospitals${toQuery(params)}`),
  // 실연동. date(yyyy-MM-dd) 필수. selectedDate 슬롯 + 14일치 날짜 가용성.
  getSlots: (hospitalId: number, date: string) =>
    http.get<HospitalSlotLookup>(`/hospitals/${hospitalId}/slots?date=${date}`),
}
