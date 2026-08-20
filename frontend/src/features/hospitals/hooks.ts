// 병원 쿼리 훅.
import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
import type { QueryClient } from '@tanstack/react-query'
import { hospitalApi } from './api'
import { withFavoriteFlag } from './favoriteCache'
import type { HospitalSearchParams } from './types'
import { useAuthStore } from '@/lib/auth/authStore'

export const hospitalKeys = {
  // 접두사 무효화용(후기가 바뀌면 상세의 평점 집계가 달라진다).
  all: ['hospitals'] as const,
  detail: (id: number) => ['hospitals', id] as const,
  search: (params: HospitalSearchParams) =>
    ['hospitals', 'search', params] as const,
  slots: (id: number, date: string) => ['hospitals', id, 'slots', date] as const,
  // 찜 목록은 병원 접두사 밖에 둔다 — 상세·검색 무효화(['hospitals'])에 휩쓸리지 않게 한다.
  favorites: (page: number, size: number) =>
    ['favorite-hospitals', page, size] as const,
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

// 캐시에 떠 있는 병원 상세·검색 응답의 favorite만 즉시 갈아끼운다(재조회 없음).
function patchFavoriteInCaches(
  queryClient: QueryClient,
  hospitalId: number,
  favorite: boolean,
) {
  queryClient.getQueriesData({ queryKey: hospitalKeys.all }).forEach(([key, data]) => {
    if (data == null) return
    queryClient.setQueryData(key, withFavoriteFlag(data, hospitalId, favorite))
  })
}

export function useFavoriteHospitals(page = 1, size = 20) {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  return useQuery({
    queryKey: hospitalKeys.favorites(page, size),
    queryFn: () => hospitalApi.listFavorites(page, size),
    enabled: isAuthenticated,
    placeholderData: keepPreviousData,
  })
}

/*
  찜 토글. 하트는 누른 즉시 반응해야 해서 낙관적으로 뒤집고, 실패하면 스냅샷으로 되돌린다.

  같은 병원이 검색 목록·상세·찜 목록 세 곳에 동시에 떠 있을 수 있어 세 캐시를 모두 손본다.
  검색·상세는 응답 안의 favorite 플래그를 직접 갈아끼우고(재조회 없이 즉시 반영), 찜 목록은
  항목이 늘거나 빠지는 구조 변화라 낙관적 편집 대신 무효화로 다시 읽는다.
*/
export function useToggleHospitalFavorite() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({
      hospitalId,
      favorite,
    }: {
      hospitalId: number
      favorite: boolean
    }) =>
      favorite
        ? hospitalApi.addFavorite(hospitalId)
        : hospitalApi.removeFavorite(hospitalId),
    onMutate: async ({ hospitalId, favorite }) => {
      await queryClient.cancelQueries({ queryKey: hospitalKeys.all })
      const snapshot = queryClient.getQueriesData({ queryKey: hospitalKeys.all })
      patchFavoriteInCaches(queryClient, hospitalId, favorite)
      return { snapshot }
    },
    onError: (_error, _variables, context) => {
      // 실패하면 낙관적 편집 전 상태로 통째로 되돌린다(부분 복구는 캐시마다 어긋날 수 있다).
      context?.snapshot.forEach(([key, data]) => {
        queryClient.setQueryData(key, data)
      })
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ['favorite-hospitals'] })
    },
  })
}
