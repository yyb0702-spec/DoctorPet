# 이슈 #109 직원용 도착 확인 API 구현 결과

> 작성일: 2026년 8월 6일

## 구현 결과

- 병원 직원 전용 `PATCH /api/hospital/reservations/{reservationId}/check-in` API를 구현했다.
- `CONFIRMED`와 `NO_SHOW_PENDING` 예약을 `CHECKED_IN`으로 조건부 변경한다.
- 예약시각 +10분 초과 시 `NO_SHOW_PENDING`, 기본 5분의 추가 유예가 끝난 +15분 초과 시 최종 `NO_SHOW`로 처리한다.
- 최종 `NO_SHOW` 전에는 보호자 알림을 생성하지 않는다.
- 도착 확인 이력에 최초 처리 시각과 직원 ID를 저장하며, 중복 요청은 최초 응답과 이력 한 건을 그대로 반환한다.
- `no_show_pending_at` 컬럼은 DB 잠금과 `schema_migrations` 마커를 사용하는 일회성 마이그레이션으로 검증한다.

## 검증 기록

| Level | 실행한 명령 | 결과 | 확인한 것 | 아직 모르는 것 |
| --- | --- | --- | --- | --- |
| 1·2·3 | `./gradlew test --tests "...HospitalReservationControllerTest" --tests "...HospitalReservationAuthorizationTest" --tests "...HospitalReservationApplicationServiceTest" --tests "...HospitalNoShowIntegrationTest" --tests "...ReservationNoShowBatchServiceTest" --tests "...ReservationNoShowPendingMigrationIntegrationTest" --tests "...ReservationQueryRepositoryIntegrationTest" --tests "...ReservationProgressStatusTest"` | PASS | API 응답, 직원 인가, +10/+15분 전이, 중복 멱등, 체크인·자동 노쇼 경합, 실제 MySQL 컬럼·마커 | 실제 다중 서버 환경 |
| 1 | `./gradlew test` | BLOCKED | 전체 테스트를 실행했으나 로컬 공용 테스트 환경이 없어 완료 판정 불가 | `AiGateway` 빈 부재와 로컬 Redis 부재로 연쇄 실패. 기능 관련 테스트와 CI는 PASS |
| 5 | 실행 JAR을 `--server.port=18080`과 Fake Gateway 설정으로 기동 후 `/actuator/health` 호출 | PARTIAL | Tomcat·MySQL·JPA·신규 enum/컬럼까지 기동되고 실제 HTTP 요청을 수신 | 로컬 Redis/Docker가 꺼져 있어 전체 헬스는 `DOWN` |
| 6 | `PATCH http://localhost:18080/api/hospital/reservations/1/check-in` | PARTIAL | 비인증 요청이 실제 서버에서 `401 COMMON_002`로 거부됨 | Redis 부재로 로그인 토큰 발급과 인증된 성공 요청은 미검증 |

## 핵심 경계

```text
예약시각 +10분까지     CONFIRMED → CHECKED_IN 가능
예약시각 +10분 초과    CONFIRMED → NO_SHOW_PENDING
추가 5분 이내          NO_SHOW_PENDING → CHECKED_IN 가능
예약시각 +15분 초과    NO_SHOW_PENDING → NO_SHOW + 보호자 알림
```

Level 5·6의 남은 검증은 Redis 또는 Docker를 기동한 환경에서 직원 계정으로 로그인한 뒤 같은 API를 두 번 호출해, 두 응답의 `checkedInAt`이 같은지 확인하면 완료된다.
