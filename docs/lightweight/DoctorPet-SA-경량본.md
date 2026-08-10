# DoctorPet 소프트웨어 아키텍처 문서(SA) 경량본
> **이 문서는 열람용 요약이다. 구현 기준은 아래 저장소 정본을 따른다. 경량본과 정본이 다르면 PRD → SA → 코드 컨벤션 → 정책 정리본 순으로 적용한다.**
| 정본 | 경로·버전 |
| --- | --- |
| 제품 요구사항 | `docs/product/DoctorPet-PRD.md` v3.23 |
| 시스템 설계·ERD·API·상태 머신 | `docs/architecture/DoctorPet-SA.md` v1.50, REST API는 §8 |
| 코드 컨벤션 | `docs/architecture/DoctorPet-코드컨벤션.md` v1.0 |
| 정책 원본 | `docs/domain/반려동물병원예약-정책정리본.md` v12 |
## 1. 시스템 구성
DoctorPet은 보호자용 API, 병원 직원용 API, 외부 연동 및 배치 작업으로 구성한다.
- JPA 감사 시각과 시간 기반 배치는 JVM 기본 시간대가 아니라 공통 `Asia/Seoul` Clock을 사용한다.
- 보호자: 회원·반려동물 관리, AI 증상 상담, 병원 검색·찜, 예약, 결제, 알림
- 병원 직원: 예약 승인·거절, 체크인, 진료 상태 변경, 진료비 청구, 오프라인 정산
- 승인 타임아웃은 예약 생성 시 저장한 `approval_deadline_at`을 기준으로 처리하며, 기존 예약은 공식에 따라 백필하는 일회성 마이그레이션을 적용한다.
- 처리 실패 건은 `approval_timeout_next_retry_at` 이후에만 다시 조회해 실행 상한에 걸린 뒤에도 정상 예약이 계속 처리되도록 한다.
- 외부 연동: AI, 결제대행사, 공공데이터, 알림 전송
- 배치: 승인 타임아웃, 자동 노쇼, 결제 상태 정산
## 2. 기술 스택
| 구분 | 기술 |
| --- | --- |
| 언어·프레임워크 | Java 17, Spring Boot |
| 데이터 접근 | Spring Data JPA, QueryDSL |
| 인증·보안 | Spring Security, JWT |
| 데이터베이스 | MySQL 8 |
| 캐시·토큰 저장 | Redis, Lettuce |
| 예약 동시성 | 낙관적 락, 조건부 UPDATE 비교 구현 |
| 외부 연동 | PortOne V2 |
| 배치 | Spring Scheduler |
| 빌드 | Gradle |
| 테스트 | JUnit 5, Mockito, `@SpringBootTest` |
## 3. 패키지와 계층
```plain text
global/
├─ config
├─ security
├─ exception
├─ response
└─ gateway
   ├─ AiGateway
   ├─ PaymentGateway
   └─ PublicDataGateway

domain/
├─ member
├─ pet
├─ hospital
├─ search
├─ reservation
├─ payment
├─ ai
├─ notification
└─ publicdata
```
- 기본 호출 구조는 `Controller → Service`이다.
- 여러 도메인을 조합하는 흐름에만 `XxxApplicationService`를 사용한다.
- AI·결제·공공데이터 연동은 각각 `AiGateway`, `PaymentGateway`, `PublicDataGateway`로 추상화한다.
- 알림 전송은 `NotificationPusher` 인터페이스로 추상화한다.
## 4. 인증과 회원
- 회원 역할은 `GUARDIAN`, `HOSPITAL_STAFF`로 구분한다.
- Access Token 수명은 30분\~1시간이다.
- Refresh Token 수명은 14일이다.
- Refresh Token은 서버에 저장하고 회전한다.
- 같은 회원의 재발급 요청은 `refresh-lock:{memberId}` 락(TTL 3초)으로 직렬화한다. 락을 얻을 때마다 단조 증가하는 펜싱 토큰(`refresh-fence:{memberId}`)을 함께 발급해, 락 TTL 만료로 더 최신 요청이 새 락을 먼저 얻은 뒤에도 뒤늦게 도착한 예전 요청의 회전 시도가 방금 성공한 세션을 지우지 않도록 막는다(이슈 #100).
- 재발급 회전은 펜싱 토큰이 최신인지 먼저 확인해, 더 최신 락이 이미 발급됐거나 회전까지 끝났으면 값을 건드리지 않고 `STALE`로 물러난다. 이 검사를 통과했는데도 저장된 값과 일치하지 않으면 진짜 재사용(`REUSED`)으로 판단해 해당 사용자의 전체 세션을 로그아웃한다.
- 로그인 5회 연속 실패 시 30분간 계정을 잠그며(`members.failed_login_attempts`, `locked_until`), 시간 경과 또는 비밀번호 재설정 성공 시 즉시 해제된다.
- 회원 탈퇴 시 이메일을 `withdrawn_{memberId}@deleted.doctorpet` 형식으로 익명화하고 Soft Delete한다.
## 5. 병원·검색·공공데이터
- 병원 기본정보는 공공데이터를 자체 DB에 적재하여 검색에 사용한다.
- 제휴 여부는 `hospitals.partnership_status`로 판별한다.
- 공공데이터와 제휴 데이터는 `local_gov_code + mgmt_no` 복합 키로 매핑하며 두 컬럼에 복합 UNIQUE를 적용한다.
- 검색은 QueryDSL 동적 `where`로 전달된 조건만 조합하며, 진료역량은 요청값을 모두 가진 병원만 남기는 AND 매칭을 사용한다.
- 진료역량 화이트리스트는 8개 축종과 11개 검사·진료·장비 값, 총 19개로 확정하며 병원 시드·검색·AI가 공유한다.
- 반경 검색은 좌표 사각박스로 후보를 줄인 뒤 애플리케이션에서 정밀 거리를 계산한다. 무반경 거리순은 MySQL `ST_Distance_Sphere` 정렬·DB 페이징을 사용한다. 현재 영업 필터는 정렬된 후보를 200건 단위로 읽어 최대 메모리 적재량을 제한하지만, 정확한 전체 건수를 위해 후보 전체를 스캔하므로 DB 왕복이 증가할 수 있다. 전국 데이터 성능 측정과 DB 영업시간 필터 전환 여부는 후속 검증 대상으로 둔다.
- 병원 검색 최초 진입 시 제휴 병원을 우선 노출하고, 모든 사용자가 공통 조회하는 기본 첫 페이지의 정적 조회 결과만 Redis에 저장한다. 조건 검색·거리·현재 영업·뒤쪽 페이지는 캐시하지 않으며 Caffeine은 사용하지 않는다.
- 병원 찜은 인증된 보호자만 등록·해제·목록 조회할 수 있고 `UNIQUE(member_id, hospital_id)`로 중복을 막는다. 검색·상세의 `favorite`은 비로그인·병원 스태프에게 `false`이며, 보호자 요청은 페이지 병원 ID를 일괄 조회해 결합한다.
- 회원별 `favorite`은 공유 검색 캐시에 저장하거나 캐시 키에 포함하지 않는다. 찜 변경으로 검색 캐시를 삭제하지 않고 응답 직전에 MySQL 원본과 결합한다.
- 검색 성능용 인덱스는 전국 단위 데이터 확장 단계에서 실행 계획과 응답시간을 측정한 뒤 적용한다.
- 전국 공공데이터를 자체 DB에 적재하고 매주 월요일 03:00에 갱신한다.
- 다중 인스턴스의 공공데이터 중복 갱신은 MySQL `GET_LOCK`으로 막는다.
- 검색 성과 목표는 P95 300ms 이하, 100 RPS, 오류율 1% 이하이다.
## 6. AI 증상 상담
- 비로그인 상담과 임시 반려동물 정보를 허용하므로 `ai_consultations.member_id`는 `NULL`일 수 있다.
- AI는 진단·처방하지 않고 증상을 필요한 진료역량으로 구조화한 뒤 실제 병원 검색 Tool을 호출한다.
- 구조화 출력은 다음 5개 필드로 고정한다.
| 필드 | 설명 |
| --- | --- |
| `possibleFocusAreas` | 질환명이 아닌 허용된 증상 관찰 범위 enum을 서버 문구로 변환한 값 |
| `requiredCapabilities` | 병원 검색에 필요한 진료역량 |
| `urgencyLevel` | 긴급도 |
| `preVisitCheckpoints` | 허용된 보호자 관찰 항목 enum을 서버 문구로 변환한 값 |
| `recommendVetVisit` | 병원 방문 권고 여부 |
- 응급 키워드가 감지되면 LLM 호출을 생략하고 고정 응답을 반환하며, LLM 결과가 `urgencyLevel=HIGH`인 경우에도 즉시 병원 안내로 강제 분기한다.
- 화이트리스트 밖의 카테고리에는 `정확한 답변이 어렵습니다`를 반환한다.
- 검색 Tool 실패 시 LLM을 재호출하지 않고 서버 고정 안내와 `fallback=true`로 직접 검색을 유도한다.
- 입력은 공백이 아닌 `symptomText` 1~1,000자와 확정된 8개 축종 중 하나인 `species`가 필수다.
- `region`, `latitude`, `longitude`는 선택이며 좌표 두 값은 함께 전달한다. 위치 권한은 프론트가 요청하고 서버는 전달받은 좌표를 검색에만 사용한다.
- Tool 조건은 축종·진료역량·응급·야간·현재 영업·거리 의도로 제한한다. “지금 진료 가능한”은 `openNow`, “야간·24시간”은 `nightCare`, “가까운·근처”는 좌표가 있을 때만 거리순으로 매핑한다.
- 실제 현재 영업 여부는 서버가 `Asia/Seoul` 시각으로 판정한다. 좌표 없이 거리순이라고 표현하거나 병원 위치를 추측하지 않는다.
- 응급이며 좌표가 있으면 거리 표현 없이도 `emergency=true`, `openNow=true`, `sort=distance`를 적용한다. 좌표가 없으면 `locationRecommended=true`로 선택적 위치 제공을 권장하되 응급 안내와 가능한 지역 검색을 지연하지 않는다. 좌표와 지역이 모두 없으면 전국 이름순 결과를 응급 추천으로 제공하지 않는다.
- 수술·입원 조건의 AI 자동 해석, 복합 조건 우선순위·완화, 미래 방문 시각, 대화형 질문과 개인화는 확장 범위다.
- Rate Limit은 로그인 회원당 1분 5회, 비로그인 IP당 1분 3회·서울 날짜당 30회이며 설정값으로 관리한다.
- 전화번호·이메일·주민번호 등 개인정보 패턴을 마스킹한 증상 텍스트만 외부 AI Gateway에 전달하고 DB에 30일간 보존한 뒤 삭제한다.
- 구조화 출력 5필드 전체를 `structured_result` JSON으로 저장한다. `required_capabilities`와 `urgency_level`은 검색·조회·집계를 위해 별도 컬럼에 중복 저장하고 같은 트랜잭션에서 일관되게 기록한다. AI Gateway 실패 시 `error_type`에 `TIMEOUT`, `TEMPORARY_UNAVAILABLE`, `INVALID_RESPONSE`를 저장하고, `tool_call_status`, `schema_parse_success` 등도 저장해 품질 지표를 측정한다.
- 공통 기능은 `FakeAiGateway`로 먼저 구현하고 실제 연동은 OpenAI `gpt-4.1-mini`의 Structured Outputs를 사용한다. Fake 단계에는 프롬프트 파일을 만들지 않는다.
- timeout·fallback·기본 Circuit Breaker는 MVP에 포함하고 세밀한 튜닝·대시보드는 확장으로 둔다.
## 7. 예약과 상태 전이
### 예약 흐름
```plain text
REQUESTED → CONFIRMED → CHECKED_IN → IN_TREATMENT → TREATMENT_COMPLETED
REQUESTED → REJECTED
REQUESTED → CANCELED
CONFIRMED → CANCELED
CONFIRMED → NO_SHOW_PENDING  // 예약시각 +10분 초과 자동 대기
NO_SHOW_PENDING → NO_SHOW  // 추가 5분 초과 자동 최종 판정
CONFIRMED/NO_SHOW_PENDING → CHECKED_IN  // 직원 도착 확인
CONFIRMED → NO_SHOW  // 예약 시작 후 병원 수동 확정
NO_SHOW → CHECKED_IN  // 병원 오판정 정정
```
- 슬롯과 예약 이력은 1:N이다. 거절·취소·승인 타임아웃으로 반환된 슬롯은 다른 예약에 다시 사용될 수 있다.
- 같은 시점의 활성 예약은 1건만 허용하며, 예약 요청 시 낙관적 락으로 슬롯을 `OPEN → RESERVED` 전환한다.
- 병원 거절 사유는 직원 부족, 슬롯 등록 오류, 진료 불가, 기타의 4종으로 관리한다.
- 예약 승인 타임아웃 또는 허용 범위 내 취소·거절 시 슬롯을 반환한다.
- 체크인은 예약시간부터 예약시간 +10분까지 허용한다.
- +10분이 지나도록 체크인하지 않으면 `NO_SHOW_PENDING`으로 바꾸고 기본 5분의 추가 유예를 둔다.
- 추가 유예까지 지나도록 체크인하지 않으면 최종 `NO_SHOW`로 바꾸고 보호자에게 알림을 보낸다.
- 노쇼는 예약시각이 지난 건이므로 슬롯을 반환하지 않고 `RESERVED`로 유지한다.
- 병원 직원은 예약 시작 시각부터 수동 노쇼 확정이 가능하며, 확정·정정 사유와 처리자를 append-only 이력으로 남긴다.
- 노쇼 이력은 전 병원 통합으로 집계하며 정정된 건은 노쇼 횟수에서 제외한다.
### 동시성 보호
- 실제 채택 방식은 낙관적 락이다.
- 조건부 UPDATE를 비교 베이스라인으로 함께 구현한다.
- 두 방식은 `ReservationLockStrategy` 인터페이스로 추상화한다.
- 예약 상태 전이는 `WHERE status = 기대값` 조건을 포함한 UPDATE로 보호한다.
- 스케줄러의 자동 전이 결과가 0행이면 이미 수동 처리된 것으로 보고 무시한다.
- 병원 직원의 수동 판정을 자동 처리보다 우선한다.
## 8. 결제와 정산
- 진료 완료 후 병원이 진료비를 청구한다.
- 진료비는 0원 초과, 300만원 이하만 허용한다.
- 진료비 상한은 코드 상수가 아닌 설정값으로 관리한다.
- 결제 요청 전 `PENDING` 기록과 고유한 `merchant_payment_id`를 생성한다.
- 타임아웃 등으로 결제 결과가 불확실하면 재청구하지 않고 단건 조회로 결과를 확인한다.
- 네트워크 오류와 일시 장애는 단건 조회 후 미처리 건에 한해 최대 3회 지수 백오프로 재시도한다.
- 한도 초과, 카드 정지, 빌링키 만료·삭제 등 재시도가 무의미한 오류는 즉시 `OFFLINE_REQUIRED`로 전환한다.
- 재시도 가능한 오류도 재시도 횟수를 소진하면 마지막 단건 조회로 확인하고, 성공하지 않았음이 확인된 경우에만 `OFFLINE_REQUIRED`로 전환한다. 마지막 결과가 미확정(UNKNOWN·조회 실패)이면 `OFFLINE_REQUIRED`가 아니라 `PENDING`을 유지해 정산 스케줄러가 확정한다(이미 승인됐을 수 있어 이중결제 방지).
- PG가 `PAID`를 반환했더라도 금액 불일치·`pgPaymentId` 누락 등 정합성 오류는 `PAID`도 `OFFLINE_REQUIRED`도 아닌, 사유를 기록한 `PENDING`으로 두고 정산 스케줄러·운영 확인으로 확정한다.
- 오프라인 정산은 `OFFLINE_REQUIRED` 상태에서만 허용한다.
- 정산 후 `OFFLINE_PAID`와 처리 시각·처리자를 기록한다.
- 오프라인 정산 후에는 자동 재시도 파이프라인을 중단하여 이중 청구를 방지한다.
- 환불과 정정 후 재청구는 MVP에서 제외한다.
- 결제 웹훅은 확장 범위이다.
## 9. 슬롯과 스케줄러
- 예약 슬롯은 14일치를 사전 생성한다.
| 작업 | 주기 | 비고 |
| --- | --- | --- |
| 자동 노쇼 판정 | 1분 | +10분 경과 건을 `NO_SHOW_PENDING`, 추가 5분 경과 건을 `NO_SHOW`로 처리 |
| 예약 승인 타임아웃 | 1분 | 응답 기한 경과 건 처리 |
| 결제 상태 정산 | 5분 | 미확정 결제 상태 확인 |
| 슬롯 생성 | 일 배치 | 향후 14일치 유지 |
| 공공데이터 주기 갱신 | 매주 월요일 03:00 | 전국 원천 데이터 갱신 후 제휴 보강 재적용 |

자동 노쇼 배치는 한 실행에서 같은 예약을 대기 전이와 최종 전이로 연달아 처리할 수 있다. 따라서 `maxScannedPerRun`은 예약의 고유 개수가 아니라 조회·전이 시도 횟수이고, `processed`는 한 번 이상 상태 전이에 성공한 예약 수로 집계한다. 대기·최종 전이별 실제 건수는 `reservation_events` 상태 이력으로 확인한다.
## 10. 데이터·보안 원칙
- 비밀번호와 빌링키는 정본의 저장·암호화 규칙을 따른다.
- 탈퇴 회원의 업무 이력은 참조 무결성을 유지하면서 개인정보를 익명화한다.
- 외부 시스템 요청·응답에는 민감정보를 그대로 기록하지 않는다.
- 상태 변경과 외부 결제 처리는 멱등성과 중복 실행 방지를 보장한다.
## 11. 미확정 사항 — 구현 금지
- 실시간 알림은 단방향 SSE로 확정하며, 양방향 WebSocket+STOMP는 채팅 도입 시에만 재논의한다.
- 미인증 계정의 장기 미완료 처리
- SNS 로그인 도입 범위와 기존 계정 연동 정책
- 이메일 인증·비밀번호 재설정 토큰 소비 순서
- 탈퇴 트랜잭션과 Redis 부수효과 순서
위 항목은 저장소 SA 부록 A에서 결정되기 전까지 임의로 구현하지 않는다.

## 12. 병원 슬롯 조회 계약

- 엔드포인트: `GET /api/hospitals/{hospitalId}/slots?date=YYYY-MM-DD`
- `date`는 필수이며, 기준 시간대는 `Asia/Seoul`이다.
- 응답의 `selectedDate`, `dateAvailabilities[].date`, `slots[].startAt`, `slots[].endAt`에는 UTC 오프셋이 없으며, 프론트는 이를 기기 로컬 시간이 아닌 `Asia/Seoul` 기준으로 해석하고 표시한다.
- `dateAvailabilities`는 오늘부터 오늘+13일까지 14개 날짜를 오름차순으로 반환한다.
- DB 상태가 `OPEN`이고 `startAt >= 현재 시각+4시간`인 슬롯이 하나라도 있으면 해당 날짜의 `reservationAvailable=true`다.
- `slots`는 선택 날짜에 시작하는 `OPEN`, `RESERVED` 슬롯을 `startAt ASC`, `id ASC`로 반환한다.
- 자정 이후 슬롯은 `startAt`의 달력 날짜에 포함한다.
- DB 상태는 변경하지 않고 응답 상태만 다음처럼 계산한다.
  - `AVAILABLE`: `OPEN`이고 `startAt >= 현재 시각+4시간`
  - `RESERVED`: `RESERVED`
  - `LEAD_TIME_CLOSED`: `OPEN`이고 `startAt < 현재 시각+4시간`
- 정확히 `현재 시각+4시간`인 슬롯은 `AVAILABLE`이다.
- 과거 또는 오늘+14일 이후 날짜는 `200 OK`와 빈 `slots`를 반환한다.
- 비제휴 또는 영업상태가 `OPEN`이 아닌 병원은 빈 `dateAvailabilities`와 `slots`를 반환한다.
- 존재하지 않는 병원은 `HOSPITAL_NOT_FOUND`, 날짜 형식 오류는 `400 VALIDATION_FAILED`로 처리한다.
