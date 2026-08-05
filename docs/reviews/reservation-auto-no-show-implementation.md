# 예약 자동 노쇼 스케줄러 구현 결과

## 처리 기준

- 대상: `CONFIRMED` 예약만
- 기준: 예약 슬롯 시작 시각 + 10분이 현재 시각 이하인 예약
- 전이: `CONFIRMED → NO_SHOW`
- 슬롯: 이미 지난 슬롯이므로 `RESERVED` 유지
- 이력: `AUTO_NO_SHOW`, `processed_by=NULL(SYSTEM)`
- 알림: 보호자에게 `NO_SHOW` 저장형 알림 발행

체크인·취소·완료 등 다른 상태는 조회와 조건부 UPDATE에서 제외한다. 수동 확정과 자동 처리가 경합하면 `CONFIRMED` 조건부 UPDATE로 한 번만 상태가 바뀌며, 자동 처리가 먼저 끝나도 수동 API는 `MANUAL_NO_SHOW` 이력을 추가해 수동 판단을 보존한다.

## 운영 설정

| 환경 변수 | 기본값 | 의미 |
| --- | ---: | --- |
| `RESERVATION_NO_SHOW_INTERVAL_MS` | 60000 | 실행 주기 |
| `RESERVATION_NO_SHOW_INITIAL_DELAY_MS` | 60000 | 최초 실행 지연 |
| `RESERVATION_NO_SHOW_ZONE_ID` | Asia/Seoul | 기준 타임존 |
| `RESERVATION_NO_SHOW_GRACE_MINUTES` | 10 | 체크인 유예 시간 |
| `RESERVATION_NO_SHOW_BATCH_SIZE` | 100 | 페이지 크기 |
| `RESERVATION_NO_SHOW_MAX_SCANNED_PER_RUN` | 1000 | 실행당 최대 조회 건수 |
| `RESERVATION_NO_SHOW_MAX_ATTEMPTS` | 2 | 단건 최대 시도 횟수 |
| `RESERVATION_NO_SHOW_LOCK_WAIT_SECONDS` | 0 | 다중 인스턴스 DB 잠금 대기 |
| `RESERVATION_NO_SHOW_INDEX_MIGRATION_ENABLED` | true | 기존 DB 조회 인덱스 일회성 적용 여부 |

관측 지표는 `reservation.no.show.processed`, `skipped`, `failed`, `lock.skipped`, `delay`, `batch.duration`을 사용한다. 조회는 `(status, slot_id)` 인덱스와 슬롯 PK 조인을 사용한다. 인덱스는 마이그레이션 이력과 MySQL 잠금으로 다중 인스턴스에서도 한 번만 적용하며, 적용 이력과 실제 스키마가 다르면 시작 시 실패시킨다.

## 검증

- 예약 시각 +10분 이전/정확한 경계
- `CONFIRMED`만 대상
- 자동 처리 멱등성과 SYSTEM 이력·알림 1회
- 체크인과 자동 처리 경합
- 수동 확정과 자동 처리 경합
- 일부 실패 재시도 후 다음 예약 계속 처리
- 다중 인스턴스 MySQL `GET_LOCK` 중복 실행 방지
