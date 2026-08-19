// AI 증상 상담 (실연동, POST /api/ai/consultations). 증상+축종(+선택 위치) → 구조화 분석·추천 병원.
// 참고용 정보이며 진료를 대체하지 않는다(응답의 disclaimer를 항상 함께 표시).
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { MapPin, AlertTriangle, Stethoscope, CheckCircle2 } from 'lucide-react'
import { useAiConsultation } from '@/features/ai/hooks'
import type { UrgencyLevel } from '@/features/ai/types'
import { PetSpecies } from '@/types/enums'
import { SPECIES_ORDER, SPECIES_LABEL } from '@/lib/species'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'
import { ApiError } from '@/lib/api/error'

const MAX = 1000

const URGENCY: Record<UrgencyLevel, { label: string; tone: string }> = {
  LOW: {
    label: '응급도 낮음',
    tone: 'bg-emerald-50 text-emerald-900 border-emerald-200',
  },
  MODERATE: {
    label: '응급도 보통',
    tone: 'bg-amber-50 text-amber-900 border-amber-200',
  },
  HIGH: {
    label: '응급도 높음 · 서둘러 진료하세요',
    tone: 'bg-destructive/10 text-destructive border-destructive/30',
  },
}

export function AiConsultationPage() {
  const consult = useAiConsultation()
  const [symptomText, setSymptomText] = useState('')
  const [species, setSpecies] = useState<PetSpecies | null>(null)
  const [region, setRegion] = useState('')
  const [coords, setCoords] = useState<{
    latitude: number
    longitude: number
  } | null>(null)
  const [geoError, setGeoError] = useState<string | null>(null)

  const trimmed = symptomText.trim()
  const overLimit = symptomText.length > MAX
  const canSubmit = trimmed.length > 0 && !overLimit && species != null

  const useMyLocation = () => {
    setGeoError(null)
    if (!navigator.geolocation) {
      setGeoError('이 브라우저는 위치를 지원하지 않아요.')
      return
    }
    navigator.geolocation.getCurrentPosition(
      (pos) =>
        setCoords({
          latitude: pos.coords.latitude,
          longitude: pos.coords.longitude,
        }),
      () => setGeoError('위치를 가져오지 못했어요. 지역명으로 입력해 주세요.'),
    )
  }

  const handleSubmit = () => {
    if (!canSubmit || species == null) return
    consult.mutate({
      symptomText: trimmed,
      species,
      region: region.trim() || undefined,
      latitude: coords?.latitude,
      longitude: coords?.longitude,
    })
  }

  const result = consult.data

  return (
    <div className="mx-auto max-w-2xl space-y-5 py-2">
      <div className="space-y-1">
        <h1 className="flex items-center gap-2 text-2xl font-bold">
          <Stethoscope className="h-6 w-6 text-primary" />
          AI 증상 상담
        </h1>
        <p className="text-sm text-muted-foreground">
          증상을 적으면 의심 부위·필요한 진료와 맞는 병원을 알려드려요. 참고용
          정보이며 진료를 대체하지 않아요.
        </p>
      </div>

      {/* 입력 */}
      <Card>
        <CardContent className="space-y-4 p-5">
          <div className="space-y-1.5">
            <label htmlFor="symptom" className="text-sm font-medium">
              증상
            </label>
            <textarea
              id="symptom"
              value={symptomText}
              onChange={(e) => setSymptomText(e.target.value)}
              rows={4}
              placeholder="예: 어제부터 밥을 안 먹고 구토를 두 번 했어요."
              className="w-full resize-none rounded-md border bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            />
            <p
              className={cn(
                'text-right text-xs',
                overLimit ? 'text-destructive' : 'text-muted-foreground',
              )}
            >
              {symptomText.length} / {MAX}
            </p>
          </div>

          <div className="space-y-1.5">
            <p className="text-sm font-medium">반려동물</p>
            <div className="flex flex-wrap gap-2">
              {SPECIES_ORDER.map((s) => (
                <Button
                  key={s}
                  type="button"
                  size="sm"
                  variant={species === s ? 'default' : 'outline'}
                  onClick={() => setSpecies(s)}
                >
                  {SPECIES_LABEL[s]}
                </Button>
              ))}
            </div>
          </div>

          <div className="space-y-1.5">
            <label htmlFor="region" className="text-sm font-medium">
              지역 <span className="text-muted-foreground">(선택)</span>
            </label>
            <Input
              id="region"
              value={region}
              onChange={(e) => setRegion(e.target.value)}
              placeholder="예: 서울 강남구"
            />
            <div className="flex items-center gap-2">
              <Button
                type="button"
                size="sm"
                variant="outline"
                onClick={useMyLocation}
              >
                <MapPin className="h-4 w-4" />
                {coords ? '내 위치 적용됨' : '내 위치 사용'}
              </Button>
              {geoError && (
                <span className="text-xs text-muted-foreground">{geoError}</span>
              )}
            </div>
          </div>

          {consult.isError && (
            <p className="text-sm text-destructive">
              {consult.error instanceof ApiError
                ? consult.error.message
                : '상담 요청에 실패했어요. 잠시 후 다시 시도해 주세요.'}
            </p>
          )}

          <Button
            className="w-full"
            disabled={!canSubmit || consult.isPending}
            onClick={handleSubmit}
          >
            {consult.isPending ? '분석 중…' : 'AI 상담 받기'}
          </Button>
        </CardContent>
      </Card>

      {/* 결과 */}
      {result && (
        <div className="space-y-4">
          {/* 응급도 배너 */}
          <div
            className={cn(
              'flex items-center gap-2 rounded-xl border p-4 text-sm font-semibold',
              URGENCY[result.structured.urgencyLevel].tone,
            )}
          >
            <AlertTriangle className="h-5 w-5 shrink-0" />
            {URGENCY[result.structured.urgencyLevel].label}
          </div>

          {result.message && (
            <p className="text-sm text-muted-foreground">{result.message}</p>
          )}

          {result.fallback && (
            <p className="rounded-md bg-amber-50 p-3 text-xs text-amber-900">
              일부 정보 조회가 제한되어 결과가 간략할 수 있어요.
            </p>
          )}

          <Card>
            <CardHeader>
              <CardTitle>분석 결과</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4 text-sm">
              {result.structured.recommendVetVisit && (
                <div className="flex items-center gap-2 rounded-md bg-primary/5 p-3 font-medium text-primary">
                  <CheckCircle2 className="h-4 w-4" />
                  병원 방문을 권장해요.
                </div>
              )}

              {result.structured.possibleFocusAreas.length > 0 && (
                <div className="space-y-1.5">
                  <p className="font-medium">의심 부위</p>
                  <ul className="list-inside list-disc text-muted-foreground">
                    {result.structured.possibleFocusAreas.map((a) => (
                      <li key={a}>{a}</li>
                    ))}
                  </ul>
                </div>
              )}

              {result.structured.requiredCapabilities.length > 0 && (
                <div className="space-y-1.5">
                  <p className="font-medium">필요할 수 있는 진료</p>
                  <div className="flex flex-wrap gap-1.5">
                    {result.structured.requiredCapabilities.map((c) => (
                      <Badge key={c} variant="secondary">
                        {c}
                      </Badge>
                    ))}
                  </div>
                </div>
              )}

              {result.structured.preVisitCheckpoints.length > 0 && (
                <div className="space-y-1.5">
                  <p className="font-medium">방문 전 확인할 점</p>
                  <ul className="list-inside list-disc text-muted-foreground">
                    {result.structured.preVisitCheckpoints.map((c) => (
                      <li key={c}>{c}</li>
                    ))}
                  </ul>
                </div>
              )}
            </CardContent>
          </Card>

          {/* 추천 병원 */}
          <div className="space-y-2">
            <div className="flex items-center justify-between">
              <h2 className="text-lg font-semibold">맞는 병원</h2>
              <Link
                to="/hospitals"
                className="text-sm text-primary hover:underline"
              >
                더 찾기
              </Link>
            </div>
            {result.locationRecommended && !coords && (
              <p className="rounded-md bg-accent p-3 text-xs text-accent-foreground">
                위치를 입력하면 가까운 병원을 더 정확히 찾아드려요.
              </p>
            )}
            {result.hospitals.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                조건에 맞는 병원을 찾지 못했어요. 병원 검색에서 직접 찾아보세요.
              </p>
            ) : (
              <div className="grid gap-3 sm:grid-cols-2">
                {result.hospitals.map((h) => (
                  <Card key={h.hospitalId}>
                    <CardContent className="space-y-2 p-4">
                      <div className="flex flex-wrap items-center gap-2">
                        <Link
                          to={`/hospitals/${h.hospitalId}`}
                          className="font-semibold hover:underline"
                        >
                          {h.name}
                        </Link>
                        {h.reservationAvailable ? (
                          <Badge variant="success">예약 가능</Badge>
                        ) : (
                          <Badge variant="muted">
                            {h.partnershipBadge ?? '예약 불가'}
                          </Badge>
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
            )}
          </div>

          {/* 면책 */}
          {result.disclaimer && (
            <p className="border-t pt-3 text-xs text-muted-foreground">
              {result.disclaimer}
            </p>
          )}
        </div>
      )}
    </div>
  )
}
