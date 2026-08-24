// 스태프 예약 목록의 날짜 그룹핑·정렬·지연 판정 헬퍼.
//
// 목록 API에 날짜 필터·정렬이 없어(SA §8-6) 불러온 **페이지 안에서만** 클라이언트가 정리한다.
// 서버는 requestedAt DESC로 페이지를 자르므로, 여기서 정렬한 결과가 전체 큐의 시간순은 아니다
// (임박한 예약이 다음 페이지에 남을 수 있다 — PR #194 리뷰 P2). 화면은 이 한계를 문구로 밝히고,
// 전체 기준 정렬은 서버가 reservedAt으로 정렬·페이징하는 계약이 생긴 뒤에 가능하다.
//
// 시각은 오프셋 없는 문자열이라 Asia/Seoul 오프셋을 붙여 파싱한다(lib/seoulTime.parseSeoulDateTime).
import type { StaffReservationListItem } from './types'
import { ReservationStatus } from '@/types/enums'
import {
  groupByDateKey,
  parseSeoulDateTime,
  todaySeoulKey,
  type DatedGroup,
} from '@/lib/seoulTime'

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

// 스태프 목록 그룹 타입. 공용 groupByDateKey의 DatedGroup을 예약 항목으로 특수화한 별칭이다.
export type DateGroup = DatedGroup<StaffReservationListItem>

// 예약 시각으로 정렬한 뒤 날짜별로 묶는다. ascending=true면 이른 시간부터.
// 공용 groupByDateKey에 reservedAt 접근자를 넘긴 얇은 래퍼다 — 정렬·그룹핑 로직은 lib/seoulTime 한 곳에만 둔다.
// 정렬 범위는 넘겨받은 배열(=현재 페이지)뿐이다. 파일 상단 주석의 한계를 참고.
export function groupByDate(
  items: StaffReservationListItem[],
  ascending: boolean,
  todayKey: string = todaySeoulKey(),
): DateGroup[] {
  return groupByDateKey(items, (item) => item.reservedAt, ascending, todayKey)
}

/*
  내원 대기 상태(확정·노쇼 확인중)인데 예약 시각이 이미 지났으면 지연 — 자동 노쇼 판정 대상이다.

  `new Date(reservedAt)`으로 비교하면 오프셋 없는 값이 브라우저 로컬로 해석돼, UTC 환경에서는
  같은 예약이 최대 9시간 다르게 지연으로 표시된다(리뷰 P2). 서울 오프셋을 붙여 실제 순간으로
  바꾼 뒤 비교한다 — `WaitlistsPage`의 카운트다운이 쓰던 것과 같은 방식이다.
  기준 시각(ms)을 인자로 받아 테스트가 실행 환경 타임존과 무관하게 고정된다.
*/
export function isOverdue(
  item: StaffReservationListItem,
  nowMs: number = Date.now(),
): boolean {
  const awaitingArrival =
    item.reservationStatus === ReservationStatus.CONFIRMED ||
    item.reservationStatus === ReservationStatus.NO_SHOW_PENDING
  return awaitingArrival && parseSeoulDateTime(item.reservedAt) < nowMs
}
