// 타임존 없는 백엔드 시각(LocalDateTime)을 다루는 유틸. 이 프로젝트의 기준 시각은 Asia/Seoul이다.
//
// 백엔드는 "2026-08-20T09:00:00"처럼 오프셋 없는 문자열을 준다. 이걸 `new Date(...)`로 그냥 파싱하면
// **브라우저 로컬 타임존**으로 해석되므로, UTC 환경에서는 같은 예약이 9시간 어긋난 시각·지연 판정으로
// 보인다(PR #188·#194 리뷰 P2).
//
// 파싱은 `parseSeoulDateTime`으로 통일한다 — 원래 `WaitlistsPage`가 들고 있던 함수를 여기로 옮긴 것이고,
// 서울은 DST가 없는 고정 UTC+9라 오프셋만 붙이면 정확하다. 로케일·ICU에 의존하지 않는 게 장점이다.
// 표시는 파싱하지 않고 문자열에서 그대로 읽는다(아래 naive* 함수).
//
// TODO: `features/hospitals/hooks.ts`의 `todaySeoul()`도 같은 계열이다 — 그 파일을 고치는 PR(#193)과
// 충돌을 피하려고 지금은 옮기지 않았고, 그 PR이 병합되면 이 파일로 합친다.

// 오프셋 없는 값에만 +09:00을 붙인다(이미 오프셋·Z가 있으면 그대로 신뢰한다).
export function parseSeoulDateTime(dateTime: string): number {
  const withOffset = /[Zz]|[+-]\d\d:\d\d$/.test(dateTime)
    ? dateTime
    : `${dateTime}+09:00`
  return new Date(withOffset).getTime()
}

/** 오늘 날짜(Asia/Seoul) "yyyy-MM-dd". en-CA 로케일이 ISO 형식을 준다. */
export function todaySeoulKey(): string {
  return new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Seoul' })
}

/** 날짜 키에 일수를 더한다. UTC 자정 기준으로 계산해 로컬 타임존에 흔들리지 않는다. */
export function shiftDateKey(dateKey: string, days: number): string {
  const base = new Date(`${dateKey}T00:00:00Z`)
  base.setUTCDate(base.getUTCDate() + days)
  return base.toISOString().slice(0, 10)
}

// 백엔드 LocalDateTime 형식("yyyy-MM-ddTHH:mm" 이상, 소수점 초는 있을 수 있음).
// 형식이 다르면 슬라이스가 엉뚱한 값을 만들어 시각이 조용히 사라지므로, 먼저 확인하고 아니면 원문을 준다.
const NAIVE_DATE_TIME = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/

/** 오프셋 없는 시각 문자열의 "HH:mm"을 그대로 읽는다(파싱하지 않으므로 타임존 영향이 없다). */
export function naiveTimeLabel(dateTime: string): string {
  if (!NAIVE_DATE_TIME.test(dateTime)) return dateTime
  return dateTime.slice(11, 16)
}

/** 오프셋 없는 시각 문자열을 "2026년 8월 20일 09:00"으로 표시한다. */
export function naiveDateTimeLabel(dateTime: string): string {
  if (!NAIVE_DATE_TIME.test(dateTime)) return dateTime
  const [year, month, day] = dateTime.slice(0, 10).split('-').map(Number)
  return `${year}년 ${month}월 ${day}일 ${naiveTimeLabel(dateTime)}`
}
