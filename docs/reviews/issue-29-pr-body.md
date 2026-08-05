# PR 제목

`[feat] 예약 자동 노쇼 판정 스케줄러 구현`

## 개요

예약 슬롯 시작 후 10분이 지났는데도 체크인하지 않은 `CONFIRMED` 예약을 자동으로 `NO_SHOW` 처리합니다. 조건부 UPDATE와 MySQL DB 잠금으로 체크인·수동 노쇼 처리·다중 인스턴스 실행 간 중복 전이를 막고, 상태 이력과 보호자 알림을 같은 트랜잭션으로 저장합니다.

## 작업 내용

### Reservation / 자동 노쇼 판정

- `CONFIRMED`이면서 슬롯 시작 후 10분이 지난 예약을 커서 방식으로 조회
- 조건부 UPDATE로 `CONFIRMED → NO_SHOW` 원자 전이
- 체크인·취소·진료 완료 등 대상 외 상태 제외
- `AUTO_NO_SHOW`, `processed_by=NULL(SYSTEM)` 감사 이력 저장
- 보호자 `NO_SHOW` 알림 저장
- 수동 확정이 자동 처리보다 먼저 끝나면 자동 처리 건너뜀
- 자동 처리가 먼저 끝난 뒤에도 `MANUAL_NO_SHOW` 수동 판단 이력 추가 가능
- MySQL `GET_LOCK`으로 다중 인스턴스 중복 실행 방지
- 단건 재시도·실패 격리·실행당 최대 조회량 적용
- 성공·스킵·실패·잠금 스킵·지연·실행 시간 지표 구성
- 실행 주기·유예 시간·배치 크기·재시도 설정 외부화, 공용 서울 기준 `Clock` 사용
- 슬롯 조인 projection으로 페이지 내 슬롯 단건 재조회 제거
- `(status, slot_id)` 조회 인덱스와 잠금 기반 일회성 마이그레이션 추가

**수정 파일**

- `ReservationNoShowScheduler.java`
- `ReservationNoShowBatchService.java`
- `ReservationNoShowProcessor.java`
- `ReservationNoShowLock.java`
- `ReservationNoShowProperties.java`
- `ReservationRepository.java`
- `ReservationNotificationPublisher.java`
- `StoringReservationNotificationPublisher.java`
- `ReservationNoShowIndexMigrationRunner.java`
- `Reservation.java`
- `application.yaml`

---

### Test

- 기준 시각 +10분 이전/경계 이후 조회 검증
- `CONFIRMED` 예약만 자동 노쇼 처리되는지 검증
- 체크인과 자동 처리 다중 스레드 경합 검증
- 수동 확정과 자동 처리 다중 스레드 경합 검증
- 상태 전이·SYSTEM 이력·알림의 원자성 및 멱등성 검증
- MySQL `GET_LOCK` 다중 인스턴스 중복 방지 검증
- 한 예약이 재시도 후 실패해도 다음 페이지 예약을 처리하는지 검증

**수정 파일**

- `HospitalNoShowIntegrationTest.java`
- `ReservationNoShowBatchServiceTest.java`

---

### Docs

- SA에 자동 노쇼 조회 인덱스 반영
- 운영 설정·관측 지표·검증 결과 문서화
- 자동 노쇼 처리 흐름을 쉬운 설명 문서로 정리

**수정 파일**

- `DoctorPet-SA.md`
- `reservation-auto-no-show-implementation.md`
- `issue-29-auto-no-show-guide.md`

## 변경 유형

- [x] feat
- [ ] fix
- [ ] refactor
- [x] docs
- [x] test
- [ ] chore

## 주요 검증 내용

- 자동 노쇼와 체크인이 동시에 실행되어도 `CHECKED_IN` 또는 `NO_SHOW` 중 하나만 성립합니다.
- 자동 처리와 수동 확정이 경합해도 상태는 한 번만 바뀌고 수동 판단 이력은 보존됩니다.
- 상태 변경·`AUTO_NO_SHOW` 이력·보호자 알림은 한 트랜잭션에서 처리됩니다.
- 단건 실패는 뒤 예약의 처리를 중단시키지 않습니다.
- 여러 애플리케이션 인스턴스가 실행돼도 한 인스턴스만 배치를 수행합니다.

### 검증 기록

| Level | 실행한 명령 | 결과 | 확인한 것 | 아직 모르는 것 |
|---|---|---|---|---|
| Level 1 | `./gradlew compileJava compileTestJava --no-daemon` | PASS | 운영 코드와 테스트 코드 컴파일 | 전체 테스트 회귀 여부 |
| Level 2 | `./gradlew test --tests "com.doctorpet.domain.reservation.service.ReservationNoShowBatchServiceTest" --no-daemon` | PASS | 재시도·실패 격리·다음 페이지 진행 | 실제 DB 경합 |
| Level 3 | `./gradlew test --tests "com.doctorpet.domain.reservation.service.HospitalNoShowIntegrationTest" --no-daemon` | PASS | 실제 MySQL 경계·경합·멱등·이력·알림·잠금 | 외부 알림 전송 채널 |
| Harness | `python scripts/harness_check.py` 및 `git diff --check` | PASS | 문서 링크·섹션 및 공백 오류 | 없음 |
| 전체 회귀 | CI와 동일한 환경 변수·빈 MySQL·Redis에서 `./gradlew clean build --no-daemon` | PASS | 전체 742개 테스트와 패키징 | 없음 |

**미검증 항목**

- MVP 알림은 저장형 폴링 방식이므로 외부 push 채널은 범위에 포함하지 않았습니다.

## 테스트

- `HospitalNoShowIntegrationTest` — 경계, 대상 상태, 체크인/수동 확정 경합, 멱등성, SYSTEM 이력, 알림, DB 잠금
- `ReservationNoShowBatchServiceTest` — 단건 재시도 실패 후 다음 예약 처리

## AI 사용 내역

| 항목 | 사용 도구 | AI 제안 내용 | 실제 적용 내용 |
|---|---|---|---|
| 기능 구조 검토 | Codex | 조회·배치·단건 트랜잭션·잠금 책임 분리 | 프로젝트의 승인 타임아웃 패턴에 맞춰 적용 |
| 동시성 검토 | Codex | 조건부 UPDATE와 DB 잠금의 이중 방어 | 실제 MySQL 다중 스레드 테스트와 함께 적용 |
| 테스트 케이스 도출 | Codex | 경계·경합·멱등·부분 실패 시나리오 | STRICT 검증 대상 위주로 선별 적용 |
| 문서 작성 | Codex | 운영 설정과 쉬운 흐름 설명 | SA 및 구현 결과 문서에 반영 |

## 체크리스트

- [x] PR 대상 브랜치가 `develop`인지 확인했습니다.
- [x] 요구사항에 맞게 기능이 동작하는지 확인했습니다.
- [x] 불필요한 코드와 디버깅 코드가 없는지 확인했습니다.
- [x] 관련 테스트를 실행했습니다.
- [x] 커밋 메시지 컨벤션을 준수했습니다.
- [x] AI 사용 내역을 작성했습니다.

## 관련 이슈

Closes #29

## 비고

- 리뷰 시 자동 노쇼와 체크인·수동 확정의 경합 결과, SYSTEM 이력과 알림의 원자성을 중점적으로 확인 부탁드립니다.
- 예약 슬롯은 이미 지난 시간이므로 자동 노쇼 처리 후에도 `RESERVED`를 유지합니다.
