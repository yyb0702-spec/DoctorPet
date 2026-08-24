# DoctorPet 데이터베이스 설계 경량본
> **이 문서는 열람용 요약이다. 구현 기준은 아래 저장소 정본을 따른다. 경량본과 정본이 다르면 PRD → SA → 코드 컨벤션 → 정책 정리본 순으로 적용한다.**
| 정본 | 경로·버전 |
| --- | --- |
| 제품 요구사항 | `docs/product/DoctorPet-PRD.md` v3.25 |
| 시스템 설계·ERD·API·상태 머신 | `docs/architecture/DoctorPet-SA.md` v1.66, REST API는 §8 |
| 코드 컨벤션 | `docs/architecture/DoctorPet-코드컨벤션.md` v1.0 |
| 정책 원본 | `docs/domain/반려동물병원예약-정책정리본.md` v14 |
## 1. 관계 요약
```plain text
members 1 ── 0..N pet_profiles
members 1 ── 0..N reservations
members 1 ── 0..N hospital_favorites
hospitals 1 ── 0..1 hospital_details
hospitals 1 ── 0..N hospital_capabilities
hospitals 1 ── 0..N hospital_favorites
hospitals 1 ── 0..N reservation_slots
reservation_slots 1 ── 0..N reservations
reservations 1 ── 0..N reservation_events
reservations 1 ── 0..1 payments   (정정 재청구 도입 후 0..N, 활성 1건)
payments 1 ── 0..1 payment_refunds
payments 1 ── 0..N payment_items   (항목화 후 일반 신규·정정 청구는 1..N, 기존 결제와 그 레거시 셀프 재청구는 0)
reservations 1 ── 0..N payment_items   (청구 전 초안 — payment_id IS NULL)
members 1 ── 0..N payment_methods
members 1 ── 0..N ai_consultations
members 1 ── 0..N notifications
reservations 1 ── 0..N chat_messages
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
| `email_verified` | BOOLEAN | 이메일 인증 여부, 기본값 `FALSE`. `FALSE`인 동안 로그인 차단 |
| `failed_login_attempts` | INT | 로그인 연속 실패 횟수, 기본값 0 |
| `locked_until` | DATETIME | 잠금 해제 시각, `NULL`이면 잠금 아님 |
| `created_at` | DATETIME | 생성 시각 |
| `deleted_at` | DATETIME | Soft Delete 시각, `NULL` 가능 |
### `pet_profiles`
| 필드 | 타입 | 제약·설명 |
| --- | --- | --- |
| `id` | BIGINT | PK |
| `member_id` | BIGINT | 회원 FK |
| `name` | VARCHAR | 이름 |
| `species` | VARCHAR | `DOG`, `CAT`, `BIRD`, `RABBIT`, `HAMSTER`, `GUINEA_PIG`, `FERRET`, `REPTILE` |
| `age` | INT | 나이 |
| `weight` | DECIMAL | 체중 |
| `neutered` | BOOLEAN | 중성화 여부 |
| `image_url` | VARCHAR(2048) | 프로필 사진 URL, `NULL` 가능(presigned URL로 S3 직접 업로드 후 저장) |
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
검색 인덱스는 전국 데이터 기준 실행 계획과 OFF/ON 성능 비교 결과에 따라 기본 이름순 `(name, id, business_status)`, 제휴 병원 이름순 `(partnership_status, name, id, business_status)`, 좌표 바운딩박스 `(coord_x, coord_y)`를 적용한다. 선택도가 낮고 이름순 정렬을 지원하지 못한 `(business_status)` 단일 인덱스는 적용하지 않는다.
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
진료역량 검색은 `UNIQUE(hospital_id, capability_type, capability_value)`를 유지한다. `(capability_value, hospital_id)` 후보는 실행 계획상 스캔 범위를 줄였지만 전체 쿼리 개선 폭이 1ms 미만이어서 쓰기·저장 비용을 감수할 근거가 부족하므로 적용하지 않는다. 진료역량 데이터나 검색 트래픽이 유의미하게 증가하면 다시 검토한다.
### `hospital_favorites`
| 필드 | 타입 | 제약·설명 |
| --- | --- | --- |
| `id` | BIGINT | PK |
| `member_id` | BIGINT | 인증된 보호자 ID, 회원 도메인 논리 참조 |
| `hospital_id` | BIGINT | 병원 FK |
| `created_at` | DATETIME | 찜 등록 시각 |
제약: `UNIQUE(member_id, hospital_id)`로 중복 찜을 방지한다. 내 찜 목록은 `created_at DESC, id DESC`로 안정적으로 정렬한다. 로컬 MySQL 합성 찜 5,000건에서 20회 워밍업 후 200회 조회한 결과 중앙값 2.761ms, P95 4.388ms였고 5,000행 스캔과 `Using filesort`가 확인됐다. 현재 응답시간에서는 쓰기·저장 비용을 감수할 근거가 부족해 `(member_id, created_at DESC, id DESC)` 후보를 적용하지 않으며, 회원별 찜 규모나 조회 부하가 증가하면 다시 측정한다. 회원 탈퇴 시 해당 회원의 찜은 삭제한다.
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
| `approval_deadline_at` | DATETIME NOT NULL | 생성 시 계산한 승인 마감 시각 |
| `approval_timeout_next_retry_at` | DATETIME NULL | 타임아웃 처리 실패 시 다음 재시도 시각 |
| `confirmed_at` | DATETIME | 승인 시각, `NULL` 가능 |
| `canceled_at` | DATETIME | 취소 시각, `NULL` 가능 |
| `hospital_cancel_reason` | VARCHAR(255) | 병원 확정 예약 취소 사유, `NULL` 가능 |
| `hospital_canceled_at` | DATETIME(6) | 병원 확정 예약 취소 시각, `NULL` 가능 |
| `no_show_at` | DATETIME | 노쇼 판정 시각, `NULL` 가능 |
| `no_show_pending_at` | DATETIME(6) NULL | 자동 노쇼 추가 유예 진입 시각 |
인덱스: `(slot_id)`, `(member_id, status)`, `(hospital_id, status)`, `(status, approval_deadline_at)` — 슬롯·회원·병원별 예약 조회와 승인 타임아웃 배치에 사용한다.

기존 예약이 있는 환경은 nullable 컬럼 추가 → `min(requested_at + 1시간, slot.start_at - 2시간)` 백필 → 검증 후 `NOT NULL`·인덱스 적용 순서로 일회성 마이그레이션한다. `reservation_approval_deadline_v1` 마커와 MySQL `GET_LOCK`으로 중복 실행을 막는다.
슬롯은 반환 후 다시 사용될 수 있어 예약 이력과 1:N 관계다. 같은 시점의 활성 예약 1건은 `reservation_slots.version` 낙관적 락으로 `OPEN → RESERVED` 점유를 원자적으로 처리해 보장한다.
### `reservation_events`
| 필드 | 타입 | 제약·설명 |
| --- | --- | --- |
| `id` | BIGINT | PK |
| `reservation_id` | BIGINT | 예약 FK |
| `event_type` | VARCHAR | `CHECKED_IN`, `HOSPITAL_CANCELED`, `AUTO_NO_SHOW_PENDING`, `AUTO_NO_SHOW`, `MANUAL_NO_SHOW`, `NO_SHOW_CORRECTED`, `TIMEOUT_REJECTED` |
| `memo` | VARCHAR | 메모, `NULL` 가능 |
| `processed_by` | BIGINT | 수동 처리자 회원 ID, 자동 처리면 `NULL` |
| `occurred_at` | DATETIME | 발생 시각 |
UNIQUE: `(reservation_id, event_type)` — 같은 사건은 재요청되어도 한 번만 기록한다.

`reservation_events`는 append-only 방식 B를 사용한다. 정상 전이는 `reservations`의 상태·시각 컬럼으로 표현하되, 직원 체크인과 노쇼 대기·자동/수동 판정·정정·승인 타임아웃처럼 상태만으로 처리 시각이나 사건 흔적을 잃는 경우는 기록한다. 수동 사건은 사유와 인증된 처리자 ID를 함께 남기고, 자동 사건의 처리 주체는 `SYSTEM` 정책으로 구분한다. 기존 중복은 `reservation_event_unique_v1` 일회성 마이그레이션에서 최초 이력만 보존해 정리한 뒤 UNIQUE를 명시적으로 추가·검증한다.
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
| `is_default` | BOOLEAN | 기본 결제수단 여부, `NOT NULL DEFAULT false` |
| `active_default_member_id` | BIGINT | 생성 컬럼 — `ACTIVE`이고 기본값일 때만 `member_id`, 아니면 `NULL` |
| `created_at` | DATETIME | 생성 시각 |
제약: `UNIQUE(active_default_member_id)` — 회원별 활성 기본 결제수단을 최대 1건으로 제한한다. 기본값 변경 시 활성 수단 조회는 `idx_payment_methods_member_id_status(member_id, status)`를 사용한다.
### `payments`
| 필드 | 타입 | 제약·설명 |
| --- | --- | --- |
| `id` | BIGINT | PK |
| `reservation_id` | BIGINT | 예약 FK. 정정·복구 이력을 보존하므로 예약과 결제는 1:N |
| `merchant_payment_id` | VARCHAR | 가맹점 결제 ID, UNIQUE |
| `payment_method_id` | BIGINT | 결제수단 FK |
| `card_brand_snapshot` | VARCHAR | 결제 당시 카드사, `NULL` 가능 |
| `card_last4_snapshot` | VARCHAR | 결제 당시 카드 끝 4자리, `NULL` 가능 |
| `amount` | INT | `0 < amount ≤ 3,000,000` |
| `status` | VARCHAR | `PENDING`, `PAID`, `OFFLINE_REQUIRED`, `OFFLINE_PAID`, `REFUNDED` |
| `payment_channel` | VARCHAR | `BILLING_KEY`, `OFFLINE` |
| `retry_count` | INT | 재시도 횟수 |
| `failure_reason` | VARCHAR | 실패 사유, `NULL` 가능 |
| `pg_payment_id` | VARCHAR | PG 결제 ID, `NULL` 가능 |
| `offline_required_at` | DATETIME | 자동 청구 실패로 오프라인 전환된 시각, `NULL` 가능 |
| `offline_settled_at` | DATETIME | 오프라인 정산 시각, `NULL` 가능 |
| `offline_settled_by` | BIGINT | 정산 처리자, `NULL` 가능 |
| `created_at` | DATETIME | 생성 시각 |
| `paid_at` | DATETIME | 결제 완료 시각, `NULL` 가능 |
| `refunded_at` | DATETIME | 전액 환불 확정 시각, `NULL` 가능 |
| `correction_of` | BIGINT | 정정한 이전 결제 FK, `NULL` 가능 |
| `recovery_of` | BIGINT | 셀프 재청구로 대체한 이전 결제 FK, `NULL` 가능. `correction_of`와 동시 사용 금지 |
| `superseded_at` | DATETIME | 다른 결제로 대체된 시각, `NULL`이면 활성 |
| `active_reservation_id` | BIGINT | 저장 생성 컬럼. 활성 결제일 때만 `reservation_id`, 아니면 `NULL` |

`UNIQUE(active_reservation_id)`가 "예약당 활성 결제 1건"을 강제하며 기존 `UNIQUE(reservation_id)`는 제거됐다. `CHECK (NOT (correction_of IS NOT NULL AND recovery_of IS NOT NULL))`는 한 결제가 정정·복구 체인에 동시에 속하는 것을 막는다. 환불·대체된 과거 결제의 `active_reservation_id`는 `NULL`이므로 제약을 타지 않고 이력으로 남는다.

제약 교체는 `PaymentActiveConstraintMigrationRunner`가 expand/contract 순서로 수행한다 — ① 새 컬럼·생성 컬럼·`UNIQUE(active_reservation_id)` 추가(구 UNIQUE 유지) → ② 중복 활성 검사와 마커 기록 → ③ `UNIQUE(reservation_id)` 제거. 재청구 경로는 이 마이그레이션이 완료된 스키마에서 동작한다.

### `payment_refunds`
| 필드 | 타입 | 제약·설명 |
| --- | --- | --- |
| `id` | BIGINT | PK |
| `payment_id` | BIGINT | 결제 FK, UNIQUE — 이 제약이 환불 선점이다 |
| `merchant_refund_id` | VARCHAR | PG 취소 멱등키, UNIQUE. 재시도에도 재사용 |
| `amount` | INT | 환불 금액. 전액 환불만 지원하므로 `payments.amount`와 같다 |
| `reason` | VARCHAR | 스태프 입력 사유. 감사용이며 보호자 알림·응답·영수증에 노출하지 않는다 |
| `status` | VARCHAR | `REQUESTED`, `COMPLETED`, `FAILED` |
| `pg_cancel_id` | VARCHAR | 공급자 취소 식별자, `NULL` 가능 |
| `failure_reason` | VARCHAR | 실패 분류값, `NULL` 가능 |
| `refunded_by` | BIGINT | 환불을 실행한 스태프 member_id |
| `claimed_at` | DATETIME | 선점 시각 |
| `claim_token` | VARCHAR | 선점 소유권 펜스 |
| `refunded_at` | DATETIME | 취소 확정 시각, `COMPLETED`에서만 |
| `created_at` / `updated_at` | DATETIME | 생성·갱신 시각 |

### `payment_items`
| 필드 | 타입 | 제약·설명 |
| --- | --- | --- |
| `id` | BIGINT | PK |
| `reservation_id` | BIGINT | 예약 FK, `NOT NULL`. 청구 전 초안도 이 값으로 존재한다 |
| `payment_id` | BIGINT | 결제 FK, `NULL` 가능. `NULL`이 "아직 청구되지 않은 초안" |
| `name` | VARCHAR | 항목 명칭 |
| `quantity` | INT | 항상 양수(요청 DTO `@Positive`) |
| `unit_price` | INT | signed — 할인·조정은 음수. `UNSIGNED` 금지 |
| `amount` | INT | signed — 서버가 `quantity * unit_price`로 산출. `UNSIGNED` 금지 |
| `created_at` | DATETIME | 생성 시각 |
인덱스: `(reservation_id, payment_id)`, `(payment_id)`. 제약: `CHECK chk_payment_items_quantity_positive (quantity > 0)`, `CHECK chk_payment_items_line_amount (amount = quantity * unit_price)` — `ddl-auto=update`가 CHECK를 만들지 않으므로 `payment_item_constraints_v1` 마이그레이션에서 추가·검증한다.

항목은 일반 청구 선기록 전 예약에 매달린 초안으로 만들고(그 시점에는 `payments` 행이 없다), 선기록은 같은 트랜잭션에서 `payments`를 먼저 INSERT해 채번된 id를 얻은 뒤 그 id로 초안을 스탬프해 스냅샷을 고정한다(`payments.id`가 IDENTITY라 순서를 뒤집을 수 없다). 스탬프 건수가 재조회한 초안 수와 다르면 전체를 롤백한다. 수정·삭제는 `WHERE payment_id IS NULL` 조건부로만 수행하고 0건이면 이미 청구된 것으로 거부한다. 불변식은 스탬프된 항목에 한해 `payments.amount = sum(payment_items.amount)`이고 합계는 0 초과·절대 상한 이하다. 결제당 항목은 `0..N`이며 항목화 이전 결제는 항목이 없고 백필하지 않는다. 항목 없는 레거시 `OFFLINE_REQUIRED` 결제의 셀프 재청구도 가짜 항목을 만들지 않아 0건을 유지하고 원 총액만 승계한다.
## 6. AI·알림·채팅
### `ai_consultations`
| 필드 | 타입 | 제약·설명 |
| --- | --- | --- |
| `id` | BIGINT | PK |
| `member_id` | BIGINT | 회원 FK, 비로그인 상담은 `NULL` 가능 |
| `symptom_text` | TEXT | 개인정보 패턴을 마스킹한 텍스트만 저장, 30일 보존 후 삭제 |
| `structured_result` | JSON | AI 구조화 출력 5필드 전체 원본 |
| `required_capabilities` | JSON | 검색·조회용 중복 저장 진료역량 |
| `urgency_level` | VARCHAR | `LOW`, `MODERATE`, `HIGH`; 조회·집계용 중복 저장 |
| `model` | VARCHAR | 사용 모델, LLM 미호출은 `NULL` |
| `prompt_version` | VARCHAR | 실제 사용 프롬프트 버전, Fake·LLM 미호출은 `NULL` |
| `prompt_tokens` | INT | 입력 토큰 수, LLM 미호출은 `NULL` |
| `completion_tokens` | INT | 출력 토큰 수, LLM 미호출은 `NULL` |
| `latency_ms` | INT | 응답 지연시간 |
| `status` | VARCHAR | `SUCCESS`, `FAILED` |
| `error_type` | VARCHAR | AI Gateway 실패 원인(`TIMEOUT`, `TEMPORARY_UNAVAILABLE`, `INVALID_RESPONSE`), 성공·LLM 미호출은 `NULL` |
| `fallback_used` | BOOLEAN | 대체 처리 여부 |
| `tool_call_status` | VARCHAR | Tool 호출 상태 |
| `schema_parse_success` | BOOLEAN | 구조화 출력 파싱 성공 여부 |
| `created_at` | DATETIME | 생성 시각 |
### `notifications`
| 필드 | 타입 | 제약·설명 |
| --- | --- | --- |
| `id` | BIGINT | PK |
| `member_id` | BIGINT | 회원 FK |
| `type` | VARCHAR(40) | `RESERVATION_REQUESTED`(병원 수신), `RESERVATION_CONFIRMED`, `RESERVATION_REJECTED`, `RESERVATION_HOSPITAL_CANCELED`, `RESERVATION_WAITLIST_OFFERED`, `PAYMENT_RESULT`, `PAYMENT_PENDING`, `NO_SHOW` |
| `content` | VARCHAR | 알림 내용 |
| `resource_type` | VARCHAR(40) NULL | 연결 리소스 종류(RESERVATION / RESERVATION_WAITLIST / PAYMENT) — generic 참조 |
| `resource_id` | BIGINT NULL | 연결 리소스 id(논리 참조) |
| `read_at` | DATETIME NULL | 읽은 시각(NULL=미읽음). `isRead`는 `read_at IS NOT NULL` 파생 |
| `created_at` | DATETIME | 생성 시각 |

`type`·`resource_type`은 MySQL native ENUM이 아니라 VARCHAR(40)이고 **허용 값 CHECK 제약도 없다**(이슈 #176) — 엔티티에 `@JdbcTypeCode(SqlTypes.VARCHAR)`를 못박고, 기존 DB의 ENUM 전환과 Hibernate가 신규 DB에 만드는 CHECK 제약 드롭을 `notification_type_varchar_v1` 마커 러너가 함께 처리한다. 유형 값 추가에 DDL이 필요하지 않고, 값 유효성은 애플리케이션 enum 파싱이 전담한다.
### `chat_messages`
| 필드 | 타입 | 제약·설명 |
| --- | --- | --- |
| `id` | BIGINT | PK |
| `reservation_id` | BIGINT | 예약 논리 참조 |
| `sender_type` | VARCHAR | `GUARDIAN`, `HOSPITAL` |
| `hospital_id` | BIGINT | 예약 병원 ID, 감사·병원 단위 읽음 기준 |
| `member_id` | BIGINT | 실제 발신자 ID(감사용) |
| `body` | VARCHAR(1000) | 텍스트 본문 |
| `client_message_id` | VARCHAR(36) | NOT NULL, 클라이언트 재전송 UUID |
| `created_at` | DATETIME | 생성 시각 |
| `read_at` | DATETIME NULL | 반대 측 읽음 시각 |
인덱스: `(reservation_id, created_at, id)`, `(reservation_id, sender_type, read_at)`, `UNIQUE(reservation_id, member_id, client_message_id)`. 기존 행은 `chat_message_client_message_id_v1` 선행 마이그레이션에서 UUID 백필 후 UNIQUE·NOT NULL을 적용하며, 배포 중 구버전 INSERT는 트리거로 UUID를 채운다.
## 7. 확장 테이블
### `payment_webhooks` `(확장)`
| 필드 | 타입 | 제약·설명 |
| --- | --- | --- |
| `id` | BIGINT | PK |
| `payment_id` | BIGINT | 결제 FK |
| `event_type` | VARCHAR | 이벤트 유형 |
| `received_at` | DATETIME | 수신 시각 |
제약: `UNIQUE(payment_id, event_type)` — 동일 결제 이벤트의 중복 수신을 한 번만 반영한다.
## 8. 마이그레이션 관리
### `schema_migrations`
| 필드 | 타입 | 제약·설명 |
| --- | --- | --- |
| `migration_key` | VARCHAR | PK, 마이그레이션 식별자(예: `email_verified_backfill_v1`, `chat_message_client_message_id_v1`) |
| `applied_at` | DATETIME | 실행 시각 |
Flyway/Liquibase 없이 `ddl-auto=update`로만 스키마를 관리하므로, "배포 시 한 번만" 실행돼야 하는 일회성 데이터 백필(예: `email_verified` 기존 회원 백필)의 실행 여부를 기록하는 범용 마커 테이블이다. 도메인 데이터가 아니라 마이그레이션 인프라이므로 다른 테이블과 관계를 맺지 않는다.
## 9. 설계상 필수 규칙
- 제휴 병원은 `hospitals.partnership_status`로 판별한다.
- 병원 찜은 제휴 여부와 관계없이 허용하며 인증된 보호자와 병원의 조합을 UNIQUE로 유지한다.
- 공공데이터와 제휴 데이터는 `local_gov_code + mgmt_no` 복합 키로 매핑하고 복합 UNIQUE로 보장한다.
- `pet_profiles`의 확정 필드는 `name`, `species`, `age`, `weight`, `neutered`이다.
- 비로그인 AI 상담을 허용하므로 `ai_consultations.member_id`는 `NULL`을 허용한다.
- `reservation_slots.version`은 낙관적 락에 사용한다.
- 노쇼 처리 시 슬롯 상태는 `RESERVED`를 유지한다.
- 오프라인 정산은 결제 상태가 `OFFLINE_REQUIRED`일 때만 수행한다.
- 보호자 셀프 재청구도 `OFFLINE_REQUIRED`에서만 허용하고 `PENDING`에서는 허용하지 않는다.
- 활성 결제는 상태가 아니라 `payments.superseded_at IS NULL`로 판정한다. 대체는 `OFFLINE_REQUIRED`·`REFUNDED`에서만 허용하고 `PAID`·`OFFLINE_PAID`는 대체하지 않는다.
- 오프라인 정산과 셀프 재청구는 둘 다 활성 조건을 포함한 조건부 UPDATE로만 성립해 하나만 이긴다(이중 수납 방지).
- 할인·조정은 별도 할인 필드가 아니라 음수 금액 항목으로 기록하므로 `payment_items.unit_price`·`amount`를 `UNSIGNED`로 만들지 않는다.
