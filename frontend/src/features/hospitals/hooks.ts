// 병원 쿼리 훅.
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { hospitalApi } from './api'
import type { HospitalSearchParams } from './types'

export const hospitalKeys = {
  detail: (id: number) => ['hospitals', id] as const,
  search: (params: HospitalSearchParams) =>
    ['hospitals', 'search', params] as const,
  slots: (id: number, date: string) => ['hospitals', id, 'slots', date] as const,
}

// 백엔드가 "오늘"을 Asia/Seoul 기준으로 계산하므로 프론트도 서울 날짜로 맞춘다.
// en-CA 로케일은 yyyy-MM-dd 형식을 준다.
export function todaySeoul(): string {
  return new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Seoul' })
}

export function useHospitalDetail(hospitalId: number) {
  return useQuery({
    queryKey: hospitalKeys.detail(hospitalId),
    queryFn: () => hospitalApi.getDetail(hospitalId),
    enabled: Number.isFinite(hospitalId),
  })
}

export function useHospitalSearch(params: HospitalSearchParams) {
  return useQuery({
    queryKey: hospitalKeys.search(params),
    queryFn: () => hospitalApi.search(params),
    // 페이지 이동·필터 변경 시 이전 결과를 유지해 깜빡임을 줄인다.
    placeholderData: keepPreviousData,
  })
}

export function useHospitalSlots(
  hospitalId: number,
  date: string,
  enabled = true,
) {
  return useQuery({
    queryKey: hospitalKeys.slots(hospitalId, date),
    queryFn: () => hospitalApi.getSlots(hospitalId, date),
    enabled: enabled && Number.isFinite(hospitalId) && Boolean(date),
    // 날짜 전환 시 이전 날짜 결과를 유지해 깜빡임을 줄인다.
    placeholderData: keepPreviousData,
  })
}
