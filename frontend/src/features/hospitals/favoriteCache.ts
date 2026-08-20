// 찜 토글의 낙관적 편집 — 캐시에 이미 들어 있는 병원 응답의 favorite 플래그만 갈아끼운다.
// 병원 상세(단건)와 검색(페이지 응답)이 같은 병원을 각자 들고 있어서, 한 곳만 고치면
// 하트가 화면에 따라 다르게 보인다. 모양이 다른 두 응답을 한 함수로 처리한다.

interface HasFavorite {
  hospitalId: number
  favorite: boolean
}

function isHospitalLike(value: unknown): value is HasFavorite {
  return (
    typeof value === 'object' &&
    value !== null &&
    typeof (value as HasFavorite).hospitalId === 'number' &&
    typeof (value as HasFavorite).favorite === 'boolean'
  )
}

/**
 * favorite 플래그를 바꾼 새 값을 돌려준다(원본은 손대지 않는다).
 * 병원 응답이 아닌 캐시(슬롯 조회 등)는 그대로 통과시킨다 — 접두사 무효화 범위에 섞여 들어온다.
 */
export function withFavoriteFlag<T>(
  data: T,
  hospitalId: number,
  favorite: boolean,
): T {
  if (isHospitalLike(data)) {
    return data.hospitalId === hospitalId ? { ...data, favorite } : data
  }
  // 페이지 응답(검색 결과): content 안의 해당 병원만 바꾼다.
  if (
    typeof data === 'object' &&
    data !== null &&
    Array.isArray((data as { content?: unknown }).content)
  ) {
    const page = data as unknown as { content: unknown[] }
    let changed = false
    const content = page.content.map((item) => {
      if (isHospitalLike(item) && item.hospitalId === hospitalId) {
        changed = true
        return { ...item, favorite }
      }
      return item
    })
    // 바뀐 게 없으면 같은 참조를 유지해 불필요한 리렌더를 만들지 않는다.
    return changed ? ({ ...page, content } as unknown as T) : data
  }
  return data
}
