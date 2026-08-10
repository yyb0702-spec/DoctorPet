// 병원 검색 결과 (화면메모 A-5, 실연동 PR #67).
// 서버 keyword 검색 + 필터 + 페이지네이션 + 거리·예약가능 배지.
import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { MapPin, Search, ChevronDown } from 'lucide-react'
import { useHospitalSearch } from '@/features/hospitals/hooks'
import type { HospitalSearchParams } from '@/features/hospitals/types'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { EmptyState, ErrorState, PageLoader } from '@/components/common/States'
import { HospitalMap } from '@/components/common/HospitalMap'
import { cn } from '@/lib/utils'
import { PetSpecies } from '@/types/enums'

const PAGE_SIZE = 20

const BUSINESS_LABEL: Record<string, string> = {
  CLOSED_TEMP: '임시휴업',
  CLOSED: '폐업',
}

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

  const toggleFlag = (key: 'nightCare' | 'emergency' | 'surgery') =>
    setParams((p) => ({ ...p, [key]: p[key] ? undefined : true, page: 1 }))

  const goToPage = (next: number) =>
    setParams((p) => ({ ...p, page: next }))

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

      {/* 필터 칩 */}
      <div className="flex flex-wrap gap-2">
        <Button
          size="sm"
          variant={species === PetSpecies.DOG ? 'default' : 'outline'}
          onClick={() => toggleSpecies(PetSpecies.DOG)}
        >
          강아지
        </Button>
        <Button
          size="sm"
          variant={species === PetSpecies.CAT ? 'default' : 'outline'}
          onClick={() => toggleSpecies(PetSpecies.CAT)}
        >
          고양이
        </Button>
        <Button
          size="sm"
          variant={params.nightCare ? 'default' : 'outline'}
          onClick={() => toggleFlag('nightCare')}
        >
          야간진료
        </Button>
        <Button
          size="sm"
          variant={params.emergency ? 'default' : 'outline'}
          onClick={() => toggleFlag('emergency')}
        >
          응급
        </Button>
        <Button
          size="sm"
          variant={params.surgery ? 'default' : 'outline'}
          onClick={() => toggleFlag('surgery')}
        >
          수술
        </Button>
        <Button
          size="sm"
          variant={params.partnerOnly ? 'default' : 'outline'}
          onClick={() =>
            setParams((p) => ({
              ...p,
              partnerOnly: p.partnerOnly ? undefined : true,
              page: 1,
            }))
          }
        >
          제휴 병원만
        </Button>
      </div>

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
                <Button asChild size="sm" variant="outline">
                  <Link to={`/hospitals/${h.hospitalId}`}>상세</Link>
                </Button>
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
