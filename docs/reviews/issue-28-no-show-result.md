# Issue #28 노쇼 수동 확정 및 정정 API 구현 결과

> 작성일: 2026년 7월 31일

## 구현 범위

- `PATCH /api/hospital/reservations/{reservationId}/no-show`
  - 병원 직원의 자병원 예약만 처리
  - 예약 시작 시각 이후 `CONFIRMED → NO_SHOW`
  - 자동 노쇼가 먼저 처리됐어도 수동 확인 이력을 멱등하게 추가
- `PATCH /api/hospital/reservations/{reservationId}/restore`
  - `NO_SHOW → CHECKED_IN`만 허용하며 반복 정정은 거부
- 두 API 모두 공백이 아닌 255자 이하 사유를 필수로 받는다.
- `reservation_events`에 사유, 처리자, 처리 시각을 append-only로 기록한다.
- 상태가 실제로 변경된 경우 `ReservationStatusChangedEvent`를 발행한다. 알림 저장·조회는 Issue #39 범위다.
- 노쇼·정정 시 슬롯은 `RESERVED` 상태를 유지한다.

## 동시성·멱등 처리

- 예약 상태 변경은 `WHERE status = 기대 상태 AND hospital_id = 자병원` 조건부 UPDATE로 보호한다.
- 조건부 UPDATE가 0행이면 잠금 조회로 최신 커밋 상태를 확인해 MySQL `REPEATABLE READ`의 이전 스냅샷 오판을 막는다.
- 자동 판정과 수동 확정이 겹치면 최종 상태는 `NO_SHOW`가 되고 수동 판정 이력은 반드시 남는다.
- `(reservation_id, event_type)` UNIQUE와 `INSERT IGNORE`로 같은 사건의 중복 이력을 막는다.
- 기존 이력은 수정하지 않고 정정 이력을 새 행으로 추가한다.

## 체크리스트 결과

- [x] Issue #28과 PRD·SA·검증 가이드 확인
- [x] Controller / Service / Repository 책임 분리
- [x] 병원 직원·자병원 인가 적용
- [x] 허용 상태·시간·필수 사유 검증
- [x] 자동/수동 경쟁과 수동 우선 규칙 검증
- [x] 처리자·사유 append-only 감사 이력 적용
- [x] 상태 변경 이벤트 발행
- [x] API·DB·경량 문서 갱신
- [x] 하네스 검사 실행
- [x] 범위 밖 자동 스케줄러(Issue #29), 알림 저장(Issue #39) 미구현

## 검증 기록

| Level | 실행한 명령 | 결과 | 확인한 것 | 아직 모르는 것 |
| --- | --- | --- | --- | --- |
| 1 | `./gradlew test --tests "com.doctorpet.domain.reservation.service.HospitalReservationApplicationServiceTest" --tests "com.doctorpet.domain.reservation.controller.HospitalReservationControllerTest"` | PASS | 상태·시간·사유·소유권·위임 | 없음 |
| 2 | `./gradlew test --tests "com.doctorpet.domain.reservation.controller.HospitalReservationAuthorizationTest"` | PASS | 401/403 경로, 병원 직원 성공, 요청 검증 400 | 없음 |
| 3 | `./gradlew test --tests "com.doctorpet.domain.reservation.service.HospitalNoShowIntegrationTest"` | PASS | 실제 MySQL 상태·이력·슬롯·자동/수동 경쟁 | Issue #29 스케줄러 자체 실행 |
| 1·3 | `./gradlew test --tests "com.doctorpet.domain.reservation.*"` | PASS | 예약 도메인 전체 회귀 | 타 도메인 외부 인프라 테스트 |
| 문서 | `python scripts/harness_check.py` | PASS | 정본·경량 문서 링크와 구조 | 없음 |
| 전체 | `./gradlew build` | FAIL | 424건 중 기존 10건이 `EmailGateway` 테스트 설정 누락·Redis 미기동으로 실패 | 외부 인프라가 준비된 전체 CI 결과 |

Level 5 로컬 기동은 **NO**다. 이번 API는 MVC·서비스·실제 MySQL 통합 테스트로 필요한 경계를 검증했고, 로컬 기본 프로필은 기존 `EmailGateway` 설정 누락으로 전체 컨텍스트가 시작되지 않는다.

Level 6 실제 HTTP 호출은 **NO**다. 인증·요청 계약은 `MockMvc`, 상태·동시성은 실제 MySQL 통합 테스트로 분리 검증했다.
