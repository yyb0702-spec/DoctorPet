# 예약 승인 타임아웃 스케줄러 구현 정리

> 작성일: 2026년 8월 4일

## 한 줄 설명

병원이 정해진 시간까지 예약 요청에 답하지 않으면 시스템이 예약을 거절하고, 잡혀 있던 진료 시간을 다시 예약할 수 있게 엽니다.

## 아주 쉽게 보는 처리 순서

1. 보호자가 예약을 신청합니다.
2. 예약을 만들 때 병원이 답해야 하는 마지막 시각을 함께 저장합니다.
3. 스케줄러가 1분마다 시간이 지난 예약을 찾습니다.
4. 아직도 `REQUESTED`인 예약만 `REJECTED`로 바꿉니다.
5. 상태 변경에 성공한 경우에만 슬롯을 `OPEN`으로 돌립니다.
6. 자동 거절 이력과 보호자 알림을 저장합니다.

## 구현하거나 고친 부분

### 1. 승인 마감 시각 저장

`Reservation.approvalDeadlineAt`을 추가했습니다. 값은 다음 두 시각 중 더 빠른 시각입니다.

- 예약을 요청한 시각에서 1시간 뒤
- 실제 예약 시작 시각에서 2시간 전

컬럼 이름이 `approvalDeadline_at`으로 작성돼 인덱스의 `approval_deadline_at`과 달랐던 오류도 고쳤습니다.

### 2. 마감 예약 조회

`REQUESTED` 상태이면서 승인 마감 시각이 지난 예약만 오래된 순서로 조회합니다. 한 번에 너무 많은 예약을 읽지 않도록 배치 크기만큼만 가져옵니다.

조회 속도를 위해 다음 인덱스를 추가했습니다.

```text
(status, approval_deadline_at)
```

### 3. 경쟁 조건 방지

병원 승인과 자동 거절이 동시에 실행돼도 조건부 UPDATE로 한쪽만 성공합니다.

- 병원 승인이 먼저 성공하면 스케줄러는 `SKIPPED`
- 자동 거절이 먼저 성공하면 병원 승인은 실패
- 자동 거절이 성공했을 때만 슬롯 반환·이력·알림 처리

병원 승인 UPDATE에도 승인 마감 조건을 넣어, 서비스 검사를 통과한 직후 시간이 지나더라도 늦은 승인이 저장되지 않게 했습니다.

### 4. 상태·슬롯·이력·알림의 원자 처리

한 예약의 자동 거절은 하나의 트랜잭션으로 처리합니다. 슬롯 반환이나 이력·알림 저장 중 문제가 생기면 예약 상태 변경도 함께 취소됩니다.

자동 처리 이력은 다음과 같습니다.

```text
eventType = TIMEOUT_REJECTED
memo = 승인 마감 시간 초과
processedBy = null
```

현재 `processedBy`가 직원 ID를 저장하는 `Long`이므로, `null`은 시스템 자동 처리를 뜻합니다.

### 5. 보호자 알림

예약 도메인은 `ReservationNotificationPublisher` 인터페이스만 사용합니다. 알림 도메인의 `StoringReservationNotificationPublisher`가 실제 `notifications` 테이블 저장을 담당합니다.

알림 내용은 다음과 같습니다.

```text
병원의 승인 시간이 지나 예약 요청이 자동으로 거절되었습니다.
```

### 6. 여러 서버의 중복 실행 방지

MySQL `GET_LOCK`으로 한 서버만 배치를 실행하게 했습니다. Redis 예약 락은 프로젝트 정책상 사용하지 않습니다.

배치 잠금과 조건부 UPDATE는 역할이 다릅니다.

- MySQL 잠금: 여러 서버가 같은 배치를 동시에 시작하지 않게 함
- 조건부 UPDATE: 병원 승인·거절과 자동 거절이 같은 예약을 동시에 바꾸지 못하게 함

### 7. 운영 설정과 관측

다음 값을 `application.yaml`에서 바꿀 수 있습니다.

- 실행 주기
- 최초 실행 대기 시간
- 한 번에 처리할 예약 수
- 실패 건 최대 시도 횟수
- DB 잠금 대기 시간

처리 성공·스킵·실패·잠금 스킵 수, 배치 실행시간, 마감 후 처리 지연시간을 Micrometer 지표로 기록합니다. 실행 결과는 로그에도 남습니다.

## Repository가 클래스여도 되는 이유

`ReservationRepository`와 `ReservationQueryRepository`는 어떤 기능이 필요한지 선언하는 인터페이스입니다.

`ReservationQueryRepositoryImpl`은 QueryDSL로 그 기능을 실제 실행하는 구현체이므로 클래스가 맞습니다. 인터페이스로 바꾸면 실행할 코드가 없어집니다.

`ReservationNotificationPublisher`는 알림 발행 계약만 선언하므로 인터페이스가 맞고, 실제 저장 코드는 `StoringReservationNotificationPublisher` 클래스가 담당합니다.

## 검증 결과

### 배포 전 스키마 체크

- `reservation.approval-deadline-migration.enabled`는 운영 배포에서 `true`로 유지한다.
- 애플리케이션 기동 로그에서 `reservation_approval_deadline_v1` 마이그레이션 완료를 확인한다.
- `reservations.approval_deadline_at`의 `NOT NULL`과 `(status, approval_deadline_at)` 인덱스를 확인한 뒤 스케줄러를 활성화한다.

| 구분 | 결과 | 확인 내용 |
| --- | --- | --- |
| Java 컴파일 | PASS | 새 클래스와 의존성 연결 |
| 기존 예약 단위 테스트 | PASS | 예약 생성·병원 승인 기존 기능 |
| 기존 MySQL 예약 통합 테스트 | PASS | 새 컬럼·인덱스 적용 후 예약 취소 트랜잭션 |
| 타임아웃 배치 단위 테스트 | PASS | 일부 실패 격리와 다음 배치 재시도 |
| 타임아웃 MySQL 통합 테스트 | PASS | 상태·슬롯·이력·알림·경합·멱등·DB 잠금 |
| 전체 `test` | BLOCKED | 로컬 Redis 미기동으로 기존 Redis 통합 테스트가 실패했고 5분 실행 제한에 도달함 |

## 테스트 코드를 쉽게 이해하기

### 1. 마감 전에는 가만히 있는지 확인

아직 답변 시간이 남은 예약을 준비하고 Processor를 실행합니다. 결과가 `SKIPPED`인지, 예약과 슬롯이 그대로인지, 이력과 알림이 생기지 않았는지 확인합니다.

```text
REQUESTED + 마감 전 → REQUESTED + RESERVED
```

### 2. 마감 후에는 네 가지가 함께 바뀌는지 확인

마감된 예약을 처리한 뒤 예약 상태, 슬롯 상태, 이력, 알림을 모두 조회합니다.

```text
예약: REQUESTED → REJECTED
슬롯: RESERVED → OPEN
이력: TIMEOUT_REJECTED 1건
알림: RESERVATION_REJECTED 1건
```

어느 하나라도 빠지면 테스트가 실패합니다.

### 3. 같은 예약을 두 번 처리해도 한 번만 바뀌는지 확인

두 스레드가 같은 예약을 동시에 처리하게 합니다. 한 스레드만 `PROCESSED`, 다른 스레드는 `SKIPPED`가 되어야 합니다. 이력과 알림도 정확히 한 건이어야 합니다.

### 4. 병원 승인과 자동 거절의 경주 확인

두 스레드를 같은 출발선에서 동시에 시작합니다.

- 승인이 이기면 `CONFIRMED + RESERVED`
- 타임아웃이 이기면 `REJECTED + OPEN`

`CONFIRMED + OPEN`처럼 예약과 슬롯이 서로 어긋난 결과는 허용하지 않습니다.

### 5. 병원 거절과 자동 거절의 경주 확인

두 작업 모두 최종 상태는 `REJECTED`지만 슬롯 반환은 한 작업만 수행해야 합니다. 타임아웃이 이겼을 때만 자동 이력과 알림이 만들어지는지도 확인합니다.

### 6. 여러 서버가 동시에 배치를 시작하는 상황 확인

첫 번째 스레드가 MySQL 잠금을 잡고 기다리게 한 다음 두 번째 실행을 시도합니다. 두 번째 실행이 빈 결과를 받고 스킵하는지 확인합니다.

### 7. 한 건이 실패해도 나머지가 계속 처리되는지 확인

첫 예약은 두 번 실패하도록 만들고 두 번째 예약은 성공하게 만듭니다. 첫 배치에서 정상 예약이 처리되고, 실패 예약은 다음 배치에서 다시 성공하는지 확인합니다.

## 테스트 파일

- `ReservationApprovalTimeoutIntegrationTest`: 실제 MySQL 상태 전이·동시성·멱등성·잠금 검증
- `ReservationApprovalTimeoutBatchServiceTest`: 일부 실패 격리와 재시도 분기 검증
- `HospitalReservationApplicationServiceTest`: 저장된 승인 마감 시각을 병원 승인에 적용하는 기존 테스트 보정

## 실행 명령

```powershell
.\gradlew.bat test --tests "com.doctorpet.domain.reservation.service.ReservationApprovalTimeoutBatchServiceTest" --no-daemon
.\gradlew.bat test --tests "com.doctorpet.domain.reservation.service.ReservationApprovalTimeoutIntegrationTest" --no-daemon
```
