// 병원 검색 결과 (화면메모 A-5, 실연동 PR #67).
// 서버 keyword 검색 + 필터 + 페이지네이션 + 거리·예약가능 배지.
import { useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { MapPin, Search, ChevronDown } from 'lucide-react'
import { useHospitalSearch } from '@/features/hospitals/hooks'
import { FavoriteButton } from '@/features/hospitals/FavoriteButton'
import type { HospitalSearchParams } from '@/features/hospitals/types'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { EmptyState, ErrorState, PageLoader } from '@/components/common/States'
import { HospitalMap } from '@/components/common/HospitalMap'
import {
  FilterCheckbox,
  FilterDropdown,
  FilterOption,
} from '@/components/common/FilterDropdown'
import { cn } from '@/lib/utils'
import { PetSpecies } from '@/types/enums'
import { SPECIES_ORDER, SPECIES_LABEL } from '@/lib/species'

const PAGE_SIZE = 20

const BUSINESS_LABEL: Record<string, string> = {
  CLOSED_TEMP: '임시휴업',
  CLOSED: '폐업',
}

// 지역 필터: 화면엔 축약명을 보이되 서버엔 주소에 실제로 포함되는 정식 시·도명을 보낸다.
// 서버가 도로명·지번 주소에 부분일치(containsIgnoreCase)시키므로, 광역시·특별자치시/도는
// 축약명이 주소에 그대로 들어 있어 매칭되지만("서울"⊂"서울특별시"), "충청·전라·경상"계 도는
// 정식 명칭이라야 매칭된다("충북"⊄"충청북도"). 시/군/구까지 좁히는 건 후속.
const SIDO_OPTIONS: { label: string; value: string }[] = [
  { label: '서울', value: '서울' },
  { label: '부산', value: '부산' },
  { label: '대구', value: '대구' },
  { label: '인천', value: '인천' },
  { label: '광주', value: '광주' },
  { label: '대전', value: '대전' },
  { label: '울산', value: '울산' },
  { label: '세종', value: '세종' },
  { label: '경기', value: '경기' },
  { label: '강원', value: '강원' },
  { label: '충북', value: '충청북도' },
  { label: '충남', value: '충청남도' },
  { label: '전북', value: '전북' },
  { label: '전남', value: '전라남도' },
  { label: '경북', value: '경상북도' },
  { label: '경남', value: '경상남도' },
  { label: '제주', value: '제주' },
]

export function HospitalSearchPage() {
  const [searchParams] = useSearchParams()
  const initialKeyword = searchParams.get('q') ?? ''
  const [keyword, setKeyword] = useState(initialKeyword)
  // 카드에서 지도를 펼친 병원 id (한 번에 하나만 열림).
  const [mapOpenId, setMapOpenId] = useState<number | null>(null)
  const [params, setParams] = useState<HospitalSearchParams>({
    keyword: initialKeyword || undefined,
    page: 1,
    size: PAGE_SIZE,
  })
  const { data, isLoading, isError, refetch, isFetching } =
    useHospitalSearch(params)

  const content = data?.content ?? []
  const species = params.supportedSpecies?.[0]

  const commitKeyword = () =>
    setParams((p) => ({ ...p, keyword: keyword.trim() || undefined, page: 1 }))

  const toggleSpecies = (s: PetSpecies) =>
    setParams((p) => ({
      ...p,
      supportedSpecies: species === s ? undefined : [s],
      page: 1,
    }))

  const toggleFlag = (
    key: 'nightCare' | 'emergency' | 'surgery' | 'hospitalization',
  ) => setParams((p) => ({ ...p, [key]: p[key] ? undefined : true, page: 1 }))

  const activeCareCount = [
    params.nightCare,
    params.emergency,
    params.surgery,
    params.hospitalization,
  ].filter(Boolean).length

  const goToPage = (next: number) =>
    setParams((p) => ({ ...p, page: next }))

  const region = params.region
  // params.region은 서버로 보내는 값(정식 명칭)이라, 드롭다운엔 대응하는 축약 라벨을 되짚어 보인다.
  const regionLabel = SIDO_OPTIONS.find((o) => o.value === region)?.label
  const setRegion = (next?: string) =>
    setParams((p) => ({ ...p, region: next, page: 1 }))

  // 거리순 정렬은 현재 위치가 있어야 성립한다(서버가 좌표 없으면 이름순으로 되돌린다).
  const distanceSort = params.sort === 'distance'
  const [geoLocating, setGeoLocating] = useState(false)
  const [geoError, setGeoError] = useState<string | null>(null)
  // 위치 조회 요청 세대. 조회 중 사용자가 이름순으로 되돌리면 세대를 올려, 늦게 도착한 콜백이
  // 취소된 선택을 거리순으로 되살리지 못하게 한다(리뷰 P2).
  const geoRequestRef = useRef(0)

  const useNameSort = () => {
    geoRequestRef.current += 1 // 진행 중인 위치 조회 콜백을 무효화한다.
    setGeoLocating(false)
    setGeoError(null)
    setParams((p) => ({
      ...p,
      sort: undefined,
      latitude: undefined,
      longitude: undefined,
      page: 1,
    }))
  }

  const useDistanceSort = () => {
    // 이미 거리순이거나 위치를 받는 중이면 무시한다 — 연타 시 권한 프롬프트가 중복으로 뜨는 걸 막는다.
    if (distanceSort || geoLocating) return
    if (!('geolocation' in navigator)) {
      setGeoError('이 브라우저에서는 현재 위치를 쓸 수 없습니다.')
      return
    }
    const requestId = ++geoRequestRef.current
    setGeoLocating(true)
    setGeoError(null)
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        // 이 조회 이후 사용자가 다른 정렬을 골랐으면(세대 불일치) 무시한다.
        if (geoRequestRef.current !== requestId) return
        setGeoLocating(false)
        setParams((p) => ({
          ...p,
          latitude: pos.coords.latitude,
          longitude: pos.coords.longitude,
          sort: 'distance',
          page: 1,
        }))
      },
      () => {
        if (geoRequestRef.current !== requestId) return
        setGeoLocating(false)
        setGeoError('위치 권한이 필요합니다. 권한을 허용한 뒤 다시 시도해 주세요.')
      },
      { timeout: 10_000 },
    )
  }

  return (
    <div className="space-y-5">
      <div>
        <h1 className="text-2xl font-bold">
          {data ? `맞는 병원 ${data.totalElements}곳` : '병원 검색'}
        </h1>
        <p className="text-sm text-muted-foreground">
          증상·병원명으로 검색하고 필터로 좁혀보세요.
        </p>
      </div>

      {/* 검색어 입력 (메인에서 넘어온 q 반영) */}
      <div className="flex max-w-xl gap-2">
        <div className="relative flex-1">
          <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && commitKeyword()}
            placeholder="증상 또는 병원명"
            className="pl-9"
            aria-label="병원 검색어"
          />
        </div>
        <Button variant="outline" onClick={commitKeyword}>
          검색
        </Button>
      </div>

      {/* 필터 드롭다운 */}
      <div className="flex flex-wrap gap-2">
        <FilterDropdown label={species ? SPECIES_LABEL[species] : '반려동물'} active={!!species}>
          <FilterOption selected={!species} onClick={() => species && toggleSpecies(species)}>
            전체
          </FilterOption>
          {SPECIES_ORDER.map((s) => (
            <FilterOption key={s} selected={species === s} onClick={() => toggleSpecies(s)}>
              {SPECIES_LABEL[s]}
            </FilterOption>
          ))}
        </FilterDropdown>

        <FilterDropdown
          label={activeCareCount > 0 ? `진료 특성 (${activeCareCount})` : '진료 특성'}
          active={activeCareCount > 0}
        >
          <FilterCheckbox checked={!!params.nightCare} onChange={() => toggleFlag('nightCare')}>
            야간진료
          </FilterCheckbox>
          <FilterCheckbox checked={!!params.emergency} onChange={() => toggleFlag('emergency')}>
            응급
          </FilterCheckbox>
          <FilterCheckbox checked={!!params.surgery} onChange={() => toggleFlag('surgery')}>
            수술
          </FilterCheckbox>
          <FilterCheckbox
            checked={!!params.hospitalization}
            onChange={() => toggleFlag('hospitalization')}
          >
            입원
          </FilterCheckbox>
        </FilterDropdown>

        <FilterDropdown
          label={params.partnerOnly ? '제휴 병원만' : '제휴 여부'}
          active={!!params.partnerOnly}
        >
          <FilterOption
            selected={!params.partnerOnly}
            onClick={() =>
              setParams((p) => ({ ...p, partnerOnly: undefined, page: 1 }))
            }
          >
            전체
          </FilterOption>
          <FilterOption
            selected={!!params.partnerOnly}
            onClick={() =>
              setParams((p) => ({ ...p, partnerOnly: true, page: 1 }))
            }
          >
            제휴 병원만
          </FilterOption>
        </FilterDropdown>

        <FilterDropdown label={regionLabel ?? '지역'} active={!!region}>
          <FilterOption selected={!region} onClick={() => setRegion(undefined)}>
            전체
          </FilterOption>
          {SIDO_OPTIONS.map((sido) => (
            <FilterOption
              key={sido.value}
              selected={region === sido.value}
              onClick={() =>
                setRegion(region === sido.value ? undefined : sido.value)
              }
            >
              {sido.label}
            </FilterOption>
          ))}
        </FilterDropdown>

        <FilterDropdown
          label={distanceSort ? '거리순' : '이름순'}
          active={distanceSort}
        >
          <FilterOption selected={!distanceSort} onClick={useNameSort}>
            이름순
          </FilterOption>
          <FilterOption selected={distanceSort} onClick={useDistanceSort}>
            {geoLocating ? '현재 위치 확인 중…' : '거리순(내 위치 기준)'}
          </FilterOption>
        </FilterDropdown>
      </div>

      {geoError && (
        <p className="text-sm text-destructive" role="alert">
          {geoError}
        </p>
      )}

      {/* 결과 리스트 — 카드를 누르면 그 자리에서 지도가 펼쳐진다(이동 X). "상세"만 이동. */}
      <div className="space-y-3">
        {isLoading && <PageLoader />}
        {isError && <ErrorState onRetry={() => refetch()} />}
        {data && content.length === 0 && (
          <EmptyState message="조건에 맞는 병원이 없습니다." />
        )}
        {content.map((h) => {
          const open = mapOpenId === h.hospitalId
          return (
            <Card key={h.hospitalId}>
              <div className="flex items-center justify-between gap-4 p-5">
                {/* 카드 본문: 클릭 시 지도 토글 */}
                <button
                  type="button"
                  onClick={() => setMapOpenId(open ? null : h.hospitalId)}
                  aria-expanded={open}
                  className="flex-1 space-y-1 text-left"
                >
                  <div className="flex items-center gap-2">
                    <span className="font-semibold">{h.name}</span>
                    {h.reservationAvailable ? (
                      <Badge variant="success">예약 가능</Badge>
                    ) : h.partnershipBadge ? (
                      <Badge variant="muted">{h.partnershipBadge}</Badge>
                    ) : (
                      <Badge variant="destructive">
                        {BUSINESS_LABEL[h.businessStatus] ?? '예약 불가'}
                      </Badge>
                    )}
                    {h.openNow && <Badge variant="secondary">영업중</Badge>}
                  </div>
                  <span className="flex items-center gap-1 text-sm text-muted-foreground">
                    <MapPin className="h-3.5 w-3.5" />
                    {h.address}
                    {h.distanceKm != null && (
                      <span className="ml-1 text-xs">· {h.distanceKm}km</span>
                    )}
                    <ChevronDown
                      className={cn(
                        'ml-1 h-3.5 w-3.5 transition-transform',
                        open && 'rotate-180',
                      )}
                    />
                  </span>
                </button>
                <div className="flex items-center gap-1">
                  <FavoriteButton
                    hospitalId={h.hospitalId}
                    favorite={h.favorite}
                  />
                  <Button asChild size="sm" variant="outline">
                    <Link to={`/hospitals/${h.hospitalId}`}>상세</Link>
                  </Button>
                </div>
              </div>
              {open && (
                <CardContent className="pb-5 pt-0">
                  <HospitalMap
                    address={h.address}
                    name={h.name}
                    className="h-56"
                  />
                </CardContent>
              )}
            </Card>
          )
        })}

        {/* 페이지네이션 */}
        {data && data.totalPages > 1 && (
          <div className="flex items-center justify-center gap-3 pt-2">
            <Button
              variant="outline"
              size="sm"
              disabled={data.first || isFetching}
              onClick={() => goToPage((params.page ?? 1) - 1)}
            >
              이전
            </Button>
            <span className="text-sm text-muted-foreground">
              {data.page} / {data.totalPages}
            </span>
            <Button
              variant="outline"
              size="sm"
              disabled={data.last || isFetching}
              onClick={() => goToPage((params.page ?? 1) + 1)}
            >
              다음
            </Button>
          </div>
        )}
      </div>
    </div>
  )
}
