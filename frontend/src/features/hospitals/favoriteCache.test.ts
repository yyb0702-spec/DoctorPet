// 찜 낙관적 편집이 상세·검색 두 응답 모양을 모두 다루는지, 무관한 캐시를 망가뜨리지 않는지 고정한다.
import { describe, expect, it } from 'vitest'
import { withFavoriteFlag } from './favoriteCache'

describe('withFavoriteFlag', () => {
  it('병원 상세(단건)의 favorite를 바꾼다', () => {
    const detail = { hospitalId: 1, name: 'A', favorite: false }

    expect(withFavoriteFlag(detail, 1, true)).toEqual({
      hospitalId: 1,
      name: 'A',
      favorite: true,
    })
    // 원본은 손대지 않는다(react-query 캐시 불변성).
    expect(detail.favorite).toBe(false)
  })

  it('다른 병원의 상세는 그대로 둔다', () => {
    const detail = { hospitalId: 2, favorite: false }
    expect(withFavoriteFlag(detail, 1, true)).toBe(detail)
  })

  it('검색 페이지 응답은 해당 병원 항목만 바꾼다', () => {
    const page = {
      content: [
        { hospitalId: 1, favorite: false },
        { hospitalId: 2, favorite: true },
      ],
      page: 1,
      totalElements: 2,
    }

    const next = withFavoriteFlag(page, 1, true)

    expect(next.content).toEqual([
      { hospitalId: 1, favorite: true },
      { hospitalId: 2, favorite: true },
    ])
    // 페이지 메타는 유지된다.
    expect(next.page).toBe(1)
    expect(next.totalElements).toBe(2)
  })

  it('바뀔 항목이 없으면 같은 참조를 유지한다(불필요한 리렌더 방지)', () => {
    const page = { content: [{ hospitalId: 9, favorite: false }] }
    expect(withFavoriteFlag(page, 1, true)).toBe(page)
  })

  it('병원 응답이 아닌 캐시(슬롯 조회 등)는 그대로 통과시킨다', () => {
    const slots = { selectedDate: '2026-08-20', slots: [{ slotId: 1 }] }
    expect(withFavoriteFlag(slots, 1, true)).toBe(slots)
    expect(withFavoriteFlag(null, 1, true)).toBeNull()
  })
})
