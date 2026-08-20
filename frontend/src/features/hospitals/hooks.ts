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
import { useMe } from '@/features/members/hooks'
import { MemberRole } from '@/types/enums'

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

/*
  찜 목록은 백엔드가 보호자 전용으로 제한한다(SecurityConfig: `/api/members/me/favorite-hospitals`
  → hasRole("GUARDIAN")). 그래서 스태프로 확인되면 호출하지 않는다 — 403이 쌓이기 때문이다(리뷰 P2).

  단, 역할을 **알 수 없는** 경우(내 정보 조회 실패)에는 막지 않는다(자기검토). useMe는 실패 시
  재시도하는 동안 계속 undefined라 창이 짧지 않은데, 그때 호출을 막으면 정상 보호자에게 빈 목록이
  "찜한 병원이 없습니다"로 보인다. 인가의 최종 판단은 서버가 하므로, 모르면 물어보고 403이면
  오류로 드러내는 편이 낫다.
*/
export function useFavoriteHospitals(page = 1, size = 20) {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  const me = useMe()
  const knownNonGuardian =
    me.data != null && me.data.role !== MemberRole.GUARDIAN
  return useQuery({
    queryKey: hospitalKeys.favorites(page, size),
    queryFn: () => hospitalApi.listFavorites(page, size),
    enabled: isAuthenticated && !knownNonGuardian,
    placeholderData: keepPreviousData,
  })
}

/*
  찜 토글. 하트는 누른 즉시 반응해야 해서 낙관적으로 뒤집고, 실패하면 스냅샷으로 되돌린다.

  같은 병원이 검색 목록·상세·찜 목록 세 곳에 동시에 떠 있을 수 있어 세 캐시를 모두 손본다.
  검색·상세는 응답 안의 favorite 플래그를 직접 갈아끼우고(재조회 없이 즉시 반영), 찜 목록은
  항목이 늘거나 빠지는 구조 변화라 낙관적 편집 대신 무효화로 다시 읽는다.

  롤백은 **실패한 병원의 플래그만** 되돌린다. 스냅샷 전체를 복원하면, 검색 결과에서 A·B를 연달아
  누른 뒤 A가 실패할 때 그 사이 성공한 B의 토글까지 함께 되돌아간다(리뷰 P2). 실패한 뒤에는
  서버 진실과 어긋났을 수 있으니 병원 쿼리도 무효화해 다시 읽는다 — 성공 경로에서는 낙관적
  편집이 이미 정답이라 재조회하지 않는다.

  진행 중인 병원 쿼리를 취소하지 않는다(자기검토). 취소하면 "다음 페이지"를 누른 직후 하트를
  누른 경우 검색 조회가 끊기고, keepPreviousData 때문에 이전 페이지가 남은 채 재조회 트리거가
  없어 목록이 멈춘다. 대신 성공 시 같은 플래그를 한 번 더 써서, 요청 도중 도착한 응답이
  낙관적 편집을 덮었더라도 결과가 수렴하게 한다.
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
    onMutate: ({ hospitalId, favorite }) => {
      patchFavoriteInCaches(queryClient, hospitalId, favorite)
      // 되돌릴 값은 이 병원의 직전 플래그뿐이다(토글이므로 !favorite).
      return { hospitalId, previousFavorite: !favorite }
    },
    onSuccess: (_data, { hospitalId, favorite }) => {
      // 요청 중 도착한 조회 응답이 낙관적 편집을 덮었을 수 있어 서버가 확정한 값을 다시 쓴다.
      patchFavoriteInCaches(queryClient, hospitalId, favorite)
    },
    onError: (_error, _variables, context) => {
      if (!context) return
      patchFavoriteInCaches(
        queryClient,
        context.hospitalId,
        context.previousFavorite,
      )
      queryClient.invalidateQueries({ queryKey: hospitalKeys.all })
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ['favorite-hospitals'] })
    },
  })
}
