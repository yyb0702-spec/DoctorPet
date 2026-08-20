// 관심 병원(찜) 목록 — 조회 + 하트로 즉시 해제.
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { MapPin } from 'lucide-react'
import { useFavoriteHospitals } from '@/features/hospitals/hooks'
import { FavoriteButton } from '@/features/hospitals/FavoriteButton'
import { Card } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { EmptyState, ErrorState, PageLoader } from '@/components/common/States'

const PAGE_SIZE = 20

// 찜 목록에는 예약 가능 여부가 없다(응답 계약에 없음) — 영업·제휴 상태만 배지로 보여준다.
const BUSINESS_LABEL: Record<string, string> = {
  CLOSED_TEMP: '임시휴업',
  CLOSED: '폐업',
}

export function FavoriteHospitalsPage() {
  // 백엔드 페이지 파라미터는 1-base다(검색과 같은 규약).
  const [page, setPage] = useState(1)
  const { data, isLoading, isError, refetch, isFetching } = useFavoriteHospitals(
    page,
    PAGE_SIZE,
  )

  if (isLoading) return <PageLoader />
  if (isError) return <ErrorState onRetry={() => refetch()} />

  const content = data?.content ?? []

  return (
    <div className="mx-auto max-w-2xl space-y-4">
      <h1 className="text-2xl font-bold">관심 병원</h1>

      {content.length === 0 ? (
        <EmptyState message="찜한 병원이 없습니다. 병원 검색에서 하트를 눌러 담아 보세요." />
      ) : (
        <div className="space-y-3">
          {content.map((h) => (
            <Card key={h.hospitalId}>
              <div className="flex items-center justify-between gap-4 p-5">
                <div className="space-y-1">
                  <div className="flex items-center gap-2">
                    <span className="font-semibold">{h.name}</span>
                    {h.partnershipStatus === 'PARTNER' ? (
                      <Badge variant="success">제휴 병원</Badge>
                    ) : (
                      <Badge variant="muted">제휴 전 병원</Badge>
                    )}
                    {h.businessStatus !== 'OPEN' && (
                      <Badge variant="destructive">
                        {BUSINESS_LABEL[h.businessStatus] ?? h.businessStatus}
                      </Badge>
                    )}
                  </div>
                  <span className="flex items-center gap-1 text-sm text-muted-foreground">
                    <MapPin className="h-3.5 w-3.5" />
                    {h.address}
                  </span>
                </div>
                <div className="flex items-center gap-1">
                  {/*
                    여기서 해제하면 목록에서 빠진다(hooks가 찜 목록을 무효화해 다시 읽는다).
                    낙관적으로 행을 지우지는 않는다 — 실패 시 되살리는 편이 더 어색하다.
                  */}
                  <FavoriteButton hospitalId={h.hospitalId} favorite={h.favorite} />
                  <Button asChild size="sm" variant="outline">
                    <Link to={`/hospitals/${h.hospitalId}`}>상세</Link>
                  </Button>
                </div>
              </div>
            </Card>
          ))}
        </div>
      )}

      {data && data.totalPages > 1 && (
        <div className="flex items-center justify-center gap-3 pt-2">
          <Button
            variant="outline"
            size="sm"
            disabled={data.first || isFetching}
            onClick={() => setPage(page - 1)}
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
            onClick={() => setPage(page + 1)}
          >
            다음
          </Button>
        </div>
      )}
    </div>
  )
}
