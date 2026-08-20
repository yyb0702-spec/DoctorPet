// 타임존 없는 백엔드 시각(LocalDateTime)을 다루는 유틸. 이 프로젝트의 기준 시각은 Asia/Seoul이다.
//
// 백엔드는 "2026-08-20T09:00:00"처럼 오프셋 없는 문자열을 준다. 이걸 `new Date(...)`로 파싱하면
// **브라우저 로컬 타임존**으로 해석되므로, UTC 환경에서는 같은 예약이 9시간 어긋난 시각·지연 판정으로
// 보인다(PR #194 리뷰 P2). 그래서 비교·표시는 모두 문자열 수준에서 Asia/Seoul 기준으로 처리한다.
//
// TODO: `features/hospitals/hooks.ts`의 `todaySeoul()`도 같은 일을 한다 — 열려 있는 PR과 충돌을
// 피하려고 지금은 옮기지 않았고, 그 PR이 병합되면 이 파일로 합친다.

// 지금 시각을 Asia/Seoul 기준의 "yyyy-MM-ddTHH:mm:ss"로 만든다.
// sv-SE 로케일이 "yyyy-MM-dd HH:mm:ss"를 주므로 공백만 'T'로 바꾸면 백엔드 문자열과 같은 모양이 된다.
export function nowSeoul(): string {
  return new Date()
    .toLocaleString('sv-SE', { timeZone: 'Asia/Seoul' })
    .replace(' ', 'T')
}

/** 오늘 날짜(Asia/Seoul) "yyyy-MM-dd". */
export function todaySeoulKey(): string {
  return nowSeoul().slice(0, 10)
}

/** 날짜 키에 일수를 더한다. UTC 자정 기준으로 계산해 로컬 타임존에 흔들리지 않는다. */
export function shiftDateKey(dateKey: string, days: number): string {
  const base = new Date(`${dateKey}T00:00:00Z`)
  base.setUTCDate(base.getUTCDate() + days)
  return base.toISOString().slice(0, 10)
}

/** 오프셋 없는 시각 문자열의 "HH:mm"을 그대로 읽는다(파싱하지 않으므로 타임존 영향이 없다). */
export function naiveTimeLabel(dateTime: string): string {
  return dateTime.slice(11, 16)
}

/** 오프셋 없는 시각 문자열을 "8월 20일 09:00"처럼 표시한다. */
export function naiveDateTimeLabel(dateTime: string): string {
  const [year, month, day] = dateTime.slice(0, 10).split('-').map(Number)
  return `${year}년 ${month}월 ${day}일 ${naiveTimeLabel(dateTime)}`
}
