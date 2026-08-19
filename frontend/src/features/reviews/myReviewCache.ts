// "이 예약에 내가 쓴 후기"를 브라우저에 캐시한다.
//
// 왜 필요한가: 백엔드에 내 후기를 되찾을 경로가 없다 —
//  - 예약 응답에 reviewed_at·reviewId가 없다
//  - 병원 후기 목록 항목은 익명(memberId 없음)이라 목록에서 내 것을 골라낼 수 없다
//  - 단건 조회(GET /reviews/{id}) 엔드포인트도 없다
// 그래서 작성 직후 응답으로 받은 후기를 캐시해 두고, 새로고침 후에도 수정·삭제를 이어간다.
//
// 서버가 정본이라는 점은 유지한다 — 캐시가 낡아 실제로는 없는 후기를 수정·삭제하려 하면
// 서버가 404/403으로 거절하고, 그때 이 캐시를 지워 스스로 회복한다(clearMyReview).
// 캐시가 없는 기기에서는 작성 폼이 다시 보이고, 중복 작성은 서버가 409로 막는다.
import type { Review } from './types'

const STORAGE_KEY = 'doctorpet.myReviews'

// 수정 폼을 채우는 데 필요한 최소값만 담는다(내용·평점은 캐시 시점 스냅샷).
export interface CachedReview {
  reviewId: number
  rating: number
  content: string
}

// deleted가 붙으면 "내가 이 예약의 후기를 지웠다"는 표식이다. 삭제해도 예약의 작성 기회
// (reservations.reviewed_at)는 복구되지 않아 재작성이 항상 409라, 폼을 다시 보여주지 않기
// 위해 지운 뒤에도 항목을 남긴다.
type StoredEntry = CachedReview & { deleted?: true }

type CacheShape = Record<string, StoredEntry>

// localStorage는 사생활 보호 모드·용량 초과·비활성 환경에서 던질 수 있어 항상 감싼다.
function readCache(): CacheShape {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return {}
    const parsed: unknown = JSON.parse(raw)
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
      return {}
    }
    return parsed as CacheShape
  } catch {
    return {}
  }
}

function writeCache(cache: CacheShape): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(cache))
  } catch {
    // 캐시는 편의 기능이라 실패해도 기능 자체는 계속 동작해야 한다(작성은 서버가 처리).
  }
}

function readEntry(reservationId: number): StoredEntry | null {
  const entry = readCache()[String(reservationId)]
  if (
    !entry ||
    typeof entry.reviewId !== 'number' ||
    typeof entry.rating !== 'number' ||
    typeof entry.content !== 'string'
  ) {
    return null
  }
  return entry
}

// 수정·삭제할 내 후기. 지운 뒤에는 null이다(표식만 남는다).
export function getMyReview(reservationId: number): CachedReview | null {
  const entry = readEntry(reservationId)
  if (!entry || entry.deleted) return null
  return { reviewId: entry.reviewId, rating: entry.rating, content: entry.content }
}

// 내가 이미 작성 기회를 써버렸는지(= 쓴 뒤 지웠는지). true면 작성 폼을 보여주지 않는다.
export function isReviewOpportunityUsed(reservationId: number): boolean {
  return readEntry(reservationId)?.deleted === true
}

export function saveMyReview(review: Review): void {
  const cache = readCache()
  cache[String(review.reservationId)] = {
    reviewId: review.reviewId,
    rating: review.rating,
    content: review.content,
  }
  writeCache(cache)
}

// 내가 지운 경우 — 작성 기회는 소진됐으므로 표식을 남겨 폼을 막는다.
export function markMyReviewDeleted(reservationId: number): void {
  const cache = readCache()
  const entry = cache[String(reservationId)]
  if (!entry) return
  cache[String(reservationId)] = { ...entry, deleted: true }
  writeCache(cache)
}

// 캐시가 실제 상태와 어긋난 경우(환불로 후기·기회가 되돌아갔거나 다른 계정) — 통째로 버려
// 서버 판단에 맡긴다. 여기서 표식을 남기면 다시 쓸 수 있는데도 폼을 막게 된다.
export function clearMyReview(reservationId: number): void {
  const cache = readCache()
  if (!(String(reservationId) in cache)) return
  delete cache[String(reservationId)]
  writeCache(cache)
}
