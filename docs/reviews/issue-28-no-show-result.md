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
- 노쇼·정정 시 슬롯은 `RESERVED` 상태를 유지한다.

## 동시성·멱등 처리

- 예약 상태 변경은 `WHERE status = 기대 상태 AND hospital_id = 자병원` 조건부 UPDATE로 보호한다.
- 조건부 UPDATE가 0행이면 잠금 조회로 최신 커밋 상태를 확인해 MySQL `REPEATABLE READ`의 이전 스냅샷 오판을 막는다.
- 자동 판정과 수동 확정이 겹치면 최종 상태는 `NO_SHOW`가 되고 수동 판정 이력은 반드시 남는다.
- `(reservation_id, event_type)` UNIQUE와 `ON DUPLICATE KEY UPDATE id = id`로 같은 사건만 멱등 처리한다.
- 기존 중복 이력은 최초 한 건만 보존한 뒤 UNIQUE를 추가·검증하는 1회성 마이그레이션으로 정리한다.
- 수동 트랜잭션의 스냅샷 직후 자동 커밋을 강제해 `FOR UPDATE`가 최신 상태를 읽는지 결정적으로 검증한다.
- 기존 이력은 수정하지 않고 정정 이력을 새 행으로 추가한다.

## 체크리스트 결과

- [x] Issue #28과 PRD·SA·검증 가이드 확인
- [x] Controller / Service / Repository 책임 분리
- [x] 병원 직원·자병원 인가 적용
- [x] 허용 상태·시간·필수 사유 검증
- [x] 자동/수동 경쟁과 수동 우선 규칙 검증
- [x] 처리자·사유 append-only 감사 이력 적용
- [x] 기존 중복 정리·UNIQUE 보장 마이그레이션
- [x] Repository 병원 ID 조건과 `/restore` 보호자 403 검증
- [x] API·DB·경량 문서 갱신
- [x] 하네스 검사 실행
- [x] 범위 밖 자동 스케줄러(Issue #29), 알림 저장(Issue #39) 미구현

## 검증 기록

| Level | 실행한 명령 | 결과 | 확인한 것 | 아직 모르는 것 |
| --- | --- | --- | --- | --- |
| 1 | `./gradlew test --tests "com.doctorpet.domain.reservation.service.HospitalReservationApplicationServiceTest" --tests "com.doctorpet.domain.reservation.controller.HospitalReservationControllerTest"` | PASS | 상태·시간·사유·소유권·위임 | 없음 |
| 2 | `./gradlew test --tests "com.doctorpet.domain.reservation.controller.HospitalReservationAuthorizationTest"` | PASS | 401/403 경로, 병원 직원 성공, 요청 검증 400 | 없음 |
| 3 | `./gradlew test --tests "com.doctorpet.domain.reservation.service.HospitalNoShowIntegrationTest"` | PASS | 실제 MySQL 상태·이력·슬롯·결정적 최신 읽기·병원 ID 조건·DB 오류 전파 | Issue #29 스케줄러 자체 실행 |
| 3 | `./gradlew test --tests "com.doctorpet.domain.reservation.migration.ReservationEventUniqueMigrationIntegrationTest"` | PASS | 기존 중복 정리·UNIQUE 추가·마커 기록·제약 유실 fail-fast | 다중 인스턴스 동시 부팅 |
| 1·3 | `./gradlew test --tests "com.doctorpet.domain.reservation.*"` | PASS | 예약 도메인 전체 회귀 | 타 도메인 외부 인프라 테스트 |
| 문서 | `python scripts/harness_check.py` | PASS | 정본·경량 문서 링크와 구조 | 없음 |
| 전체 | `./gradlew clean build` | FAIL | 431건 중 예약 테스트는 통과했으나 10건이 로컬 `MAIL_PROVIDER` 미설정·Redis 미기동으로 실패 | MySQL·Redis·메일 환경변수가 준비된 CI 결과 |

Level 5 로컬 기동은 **NO**다. 이번 API는 MVC·서비스·실제 MySQL 통합 테스트로 필요한 경계를 검증했고, 로컬 기본 프로필은 기존 `EmailGateway` 설정 누락으로 전체 컨텍스트가 시작되지 않는다.

Level 6 실제 HTTP 호출은 **NO**다. 인증·요청 계약은 `MockMvc`, 상태·동시성은 실제 MySQL 통합 테스트로 분리 검증했다.
