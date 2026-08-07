# [feat] 직원용 도착 확인 및 노쇼 추가 유예 구현

## 개요

병원 직원이 예약 환자의 실제 도착을 기록하는 API를 구현했습니다. 자동 노쇼 판정 전에 `NO_SHOW_PENDING` 상태와 기본 5분의 추가 유예를 두고, 직원 도착 확인과 스케줄러가 경합해도 유효한 상태 전이 하나만 성립하도록 조건부 UPDATE로 보호했습니다.

## 작업 내용

### 직원용 도착 확인 API

- `PATCH /api/hospital/reservations/{reservationId}/check-in`
- 요청 body 없이 인증된 병원 직원의 `memberId`만 사용
- 직원 소속 병원과 예약 병원 일치 여부 검증
- `CONFIRMED`, `NO_SHOW_PENDING` 상태에서 `CHECKED_IN` 전이 허용
- `{ reservationId, status, checkedInAt }` 응답 반환
- 반복 호출 시 최초 도착 시각과 `CHECKED_IN` 이력 한 건을 재사용

**주요 파일**

- `HospitalReservationController.java`
- `HospitalReservationApplicationService.java`
- `ReservationCheckInResponse.java`
- `ReservationRepository.java`
- `ReservationEventRepository.java`

---

### NO_SHOW_PENDING 추가 유예

- 예약시각 +10분 초과: `CONFIRMED → NO_SHOW_PENDING`
- 기본 추가 유예 5분 이내: 직원 도착 확인 가능
- 예약시각 +15분 초과: `NO_SHOW_PENDING → NO_SHOW`
- 최종 `NO_SHOW` 전이 전에는 보호자 노쇼 알림 미발행
- 수동 노쇼는 `CONFIRMED`, `NO_SHOW_PENDING`에서 모두 가능
- 배치 조회·지연 지표가 두 상태의 서로 다른 기준 시각을 사용하도록 수정

**주요 파일**

- `ReservationStatus.java`
- `ReservationEventType.java`
- `ReservationNoShowProperties.java`
- `ReservationNoShowBatchService.java`
- `ReservationNoShowProcessor.java`
- `application.yaml`

---

### DB 마이그레이션

- `reservations.no_show_pending_at DATETIME(6) NULL` 추가
- MySQL `GET_LOCK`으로 다중 인스턴스 최초 실행 직렬화
- `schema_migrations`의 `reservation_no_show_pending_v1` 마커로 일회성 적용
- 마커가 있으나 컬럼이 없으면 기동 실패로 스키마 불일치 노출

**주요 파일**

- `Reservation.java`
- `ReservationNoShowPendingMigrationRunner.java`
- `ReservationNoShowPendingMigrationIntegrationTest.java`

---

### Test

- 비인증 사용자 401, 보호자 403, 병원 직원 성공 경로 검증
- +10분 경계의 `NO_SHOW_PENDING` 진입 검증
- 추가 유예 5분 안의 체크인 성공 검증
- +15분 초과 최종 `NO_SHOW`·이력·알림 1회 검증
- 중복 체크인의 최초 시각·처리자·이력 멱등성 검증
- 체크인과 자동 노쇼의 실제 MySQL 다중 스레드 경합 검증
- 신규 컬럼과 마이그레이션 마커를 실제 MySQL에서 검증

## 변경 유형

- [x] feat
- [ ] fix
- [ ] refactor
- [x] docs
- [x] test
- [ ] chore

## 주요 검증 내용

### 검증 기록

| Level | 실행한 명령 | 결과 | 확인한 것 | 아직 모르는 것 |
| --- | --- | --- | --- | --- |
| 1·2·3 | `./gradlew test --tests "...HospitalReservationControllerTest" --tests "...HospitalReservationAuthorizationTest" --tests "...HospitalReservationApplicationServiceTest" --tests "...HospitalNoShowIntegrationTest" --tests "...ReservationNoShowBatchServiceTest" --tests "...ReservationNoShowPendingMigrationIntegrationTest" --tests "...ReservationQueryRepositoryIntegrationTest" --tests "...ReservationProgressStatusTest"` | PASS | API 계약·인가·경계·멱등·실제 MySQL 전이·경합·마이그레이션 | 실제 다중 서버 환경 |
| 1 | `./gradlew test` | BLOCKED | 전체 테스트를 실행했으나 로컬 공용 테스트 환경이 없어 완료 판정 불가 | `AiGateway` 빈 부재와 로컬 Redis 부재로 연쇄 실패. 기능 관련 테스트와 CI는 PASS |
| 5 | 실행 JAR을 18080 포트로 기동 후 `/actuator/health` 호출 | PARTIAL | Tomcat·MySQL·JPA·신규 enum/컬럼 기동 및 HTTP 수신 | 로컬 Redis/Docker 미기동으로 health `DOWN` |
| 6 | `PATCH http://localhost:18080/api/hospital/reservations/1/check-in` | PARTIAL | 비인증 요청이 `401 COMMON_002`로 거부됨 | Redis 부재로 인증된 직원 성공 요청 미검증 |

**미검증 항목**

- Redis를 포함한 로컬 전체 헬스 `UP`
- 실제 로그인 토큰을 사용한 직원 체크인 성공·중복 HTTP 호출
- 실제 다중 서버 배포 환경

## AI 사용 내역

| 항목 | 사용 도구 | AI 제안 내용 | 실제 적용 내용 |
| --- | --- | --- | --- |
| 상태 머신 설계 | Codex | 자동 노쇼 전 `NO_SHOW_PENDING` 추가 | 사용자 확정값인 기본 5분으로 반영 |
| 동시성 검토 | Codex | 체크인·자동 노쇼 조건부 UPDATE 경계 통일 | 상태와 슬롯 시각을 UPDATE 조건에서 함께 검증 |
| 테스트 작성 | Codex | 경계·멱등·경합·마이그레이션 시나리오 | 단위·MockMvc·실제 MySQL 테스트로 반영 |
| 문서 갱신 | Codex | PRD·SA·정책·검증 기록 동기화 | 정본 버전과 상태 전이 계약 갱신 |

## 체크리스트

- [x] PR 대상 브랜치가 `develop`인지 확인했습니다.
- [x] 요구사항에 맞게 기능이 동작하는지 확인했습니다.
- [x] 불필요한 코드와 디버깅 코드를 제거했습니다.
- [x] 관련 테스트를 실행했습니다.
- [x] 커밋 메시지 컨벤션을 준수했습니다.
- [x] AI 사용 내역을 작성했습니다.
- [x] 관련 정본 문서를 갱신했습니다.

## 관련 이슈

Closes #109

## 비고

- `RESERVATION_NO_SHOW_PENDING_GRACE_MINUTES` 기본값은 5이며 양의 정수로 검증합니다.
- 로컬 Redis 또는 Docker를 기동한 뒤 Level 5·6의 남은 항목을 추가 확인해야 합니다.
