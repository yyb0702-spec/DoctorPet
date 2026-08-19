// 내 후기 캐시 — 서버에 "내 후기 조회" 경로가 없어 이 캐시가 수정·삭제 진입점을 잡는다.
// 낡거나 깨진 값이 화면을 잘못 몰고 가지 않는지 검증한다.
import { beforeEach, describe, expect, it } from 'vitest'
import {
  clearMyReview,
  getMyReview,
  isReviewOpportunityUsed,
  markMyReviewDeleted,
  saveMyReview,
} from './myReviewCache'
import type { Review } from './types'

const STORAGE_KEY = 'doctorpet.myReviews'

function review(overrides: Partial<Review> = {}): Review {
  return {
    reviewId: 10,
    reservationId: 667,
    hospitalId: 8643,
    memberId: 554,
    rating: 4.5,
    content: '친절했어요',
    createdAt: '2026-08-19T10:00:00',
    updatedAt: '2026-08-19T10:00:00',
    ...overrides,
  }
}

beforeEach(() => {
  localStorage.clear()
})

describe('myReviewCache', () => {
  it('저장한 적 없으면 null이다 — 작성 폼을 보여야 하는 상태', () => {
    expect(getMyReview(667)).toBeNull()
  })

  it('저장하면 수정 폼을 채울 값(reviewId·평점·내용)을 돌려준다', () => {
    saveMyReview(review())
    expect(getMyReview(667)).toEqual({
      reviewId: 10,
      rating: 4.5,
      content: '친절했어요',
    })
  })

  it('예약별로 따로 보관한다 — 다른 예약의 후기가 섞이지 않는다', () => {
    saveMyReview(review({ reviewId: 10, reservationId: 667 }))
    saveMyReview(review({ reviewId: 11, reservationId: 668, content: '보통' }))
    expect(getMyReview(667)?.reviewId).toBe(10)
    expect(getMyReview(668)?.reviewId).toBe(11)
  })

  it('같은 예약을 다시 저장하면 최신 내용으로 덮어쓴다(수정 반영)', () => {
    saveMyReview(review({ rating: 4.5, content: '친절했어요' }))
    saveMyReview(review({ rating: 3, content: '수정한 내용' }))
    expect(getMyReview(667)).toEqual({
      reviewId: 10,
      rating: 3,
      content: '수정한 내용',
    })
  })

  it('삭제하면 다시 null이 되고 다른 예약은 남는다', () => {
    saveMyReview(review({ reviewId: 10, reservationId: 667 }))
    saveMyReview(review({ reviewId: 11, reservationId: 668 }))
    clearMyReview(667)
    expect(getMyReview(667)).toBeNull()
    expect(getMyReview(668)?.reviewId).toBe(11)
  })

  it('없는 예약을 지워도 조용히 넘어간다', () => {
    expect(() => clearMyReview(999)).not.toThrow()
  })

  it('내가 지우면(markMyReviewDeleted) 후기는 null이지만 작성 기회는 소진된 것으로 본다', () => {
    saveMyReview(review())
    markMyReviewDeleted(667)
    // 지운 뒤에는 수정·삭제할 후기가 없다.
    expect(getMyReview(667)).toBeNull()
    // 하지만 작성 기회는 되돌아오지 않으므로 재작성 폼을 막아야 한다.
    expect(isReviewOpportunityUsed(667)).toBe(true)
  })

  it('clearMyReview는 표식까지 지워 다시 작성 가능 상태로 되돌린다(환불·계정 불일치 회복)', () => {
    saveMyReview(review())
    markMyReviewDeleted(667)
    clearMyReview(667)
    expect(getMyReview(667)).toBeNull()
    // 표식이 사라져 폼을 다시 보여준다 — 서버 판단에 맡긴다.
    expect(isReviewOpportunityUsed(667)).toBe(false)
  })

  it('저장·삭제한 적 없으면 작성 기회는 아직 남아 있다', () => {
    expect(isReviewOpportunityUsed(667)).toBe(false)
  })

  it('삭제 표식이 있어도 다른 예약에는 영향이 없다', () => {
    saveMyReview(review({ reviewId: 10, reservationId: 667 }))
    saveMyReview(review({ reviewId: 11, reservationId: 668 }))
    markMyReviewDeleted(667)
    expect(isReviewOpportunityUsed(667)).toBe(true)
    expect(isReviewOpportunityUsed(668)).toBe(false)
    expect(getMyReview(668)?.reviewId).toBe(11)
  })

  it('저장 없이 markMyReviewDeleted를 불러도 조용히 넘어간다(표식도 남기지 않는다)', () => {
    expect(() => markMyReviewDeleted(999)).not.toThrow()
    expect(isReviewOpportunityUsed(999)).toBe(false)
  })

  it('저장값이 깨져 있으면(JSON 아님) null로 취급한다', () => {
    localStorage.setItem(STORAGE_KEY, 'not-json')
    expect(getMyReview(667)).toBeNull()
  })

  it('배열·원시값이 들어와도 null로 취급한다 — 형태를 신뢰하지 않는다', () => {
    localStorage.setItem(STORAGE_KEY, '[1,2,3]')
    expect(getMyReview(667)).toBeNull()
    localStorage.setItem(STORAGE_KEY, '"string"')
    expect(getMyReview(667)).toBeNull()
  })

  it('항목의 필드 타입이 어긋나면 null로 취급한다', () => {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ '667': { reviewId: 'ten', rating: 4.5, content: 'x' } }),
    )
    expect(getMyReview(667)).toBeNull()
  })

  it('필드가 빠져 있어도 null로 취급한다', () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ '667': { reviewId: 10 } }))
    expect(getMyReview(667)).toBeNull()
  })
})
