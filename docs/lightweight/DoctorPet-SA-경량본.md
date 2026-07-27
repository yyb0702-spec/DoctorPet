# DoctorPet 소프트웨어 아키텍처 문서(SA) 경량본
> **이 문서는 열람용 요약이다. 구현 기준은 아래 저장소 정본을 따른다. 경량본과 정본이 다르면 PRD → SA → 코드 컨벤션 → 정책 정리본 순으로 적용한다.**
| 정본 | 경로·버전 |
| --- | --- |
| 제품 요구사항 | `docs/product/DoctorPet-PRD.md` v3.5 |
| 시스템 설계·ERD·API·상태 머신 | `docs/architecture/DoctorPet-SA.md` v1.8, REST API는 §8 |
| 코드 컨벤션 | `docs/architecture/DoctorPet-코드컨벤션.md` v1.0 |
| 정책 원본 | `docs/domain/반려동물병원예약-정책정리본.md` v8 |
## 1. 시스템 구성
DoctorPet은 보호자용 API, 병원 직원용 API, 외부 연동 및 배치 작업으로 구성한다.
- 보호자: 회원·반려동물 관리, AI 증상 상담, 병원 검색, 예약, 결제, 알림
- 병원 직원: 예약 승인·거절, 체크인, 진료 상태 변경, 진료비 청구, 오프라인 정산
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
- 폐기된 Refresh Token의 재사용이 탐지되면 해당 사용자의 전체 세션을 로그아웃한다.
- 회원 탈퇴 시 이메일을 `withdrawn_{memberId}@deleted.doctorpet` 형식으로 익명화하고 Soft Delete한다.
## 5. 병원·검색·공공데이터
- 병원 기본정보는 공공데이터를 자체 DB에 적재하여 검색에 사용한다.
- 제휴 여부는 `hospitals.partnership_status`로 판별한다.
- 공공데이터와 제휴 데이터는 `local_gov_code + mgmt_no` 복합 키로 매핑하며 두 컬럼에 복합 UNIQUE를 적용한다.
- 검색은 QueryDSL 동적 `where`로 전달된 조건만 조합하며, 진료역량은 요청값을 모두 가진 병원만 남기는 AND 매칭을 사용한다.
- 거리는 좌표 반경 사각박스로 후보를 줄인 뒤 애플리케이션에서 정밀 계산·정렬한다. 페이징 count 쿼리를 분리하고 결과 DTO를 직접 조회한다.
- 조회 빈도가 높은 검색 결과는 Redis 원격 캐시에 저장하며 Caffeine은 사용하지 않는다.
- MVP에서는 특정 지자체 데이터를 한 번 시드 적재한다.
- 공공데이터 주기 갱신 배치는 확장 범위이며 MVP 스케줄러에 포함하지 않는다.
- 검색 성과 목표는 P95 300ms 이하, 100 RPS, 오류율 1% 이하이다.
## 6. AI 증상 상담
- 비로그인 상담과 임시 반려동물 정보를 허용하므로 `ai_consultations.member_id`는 `NULL`일 수 있다.
- AI는 진단·처방하지 않고 증상을 필요한 진료역량으로 구조화한 뒤 실제 병원 검색 Tool을 호출한다.
- 구조화 출력은 다음 5개 필드로 고정한다.
| 필드 | 설명 |
| --- | --- |
| `possibleFocusAreas` | 의심되는 진료 집중 영역 |
| `requiredCapabilities` | 병원 검색에 필요한 진료역량 |
| `urgencyLevel` | 긴급도 |
| `preVisitCheckpoints` | 내원 전 확인 사항 |
| `recommendVetVisit` | 병원 방문 권고 여부 |
- 응급 키워드가 감지되면 LLM 판단을 우회하고 고정 응답을 반환한다.
- 화이트리스트 밖의 카테고리에는 `정확한 답변이 어렵습니다`를 반환한다.
- **\[결정 필요 — 정본 충돌\]** 병원 검색 Tool 실패 시 PRD·정책은 실패 컨텍스트를 LLM에 전달해 안내 문구를 생성하고, SA 장애 격리는 `fallback=true`로 직접 검색을 유도한다. LLM 재호출 여부와 서버 고정 문구 사용 여부는 추후 팀 합의 후 정본을 함께 수정하며, 결정 전에는 구현하지 않는다.
- 증상 원문은 전화번호·이메일·주민번호 등 패턴을 마스킹해 30일간 보존하고, 30일이 지난 원문은 배치로 삭제한다. 배치 주기와 구현 방식은 정본에 확정되어 있지 않다.
- 구조화 출력 5필드 전체를 `structured_result` JSON으로 저장한다. `required_capabilities`와 `urgency_level`은 검색·조회·집계를 위해 별도 컬럼에 중복 저장하고 같은 트랜잭션에서 일관되게 기록한다. `error_type`, `tool_call_status`, `schema_parse_success` 등도 저장해 품질 지표를 측정한다.
## 7. 예약과 상태 전이
### 예약 흐름
```plain text
REQUESTED → CONFIRMED → CHECKED_IN → IN_TREATMENT → TREATMENT_COMPLETED
REQUESTED → REJECTED
REQUESTED → CANCELED
CONFIRMED → CANCELED
CONFIRMED → NO_SHOW
NO_SHOW → CHECKED_IN  // 병원 오판정 정정
```
- 슬롯과 예약 이력은 1:N이다. 거절·취소·승인 타임아웃으로 반환된 슬롯은 다른 예약에 다시 사용될 수 있다.
- 같은 시점의 활성 예약은 1건만 허용하며, 예약 요청 시 낙관적 락으로 슬롯을 `OPEN → RESERVED` 전환한다.
- 병원 거절 사유는 직원 부족, 슬롯 등록 오류, 진료 불가, 기타의 4종으로 관리한다.
- 예약 승인 타임아웃 또는 허용 범위 내 취소·거절 시 슬롯을 반환한다.
- 체크인은 예약시간부터 예약시간 +10분까지 허용한다.
- +10분이 지나도록 체크인하지 않으면 자동으로 `NO_SHOW` 처리한다.
- 노쇼는 예약시각이 지난 건이므로 슬롯을 반환하지 않고 `RESERVED`로 유지한다.
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
- 재시도 가능한 오류도 재시도 횟수를 모두 소진하면 `OFFLINE_REQUIRED`로 전환한다.
- 오프라인 정산은 `OFFLINE_REQUIRED` 상태에서만 허용한다.
- 정산 후 `OFFLINE_PAID`와 처리 시각·처리자를 기록한다.
- 오프라인 정산 후에는 자동 재시도 파이프라인을 중단하여 이중 청구를 방지한다.
- 환불과 정정 후 재청구는 MVP에서 제외한다.
- 결제 웹훅은 확장 범위이다.
## 9. 슬롯과 스케줄러
- 예약 슬롯은 14일치를 사전 생성한다.
| 작업 | 주기 | 비고 |
| --- | --- | --- |
| 자동 노쇼 판정 | 1분 | 예약시간 +10분 경과 건 처리 |
| 예약 승인 타임아웃 | 1분 | 응답 기한 경과 건 처리 |
| 결제 상태 정산 | 5분 | 미확정 결제 상태 확인 |
| 슬롯 생성 | 일 배치 | 향후 14일치 유지 |
| 공공데이터 주기 갱신 | 확장 | MVP는 1회 시드 적재 |
## 10. 데이터·보안 원칙
- 비밀번호와 빌링키는 정본의 저장·암호화 규칙을 따른다.
- 탈퇴 회원의 업무 이력은 참조 무결성을 유지하면서 개인정보를 익명화한다.
- 외부 시스템 요청·응답에는 민감정보를 그대로 기록하지 않는다.
- 상태 변경과 외부 결제 처리는 멱등성과 중복 실행 방지를 보장한다.
## 11. 미확정 사항 — 구현 금지
- LLM 모델·제공자와 프롬프트 외부화 저장 방식
- 진료역량 화이트리스트의 구체 값 목록
- AI 입력 길이 제한과 Rate Limit 구체 수치
- 실시간 push 방식(SSE 또는 WebSocket+STOMP)
- 활성 예약·미수금을 보유한 회원의 탈퇴 처리
- 검색 Tool 실패 응답 주체: LLM 안내 생성 또는 서버 `fallback=true`·고정 문구
위 항목은 저장소 SA 부록 A에서 결정되기 전까지 임의로 구현하지 않는다.
