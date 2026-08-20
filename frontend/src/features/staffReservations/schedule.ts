// 스태프 예약 목록의 날짜 그룹핑·정렬·지연 판정 헬퍼.
//
// 목록 API에 날짜 필터·정렬이 없어(SA §8-6) 불러온 **페이지 안에서만** 클라이언트가 정리한다.
// 서버는 requestedAt DESC로 페이지를 자르므로, 여기서 정렬한 결과가 전체 큐의 시간순은 아니다
// (임박한 예약이 다음 페이지에 남을 수 있다 — PR #194 리뷰 P2). 화면은 이 한계를 문구로 밝히고,
// 전체 기준 정렬은 서버가 reservedAt으로 정렬·페이징하는 계약이 생긴 뒤에 가능하다.
//
// 시각은 오프셋 없는 문자열이라 Date로 파싱하지 않고 Asia/Seoul 기준 문자열로 비교한다(lib/seoulTime).
import type { StaffReservationListItem } from './types'
import { ReservationStatus } from '@/types/enums'
import { nowSeoul, shiftDateKey, todaySeoulKey } from '@/lib/seoulTime'

// 다가오는(예정) 상태 — 이른 시간부터 보여준다. 그 외(이력) 상태는 최근부터.
const UPCOMING_STATUSES: ReservationStatus[] = [
  ReservationStatus.REQUESTED,
  ReservationStatus.CONFIRMED,
  ReservationStatus.NO_SHOW_PENDING,
  ReservationStatus.CHECKED_IN,
  ReservationStatus.IN_TREATMENT,
]

export function isUpcomingStatus(status: ReservationStatus): boolean {
  return UPCOMING_STATUSES.includes(status)
}

// reservedAt("yyyy-MM-ddTHH:mm:ss", 타임존 없음)의 날짜 부분만 그룹 키로 쓴다 — Date 파싱의
// 타임존 흔들림 없이 안정적이다.
function dateKey(reservedAt: string): string {
  return reservedAt.slice(0, 10)
}

export interface DateGroup {
  date: string // yyyy-MM-dd
  label: string // "오늘"·"내일"·"8월 19일 (수)"
  items: StaffReservationListItem[]
}

/*
  "오늘"·"내일" 판정은 Asia/Seoul 날짜로 한다 — 브라우저 로컬 날짜를 쓰면 UTC 환경에서 한국의
  오늘 예약이 "내일"로 붙는다. 기준 날짜를 인자로 받아 테스트가 타임존과 무관하게 고정된다.
*/
export function dateLabel(key: string, todayKey: string = todaySeoulKey()): string {
  if (key === todayKey) return '오늘'
  if (key === shiftDateKey(todayKey, 1)) return '내일'
  // UTC 자정으로 파싱하고 UTC로 표시해, 로컬 타임존이 날짜·요일을 밀지 않게 한다.
  return new Date(`${key}T00:00:00Z`).toLocaleDateString('ko-KR', {
    timeZone: 'UTC',
    month: 'long',
    day: 'numeric',
    weekday: 'short',
  })
}

// 예약 시각으로 정렬한 뒤 날짜별로 묶는다. ascending=true면 이른 시간부터.
// 정렬 범위는 넘겨받은 배열(=현재 페이지)뿐이다. 파일 상단 주석의 한계를 참고.
export function groupByDate(
  items: StaffReservationListItem[],
  ascending: boolean,
  todayKey: string = todaySeoulKey(),
): DateGroup[] {
  const sorted = [...items].sort((a, b) => {
    const cmp = a.reservedAt.localeCompare(b.reservedAt)
    return ascending ? cmp : -cmp
  })

  const groups: DateGroup[] = []
  const indexByKey = new Map<string, number>()
  for (const item of sorted) {
    const key = dateKey(item.reservedAt)
    let idx = indexByKey.get(key)
    if (idx === undefined) {
      idx = groups.length
      indexByKey.set(key, idx)
      groups.push({ date: key, label: dateLabel(key, todayKey), items: [] })
    }
    groups[idx].items.push(item)
  }
  return groups
}

/*
  내원 대기 상태(확정·노쇼 확인중)인데 예약 시각이 이미 지났으면 지연 — 자동 노쇼 판정 대상이다.

  `new Date(reservedAt)`으로 비교하면 오프셋 없는 값이 브라우저 로컬로 해석돼, UTC 환경에서는
  같은 예약이 최대 9시간 다르게 지연으로 표시된다(리뷰 P2). 같은 모양의 문자열끼리 비교하면
  타임존이 개입하지 않는다 — 둘 다 "yyyy-MM-ddTHH:mm:ss" 형식이라 사전순 비교가 시간순과 같다.
*/
export function isOverdue(
  item: StaffReservationListItem,
  nowSeoulTime: string = nowSeoul(),
): boolean {
  const awaitingArrival =
    item.reservationStatus === ReservationStatus.CONFIRMED ||
    item.reservationStatus === ReservationStatus.NO_SHOW_PENDING
  return awaitingArrival && item.reservedAt < nowSeoulTime
}
