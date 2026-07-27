# DoctorPet 데이터베이스 설계 경량본
> **이 문서는 열람용 요약이다. 구현 기준은 아래 저장소 정본을 따른다. 경량본과 정본이 다르면 PRD → SA → 코드 컨벤션 → 정책 정리본 순으로 적용한다.**
| 정본 | 경로·버전 |
| --- | --- |
| 제품 요구사항 | `docs/product/DoctorPet-PRD.md` v3.5 |
| 시스템 설계·ERD·API·상태 머신 | `docs/architecture/DoctorPet-SA.md` v1.8, REST API는 §8 |
| 코드 컨벤션 | `docs/architecture/DoctorPet-코드컨벤션.md` v1.0 |
| 정책 원본 | `docs/domain/반려동물병원예약-정책정리본.md` v8 |
## 1. 관계 요약
```plain text
members 1 ── 0..N pet_profiles
members 1 ── 0..N reservations
hospitals 1 ── 0..1 hospital_details
hospitals 1 ── 0..N hospital_capabilities
hospitals 1 ── 0..N reservation_slots
reservation_slots 1 ── 0..N reservations
reservations 1 ── 0..N reservation_events
reservations 1 ── 0..1 payments
members 1 ── 0..N payment_methods
members 1 ── 0..N ai_consultations
members 1 ── 0..N notifications
```
## 2. 회원·반려동물
### `members`
| 필드 | 타입 | 제약·설명 |
| --- | --- | --- |
| `id` | BIGINT | PK |
| `email` | VARCHAR | 로그인 식별자, 활성 회원 중복 불가 |
| `password` | VARCHAR | 해시 저장 |
| `nickname` | VARCHAR | 닉네임 |
| `role` | VARCHAR | `GUARDIAN`, `HOSPITAL_STAFF` |
| `hospital_id` | BIGINT | 병원 직원 소속, `NULL` 가능 |
| `created_at` | DATETIME | 생성 시각 |
| `deleted_at` | DATETIME | Soft Delete 시각, `NULL` 가능 |
### `pet_profiles`
| 필드 | 타입 | 제약·설명 |
| --- | --- | --- |
| `id` | BIGINT | PK |
| `member_id` | BIGINT | 회원 FK |
| `name` | VARCHAR | 이름 |
| `species` | VARCHAR | `DOG`, `CAT` |
| `age` | INT | 나이 |
| `weight` | DECIMAL | 체중 |
| `neutered` | BOOLEAN | 중성화 여부 |
| `created_at` | DATETIME | 생성 시각 |
| `deleted_at` | DATETIME | Soft Delete 시각, `NULL` 가능 |
## 3. 병원
### `hospitals`
| 필드 | 타입 | 제약·설명 |
| --- | --- | --- |
| `id` | BIGINT | PK |
| `mgmt_no` | VARCHAR | 관리번호, `local_gov_code`와 복합 UNIQUE |
| `local_gov_code` | VARCHAR | 지자체 코드 |
| `name` | VARCHAR | 병원명 |
| `phone` | VARCHAR | 전화번호 |
| `address_jibun` | VARCHAR | 지번 주소 |
| `address_road` | VARCHAR | 도로명 주소 |
| `zipcode` | VARCHAR | 우편번호 |
| `coord_x` | DECIMAL | X 좌표 |
| `coord_y` | DECIMAL | Y 좌표 |
| `license_date` | DATE | 인허가일 |
| `business_status` | VARCHAR | `OPEN`, `CLOSED_TEMP`, `CLOSED` |
| `close_date` | DATE | 폐업일, `NULL` 가능 |
| `area` | DECIMAL | 면적, `NULL` 가능 |
| `source_modified_at` | DATETIME | 공공데이터 최종 수정일 |
| `partnership_status` | VARCHAR | `PARTNER`, `NON_PARTNER` |
제약: `UNIQUE(local_gov_code, mgmt_no)` — 지자체 범위 관리번호를 복합 매핑 키로 사용한다.
인덱스: `(business_status)`, `(coord_x, coord_y)` — 영업상태 필터와 거리 후보 검색에 사용한다.
### `hospital_details`
| 필드 | 타입 | 제약·설명 |
| --- | --- | --- |
| `id` | BIGINT | PK |
| `hospital_id` | BIGINT | 병원 FK, UNIQUE |
| `open_hours` | VARCHAR/JSON | 운영 시간 |
| `surgery_available` | BOOLEAN | 수술 가능 여부 |
| `hospitalization_available` | BOOLEAN | 입원 가능 여부 |
| `night_care` | BOOLEAN | 야간 진료 여부 |
| `emergency` | BOOLEAN | 응급 진료 여부 |
### `hospital_capabilities`
| 필드 | 타입 | 제약·설명 |
| --- | --- | --- |
| `id` | BIGINT | PK |
| `hospital_id` | BIGINT | 병원 FK |
| `capability_type` | VARCHAR | `SPECIES`, `EXAM`, `TREATMENT`, `EQUIPMENT` |
| `capability_value` | VARCHAR | 역량 값 |
인덱스: `(capability_type, capability_value, hospital_id)` — 요청 진료역량의 AND 매칭에 사용한다.
## 4. 예약
### `reservation_slots`
| 필드 | 타입 | 제약·설명 |
| --- | --- | --- |
| `id` | BIGINT | PK |
| `hospital_id` | BIGINT | 병원 FK |
| `start_at` | DATETIME | 시작 시각 |
| `end_at` | DATETIME | 종료 시각 |
| `status` | VARCHAR | `OPEN`, `RESERVED` |
| `version` | BIGINT | 낙관적 락 버전 |
제약: `UNIQUE(hospital_id, start_at)` — 중복 슬롯을 방지하고 슬롯 생성 배치의 재실행 멱등성을 보장한다. 인덱스는 `(hospital_id, status, start_at)`이다.
### `reservations`
| 필드 | 타입 | 제약·설명 |
| --- | --- | --- |
| `id` | BIGINT | PK |
| `member_id` | BIGINT | 회원 FK |
| `pet_id` | BIGINT | 반려동물 FK |
| `pet_name_snapshot` | VARCHAR | 예약 당시 이름 |
| `pet_species_snapshot` | VARCHAR | 예약 당시 동물 종 |
| `hospital_id` | BIGINT | 병원 FK |
| `slot_id` | BIGINT | 슬롯 FK |
| `payment_method_id` | BIGINT | 결제수단 FK |
| `status` | VARCHAR | 예약 상태 |
| `reject_reason` | VARCHAR | 거절 사유, `NULL` 가능 |
| `requested_at` | DATETIME | 요청 시각 |
| `confirmed_at` | DATETIME | 승인 시각, `NULL` 가능 |
| `canceled_at` | DATETIME | 취소 시각, `NULL` 가능 |
| `no_show_at` | DATETIME | 노쇼 판정 시각, `NULL` 가능 |
인덱스: `(slot_id)`, `(member_id, status)`, `(hospital_id, status)` — 슬롯·회원·병원별 예약 조회에 사용한다.
슬롯은 반환 후 다시 사용될 수 있어 예약 이력과 1:N 관계다. 같은 시점의 활성 예약 1건은 `reservation_slots.version` 낙관적 락으로 `OPEN → RESERVED` 점유를 원자적으로 처리해 보장한다.
### `reservation_events`
| 필드 | 타입 | 제약·설명 |
| --- | --- | --- |
| `id` | BIGINT | PK |
| `reservation_id` | BIGINT | 예약 FK |
| `event_type` | VARCHAR | `AUTO_NO_SHOW`, `MANUAL_NO_SHOW`, `NO_SHOW_CORRECTED`, `TIMEOUT_REJECTED` |
| `memo` | VARCHAR | 메모, `NULL` 가능 |
| `occurred_at` | DATETIME | 발생 시각 |
`reservation_events`는 append-only 방식 B를 사용한다. 정상 전이는 `reservations`의 상태·시각 컬럼으로 표현하고, 노쇼 자동·수동 판정, 노쇼 정정, 승인 타임아웃처럼 상태만으로 흔적이 사라지는 예외·비가역 사건만 기록한다.
## 5. 결제
### `payment_methods`
| 필드 | 타입 | 제약·설명 |
| --- | --- | --- |
| `id` | BIGINT | PK |
| `member_id` | BIGINT | 회원 FK |
| `billing_key_enc` | VARBINARY/VARCHAR | 암호화된 빌링키 |
| `card_brand` | VARCHAR | 카드사, `NULL` 가능 |
| `card_last4` | VARCHAR | 카드 끝 4자리, `NULL` 가능 |
| `status` | VARCHAR | `ACTIVE`, `EXPIRED`, `DELETED` |
| `created_at` | DATETIME | 생성 시각 |
### `payments`
| 필드 | 타입 | 제약·설명 |
| --- | --- | --- |
| `id` | BIGINT | PK |
| `reservation_id` | BIGINT | 예약 FK, UNIQUE |
| `merchant_payment_id` | VARCHAR | 가맹점 결제 ID, UNIQUE |
| `payment_method_id` | BIGINT | 결제수단 FK |
| `card_brand_snapshot` | VARCHAR | 결제 당시 카드사, `NULL` 가능 |
| `card_last4_snapshot` | VARCHAR | 결제 당시 카드 끝 4자리, `NULL` 가능 |
| `amount` | INT | `0 < amount ≤ 3,000,000` |
| `status` | VARCHAR | `PENDING`, `PAID`, `OFFLINE_REQUIRED`, `OFFLINE_PAID` |
| `payment_channel` | VARCHAR | `BILLING_KEY`, `OFFLINE` |
| `retry_count` | INT | 재시도 횟수 |
| `failure_reason` | VARCHAR | 실패 사유, `NULL` 가능 |
| `pg_payment_id` | VARCHAR | PG 결제 ID, `NULL` 가능 |
| `offline_settled_at` | DATETIME | 오프라인 정산 시각, `NULL` 가능 |
| `offline_settled_by` | BIGINT | 정산 처리자, `NULL` 가능 |
| `created_at` | DATETIME | 생성 시각 |
| `paid_at` | DATETIME | 결제 완료 시각, `NULL` 가능 |
## 6. AI·알림
### `ai_consultations`
| 필드 | 타입 | 제약·설명 |
| --- | --- | --- |
| `id` | BIGINT | PK |
| `member_id` | BIGINT | 회원 FK, 비로그인 상담은 `NULL` 가능 |
| `symptom_text` | TEXT | 전화번호·이메일·주민번호 등 패턴 마스킹, 30일 보존 후 원문 배치 삭제 |
| `structured_result` | JSON | AI 구조화 출력 5필드 전체 원본 |
| `required_capabilities` | JSON | 검색·조회용 중복 저장 진료역량 |
| `urgency_level` | VARCHAR | `LOW`, `MODERATE`, `HIGH`; 조회·집계용 중복 저장 |
| `model` | VARCHAR | 사용 모델 |
| `prompt_version` | VARCHAR | 프롬프트 버전 |
| `prompt_tokens` | INT | 입력 토큰 수 |
| `completion_tokens` | INT | 출력 토큰 수 |
| `latency_ms` | INT | 응답 지연시간 |
| `status` | VARCHAR | `SUCCESS`, `FAILED` |
| `error_type` | VARCHAR | `TIMEOUT`, `RATE_LIMIT`, `SERVER_ERROR`, `NULL` 가능 |
| `fallback_used` | BOOLEAN | 대체 처리 여부 |
| `tool_call_status` | VARCHAR | Tool 호출 상태 |
| `schema_parse_success` | BOOLEAN | 구조화 출력 파싱 성공 여부 |
| `created_at` | DATETIME | 생성 시각 |
### `notifications`
| 필드 | 타입 | 제약·설명 |
| --- | --- | --- |
| `id` | BIGINT | PK |
| `member_id` | BIGINT | 회원 FK |
| `type` | VARCHAR | 알림 유형 |
| `content` | VARCHAR | 알림 내용 |
| `is_read` | BOOLEAN | 읽음 여부 |
| `created_at` | DATETIME | 생성 시각 |
## 7. 확장 테이블
### `payment_webhooks` `(확장)`
| 필드 | 타입 | 제약·설명 |
| --- | --- | --- |
| `id` | BIGINT | PK |
| `payment_id` | BIGINT | 결제 FK |
| `event_type` | VARCHAR | 이벤트 유형 |
| `received_at` | DATETIME | 수신 시각 |
제약: `UNIQUE(payment_id, event_type)` — 동일 결제 이벤트의 중복 수신을 한 번만 반영한다.
## 8. 설계상 필수 규칙
- 제휴 병원은 `hospitals.partnership_status`로 판별한다.
- 공공데이터와 제휴 데이터는 `local_gov_code + mgmt_no` 복합 키로 매핑하고 복합 UNIQUE로 보장한다.
- `pet_profiles`의 확정 필드는 `name`, `species`, `age`, `weight`, `neutered`이다.
- 비로그인 AI 상담을 허용하므로 `ai_consultations.member_id`는 `NULL`을 허용한다.
- `reservation_slots.version`은 낙관적 락에 사용한다.
- 노쇼 처리 시 슬롯 상태는 `RESERVED`를 유지한다.
- 오프라인 정산은 결제 상태가 `OFFLINE_REQUIRED`일 때만 수행한다.
