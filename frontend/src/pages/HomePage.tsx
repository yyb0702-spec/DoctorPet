// 메인 (화면메모 A-1) — 히어로 + 증상/병원명 검색 + 내 주변 추천 병원 3곳.
import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Search, MapPin } from 'lucide-react'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { useHospitalSearch } from '@/features/hospitals/hooks'

export function HomePage() {
  const navigate = useNavigate()
  const [query, setQuery] = useState('')
  // 추천 병원은 검색 기본 목록(제휴 우선) 상위 3곳을 노출한다.
  const { data: page } = useHospitalSearch({ page: 1, size: 3 })
  const hospitals = page?.content

  const handleSearch = () => {
    // 증상/병원명 검색 → 병원 검색 결과로 이동(AI 상담 연동은 계약 확정 후).
    navigate(query.trim() ? `/hospitals?q=${encodeURIComponent(query.trim())}` : '/hospitals')
  }

  return (
    <div className="space-y-10 py-4">
      {/* 히어로 */}
      <section className="rounded-2xl bg-primary/5 p-8 sm:p-10">
        <h1 className="text-2xl font-bold sm:text-3xl">
          오늘은 우리 아이가
          <br />
          어디가 불편한가요?
        </h1>
        <p className="mt-2 text-sm text-muted-foreground">
          증상이나 병원명을 검색하면 맞는 병원을 찾아드려요.
        </p>
        <div className="mt-5 flex max-w-xl gap-2">
          <Input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && handleSearch()}
            placeholder="증상 또는 병원명을 입력하세요"
            className="h-11 bg-background"
          />
          <Button className="h-11 px-6" onClick={handleSearch}>
            <Search className="h-4 w-4" />
            검색
          </Button>
        </div>
        <p className="mt-3 text-sm">
          증상이 걱정되나요?{' '}
          <Link to="/ai" className="font-medium text-primary hover:underline">
            AI 증상 상담 받기 →
          </Link>
        </p>
      </section>

      {/* 내 주변 추천 병원 */}
      <section className="space-y-3">
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-semibold">내 주변 추천 병원</h2>
          <Link to="/hospitals" className="text-sm text-primary hover:underline">
            전체 보기
          </Link>
        </div>
        <div className="grid gap-3 sm:grid-cols-3">
          {hospitals?.slice(0, 3).map((h) => (
            <Card key={h.hospitalId}>
              <CardContent className="space-y-2 p-5">
                <div className="flex items-center gap-2">
                  <Link
                    to={`/hospitals/${h.hospitalId}`}
                    className="font-semibold hover:underline"
                  >
                    {h.name}
                  </Link>
                  {h.reservationAvailable ? (
                    <Badge variant="success">예약 가능</Badge>
                  ) : (
                    <Badge variant="muted">{h.partnershipBadge ?? '예약 불가'}</Badge>
                  )}
                </div>
                <p className="flex items-center gap-1 text-xs text-muted-foreground">
                  <MapPin className="h-3.5 w-3.5" />
                  {h.address}
                  {h.distanceKm != null && (
                    <span className="ml-1">· {h.distanceKm}km</span>
                  )}
                </p>
              </CardContent>
            </Card>
          ))}
        </div>
      </section>
    </div>
  )
}
