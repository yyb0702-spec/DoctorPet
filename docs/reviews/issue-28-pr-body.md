# PR 제목

`[feat] 노쇼 수동 확정 및 정정 API 구현`

## 개요

병원 직원이 자병원의 확정 예약을 수동으로 노쇼 처리하고, 잘못된 노쇼 판정을 체크인 상태로 정정할 수 있는 운영 API를 구현했습니다.

조건부 UPDATE와 append-only 이력으로 상태 전이와 감사 기록을 보호합니다. 자동 노쇼와 수동 처리가 동시에 실행되는 경우에도 수동 판정 이력이 누락되지 않도록 최신 상태 잠금 조회와 이벤트 멱등 처리를 적용했습니다.

## 작업 내용

### 예약 / 노쇼 수동 확정·정정

- `PATCH /api/hospital/reservations/{reservationId}/no-show` 구현
  - 병원 직원 및 자병원 예약 소유권 검증
  - 예약 시작 시각 이후 `CONFIRMED → NO_SHOW` 전이
  - 공백이 아닌 255자 이하 확정 사유 검증
- `PATCH /api/hospital/reservations/{reservationId}/restore` 구현
  - 현재 `NO_SHOW`인 예약만 `CHECKED_IN`으로 정정
  - 정정 사유 필수 검증 및 반복 정정 차단
- 노쇼·정정 후에도 예약 슬롯은 `RESERVED` 상태 유지

**수정 파일**

- `HospitalReservationController.java`
- `HospitalReservationApplicationService.java`
- `ReservationRepository.java`
- `ReservationErrorCode.java`
- `ReservationNoShowRequest.java`
- `ReservationNoShowRestoreRequest.java`

---

### 동시성·감사 이력·스키마 안전성

- 상태 조건과 병원 ID를 포함한 조건부 UPDATE 적용
- 자동 전이가 먼저 끝난 경우 잠금 조회로 최신 커밋 상태 확인
  - MySQL `REPEATABLE READ`의 이전 스냅샷으로 수동 처리를 잘못 거부하는 문제 방지
- `reservation_events`에 확정·정정 사유, 처리자 ID, 처리 시각을 append-only로 기록
- `(reservation_id, event_type)` UNIQUE 및 `ON DUPLICATE KEY UPDATE id = id`로 같은 사건만 멱등 처리
- 기존 중복 이력은 최초 한 건만 보존한 뒤 UNIQUE를 명시적으로 추가·검증
- `schema_migrations`의 `reservation_event_unique_v1` 마커로 1회 실행 보장
- 마커가 있는데 UNIQUE가 없으면 부팅 실패로 스키마 불일치 노출

**수정 파일**

- `ReservationEvent.java`
- `ReservationEventRepository.java`
- `ReservationEventUniqueMigrationRunner.java`

---

### Test

- 수동 확정 성공 및 허용 시간 검증
- 잘못된 상태, 사유 누락, 반복 정정 거부
- 보호자 접근 차단 및 병원 직원 접근 성공
- 타 병원 예약 소유권 검증
- 수동 확정·정정 시 사유와 처리자 이력 추가
- 동일 수동 요청의 이력 멱등성 검증
- 실제 MySQL에서 자동·수동 노쇼 동시 실행 검증
- 수동 스냅샷 직후 자동 커밋을 강제한 `FOR UPDATE` 최신 읽기 검증
- Repository의 타 병원 `hospitalId` 조건 검증
- 기존 중복 데이터 정리·UNIQUE 추가·마커 검증
- `/restore` 보호자 접근 403 검증
- 중복 키가 아닌 감사 이력 DB 오류 전파 검증
- 노쇼·정정 후 슬롯 `RESERVED` 유지 검증

**수정 파일**

- `HospitalReservationApplicationServiceTest.java`
- `HospitalReservationControllerTest.java`
- `HospitalReservationAuthorizationTest.java`
- `HospitalNoShowIntegrationTest.java`
- `ReservationEventUniqueMigrationIntegrationTest.java`

---

### Docs

- SA의 노쇼 API, 허용 시간, 처리자 이력 규칙 보강
- `reservation_events.processed_by`와 이벤트 UNIQUE 계약 반영
- 기존 데이터 중복 정리와 UNIQUE 보장 절차 반영
- 경량 SA·DB 문서와 구현 결과 문서 갱신

**수정 파일**

- `docs/architecture/DoctorPet-SA.md`
- `docs/lightweight/DoctorPet-SA-경량본.md`
- `docs/lightweight/DoctorPet-DB설계-경량본.md`
- `docs/reviews/issue-28-no-show-result.md`

## 변경 유형

- [x] feat
- [ ] fix
- [ ] refactor
- [x] docs
- [x] test
- [ ] chore

## 주요 검증 내용

가장 중요한 검증 대상은 자동 노쇼와 수동 확정이 동시에 실행되는 상황입니다. 둘 중 어느 요청이 먼저 DB 상태를 바꾸더라도 최종 상태는 `NO_SHOW`, 수동 이력은 정확히 1건, 슬롯은 `RESERVED`로 유지되는지 실제 MySQL 다중 스레드 테스트로 확인했습니다.

### 검증 기록

| Level | 실행한 명령 | 결과 | 확인한 것 | 아직 모르는 것 |
| --- | --- | --- | --- | --- |
| 1 | `./gradlew test --tests "com.doctorpet.domain.reservation.service.HospitalReservationApplicationServiceTest" --tests "com.doctorpet.domain.reservation.controller.HospitalReservationControllerTest"` | PASS | 상태·시간·사유·소유권·Controller 위임 | 없음 |
| 2 | `./gradlew test --tests "com.doctorpet.domain.reservation.controller.HospitalReservationAuthorizationTest"` | PASS | 보호자 403, 병원 직원 성공, 요청 검증 400 | 없음 |
| 3 | `./gradlew test --tests "com.doctorpet.domain.reservation.service.HospitalNoShowIntegrationTest"` | PASS | 실제 MySQL 상태·이력·슬롯·결정적 최신 읽기·병원 ID 조건·DB 오류 전파 | Issue #29 스케줄러 자체 실행 |
| 3 | `./gradlew test --tests "com.doctorpet.domain.reservation.migration.ReservationEventUniqueMigrationIntegrationTest"` | PASS | 기존 중복 정리·UNIQUE 추가·마커 기록·제약 유실 fail-fast | 다중 인스턴스 동시 부팅 |
| 1·3 | `./gradlew test --tests "com.doctorpet.domain.reservation.*"` | PASS | 예약 도메인 전체 회귀 | 타 도메인 외부 인프라 테스트 |
| 문서 | `python scripts/harness_check.py` | PASS | 정본·경량 문서 링크와 구조 | 없음 |
| 전체 | `./gradlew clean build` | FAIL | 431건 중 예약 테스트는 통과했으나 10건이 로컬 메일 설정·Redis 미기동으로 실패 | 아래 미검증 항목 참고 |

**미검증 항목**

- Issue #29 구현 시 테스트 내부 복제 SQL을 실제 자동 노쇼 서비스 호출로 교체하고, 상태 UPDATE와 `AUTO_NO_SHOW` 이력이 한 트랜잭션에서 수동 처리와 경쟁하는 Level 3 테스트를 재실행해야 합니다.
- 알림 저장·조회 및 상태 변경 이벤트는 소비자와 함께 Issue #39에서 구현합니다.
- 전체 빌드의 실패 10건은 로컬 `MAIL_PROVIDER` 미설정과 Redis 미기동 때문이며, CI에는 Fake 메일 설정과 Redis 7 서비스가 구성돼 있습니다.
- Level 5 로컬 기동은 기본 프로필의 기존 `EmailGateway` 설정 누락으로 진행하지 않았습니다.
- Level 6 실제 HTTP 호출은 진행하지 않았습니다. 요청 계약은 MockMvc, 상태·동시성은 실제 MySQL 통합 테스트로 분리 검증했습니다.

## 테스트

- `HospitalReservationApplicationServiceTest` — 상태·시간·사유·소유권과 자동 선처리 분기
- `HospitalReservationAuthorizationTest` — 역할별 접근과 요청 DTO 검증
- `HospitalNoShowIntegrationTest` — 실제 MySQL 상태 전이, append-only 이력, 중복 방지, 자동·수동 경쟁
- 예약 도메인 전체 테스트 — 관련 기능 회귀 확인

## AI 사용 내역

| 항목 | 사용 도구 | AI 제안 내용 | 실제 적용 내용 |
| --- | --- | --- | --- |
| 정책·범위 확인 | Codex | Issue #28과 PRD·SA의 노쇼 규칙 비교 | 자동 스케줄러와 알림 저장은 후속 이슈로 분리 |
| 구현 | Codex | 조건부 UPDATE, 감사 이력, UNIQUE 보장 구조 제안 | 기존 예약 서비스·Repository·마이그레이션 구조에 맞게 적용 |
| 동시성 분석 | Codex | 자동·수동 경쟁 테스트와 최신 상태 재조회 제안 | 실제 MySQL 실패를 재현하고 잠금 조회로 수정 |
| 테스트·문서 | Codex | 단위·MVC·Level 3 테스트와 PR 문서 초안 작성 | 실제 실행 결과만 PASS/FAIL로 기록 |

## 체크리스트

- [x] PR 대상 브랜치가 `develop`인지 확인했습니다.
- [x] 요구사항에 맞게 기능이 동작하는지 확인했습니다.
- [x] 불필요한 코드와 디버깅 코드가 없는지 확인했습니다.
- [x] 관련 테스트를 실제로 실행했습니다.
- [x] 커밋 메시지 컨벤션을 준수했습니다.
- [x] AI 사용 내역을 작성했습니다.
- [ ] `./gradlew build` 성공 — 기존 메일 테스트 설정·Redis 환경 실패 10건이 남아 있습니다.

## 관련 이슈

Closes #28

## 비고

- 자동 노쇼 스케줄러 구현: Issue #29
- 알림 저장·폴링 조회 구현: Issue #39
- 작성자는 직접 merge하지 않고 2명 이상 Approve 후 팀원이 merge합니다.
