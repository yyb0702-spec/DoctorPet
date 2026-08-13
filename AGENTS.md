# DoctorPet 작업 규칙 (AGENTS)

반려동물 증상 기반 병원 검색·승인형 예약·후불 결제 백엔드 프로젝트.
이 파일이 모든 AI 도구(Claude Code, Codex 등) 공통의 작업 규칙 정본이다.

## 문서 정본

| 문서 | 역할 |
| --- | --- |
| `docs/product/DoctorPet-PRD.md` | 제품 요구사항. 최상위 기준 |
| `docs/architecture/DoctorPet-SA.md` | ERD·API·상태 머신·핵심 설계 |
| `docs/architecture/DoctorPet-코드컨벤션.md` | 코드 스타일·클래스 규약 |
| `docs/domain/반려동물병원예약-정책정리본.md` | 정책 원본 (PRD·SA와 다르면 PRD·SA 우선) |
| `docs/collaboration/github-rules.md` | 팀 브랜치·커밋·PR 규칙 |
| `docs/ai/context-router.md` | 작업별 읽을 문서 지도 (hot path) |
| `docs/ai/rule-source-map.md` | 규칙 정본 지도 (충돌 시 어디를 고칠지) |
| `docs/testing/verification-guide.md` | 검증 Level·판정 값·기록 양식 |
| `docs/enhancement/README.md` | MVP 이후 고도화 델타 레이어(동결 SA를 도메인별 파일로 override) |

문서끼리 충돌하면 PRD > SA > 코드컨벤션 순으로 따르고, 충돌 사실을 사용자에게 알린다. 정책정리본은 PRD·SA 아래다. 브랜치·커밋·PR 절차는 `docs/collaboration/github-rules.md`가 별도 영역의 정본이다. 문서 버전은 각 문서 헤더가 단일 정본이다. 규칙 중복·충돌 정리는 `docs/ai/rule-source-map.md`를 따른다.

## 기술 스택

Java 17, Spring Boot, Spring Data JPA, Spring Security, QueryDSL, MySQL 8.x, Redis(Lettuce), JWT(Access/Refresh), PortOne V2(빌링키 후불 결제), Spring Scheduler, Gradle, JUnit5, Mockito.

## 작업 시작 전

1. 현재 작업 목표와 미커밋 변경을 확인한다. `git status --short --branch`, `git branch -vv`, `git log --oneline -5`로 실제 상태를 본다.
2. `docs/ai/context-router.md`에서 이번 작업의 hot path를 골라 필수 문서 3~5개만 읽는다. `docs/` 전체를 재귀적으로 읽지 않는다.
3. 직전 작업의 반복 실수가 있는지 `docs/ai/agent-mistakes.md`를 확인한다.
4. 문서의 기록과 실제 코드·저장소 상태가 다르면 작업 전에 차이를 알린다.

## 작업 범위

- 한 번에 한 작업만 진행하고, 한 작업에는 한 목표만 둔다.
- 범위 밖 파일은 수정하지 않는다. 구현 경계는 `docs/ai/implementation-guardrails.md`를 따른다.
- SA 부록 A의 `[결정 필요]` 항목이나 비어 있는 정책은 마음대로 구현하지 않고 질문으로 남긴다.
- PRD·SA의 설계 변경이 필요해지면 구현 전에 사용자 확인을 받는다.

## 확정된 핵심 결정 (재논의 금지, 근거는 각 항목에 명시)

- 동시성: 낙관적 락(`@Version`) 실채택, 조건부 UPDATE는 비교 베이스라인. `ReservationLockStrategy` 인터페이스로 추상화. Redis 분산 락 미사용 (SA §9-3)
- 검색 캐시: Redis 원격 캐시. Caffeine 미사용 (SA §9-2)
- 실시간 알림·채팅: 예약·결제 알림은 MVP2 단방향 SSE를 유지한다(#40). `NotificationPusher` 추상화 + `SseNotificationPusher` 구현, 티켓 기반 인증·커밋 후 전송 계약은 변경하지 않는다. 병원↔회원의 예약당 채팅은 native WebSocket + STOMP를 사용하며, 전용 `/ws/chat` HTTP Upgrade 경로만 CONNECT까지 도달하도록 허용하고 실제 인증은 STOMP CONNECT `Authorization` 헤더의 JWT 검증으로 강제한다. 채팅은 단일 인스턴스 Spring SimpleBroker를 기준으로 하고 다중 인스턴스 fan-out은 후속 범위이며, 메시지는 저장 커밋 후에만 전달한다. **채팅 상세 정책의 현재 정본은 고도화 델타 `docs/enhancement/채팅.md`**이며, 동결 SA §9-8은 알림 SSE 계약만 따른다. SA 채팅 절 승격은 실제 채팅 구현 PR에서 수행한다.
- 환불: MVP는 제외였으나 **MVP+ 고도화에서 오청구 전액 환불을 도입**(이슈 #37, SA §5-2·§9-4). 대상은 빌링키 자동결제 완료건(`PAID`·`BILLING_KEY`)뿐이고 사유만 입력받아 결제 금액 그대로 취소한다. 선점은 `payment_refunds.UNIQUE(payment_id)`가 담당하며 결제 상태에 환불 중간 단계를 두지 않는다(역방향 전이 방지). 현장 수납분(`OFFLINE_PAID`) 환불은 여전히 확장이다. 예약 상태에 `PAYMENT_COMPLETED` 없음 — 결제완료는 예약+결제 상태 조합으로 표현 (SA §5-4)
- 정정 재청구: **확정 정책이며, 기존의 "환불한 예약은 재청구할 수 없다" 결정을 대체한다**(SA §9-4). 금액 정정은 기존 `PAID` 결제를 전액 환불한 뒤 **새 `payments` 행을 만들고 `correction_of`로 이전 결제에 연결**하는 방식만 쓴다. 예약당 활성 결제는 1건만 허용하고, 환불·대체된 과거 결제는 지우지 않고 이력으로 남긴다. `payments.UNIQUE(reservation_id)`가 이 흐름을 막으므로 **"활성 결제 UNIQUE"로 대체**하며(SA §4 payments), 그 스키마·코드 변경이 끝나기 전까지는 코드상 재청구가 여전히 불가하다 — 구현 PR이 제약 교체와 **이 문장·SA §9-4 알려진 한계·Context Router 표기의 갱신까지** 함께 수행한다(완료 조건은 `docs/enhancement/결제.md` §6. 구현만 하고 문서를 남기면 다음 작업자가 미구현으로 오독한다). **부분 환불은 재청구와 무관한 별개 계약이며 계속 미확정·제외**다(`docs/enhancement/결제.md` 3.5-b)
- 청구 항목화: 청구 항목(`payment_items`)은 **병원 스태프가 진료 완료 후·청구 선기록 전까지만** 작성·수정하고, 선기록(Payment `PENDING` 생성) 이후에는 절대 수정하지 않는다. 항목은 청구 시점 스냅샷이며, 기존 결제의 금액 정정은 항목 수정이 아니라 정정 재청구 절차만 쓴다 — 이 금지는 데이터 정합성뿐 아니라 **정정 재청구를 우회 구현하지 못하게 하는 경계**다(SA §9-4). 그래서 금지는 문서가 아니라 **직렬화로 강제한다** — 항목 수정과 청구 선기록은 같은 예약 행을 `PESSIMISTIC_WRITE`로 잠그고(청구가 이미 쓰는 락), 항목 수정은 `WHERE payment_id IS NULL` 조건부 UPDATE로만 반영한다(STRICT, SA §4·§9-4). **총액은 서버가 항목 합계로 산출**하며 클라이언트 총액을 신뢰하지 않는다. 할인·조정은 별도 할인 필드가 아니라 **음수 금액 항목**으로 기록하므로 `quantity`는 양수(요청 DTO `@Positive`), `unit_price`·`amount`는 음수를 허용하는 signed 정수이며 **UNSIGNED로 만들지 않는다**. 최종 합계는 0보다 커야 한다. 항목화 이전 결제는 항목이 없고 **백필하지 않는다**. 세율·부가세 분리와 진료 항목 마스터 표준화는 제외
- 영수증: `PAID`·`OFFLINE_PAID`·`REFUNDED` 결제에 **구조화 JSON만** 제공한다(PDF·전자문서·세금계산서·현금영수증 국세청 연동·진료확인서 제외). 보호자는 본인 예약 결제만, 병원 스태프는 자기 병원 결제만 조회한다. 환불 사유는 감사용이라 보호자 알림·응답에 노출하지 않는다(SA §4 `payment_refunds.reason` 기존 결정 승계)
- 결제 실패 셀프 복구: `Payment.status == OFFLINE_REQUIRED`에서만 허용하고, 보호자가 ACTIVE 결제수단을 새로 지정한 뒤 **명시적으로 재청구를 요청**해야 한다. `PENDING`에서는 어떤 셀프 재청구도 허용하지 않는다 — 승인 여부가 불확정이라 이중 결제 위험이 있어 정산 스케줄러의 단건 조회 결과를 기다린다(SA §9-7). **셀프 재청구와 오프라인 정산은 승자 규칙을 DB로 못박는다** — 둘 다 `superseded_at IS NULL`(활성)을 전제로 한 조건부 UPDATE로만 성립해 먼저 커밋한 쪽이 이기고 늦은 쪽은 409다(STRICT: 자동결제·현장 수납 이중 수납 방지, SA §5-2·§9-4)
- 활성 결제: 활성은 상태가 아니라 **`superseded_at IS NULL`**로 판정한다. 대체는 `OFFLINE_REQUIRED`(셀프 복구)·`REFUNDED`(정정 재청구)에서만 허용하고 `PAID`·`OFFLINE_PAID`는 대체하지 않는다 — 이미 수납된 결제를 대체하면 받은 돈의 근거가 사라진다 (SA §5-2)
- 결제수단 기본값·예약 결제수단 재지정: **구현 완료**(PR #152, SA §4 payment_methods·§8-7). 회원별 활성 기본 결제수단은 최대 1건이고, 예약 결제수단 재지정은 결제 선기록 전·진료 시작 전에만 허용한다
- 응답 포맷: `ApiResponse{ code, message, data }`, 성공 `code="SUCCESS"`, 실패는 예외 → GlobalExceptionHandler
- ErrorCode: `{DOMAIN}_{3자리}`, 도메인별 enum 분리 (글로벌 통합 enum 금지)

## 미확정 — 구현하지 말고 질문 (SA 부록 A)

미인증 계정 장기 미완료 처리, SNS 로그인 도입 범위, 이메일 인증·비밀번호 재설정 토큰 소비 순서, 탈퇴 트랜잭션과 Redis 부수효과 순서.

## 검증

- 코드를 바꾸면 관련 테스트를 실행한다.
- 실행하지 않은 검증을 완료라고 말하지 않는다. 판정은 PASS/FAIL/PARTIAL/BLOCKED 4값만 쓰고, 완료 근거는 PASS만 인정한다.
- Mock 테스트 PASS와 실제 DB·외부 API 검증을 구분한다.
- 검증 Level·기록 양식은 `docs/testing/verification-guide.md`, 리뷰 기준은 `docs/ai/review-gate.md`를 따른다.
- PR을 열기 전 `docs/ai/completion-checklist.md`를 확인한다.

## STRICT 신호 — 아래를 바꾸면 동시성·통합 테스트까지 필수

- 예약 슬롯 점유·낙관적 락(`@Version`) → 다중 스레드 테스트로 "1건만 성립" 검증 (SA §9-3)
- 결제 멱등(`merchant_payment_id`·`reservation_id` UNIQUE)·재시도 분기·오프라인 정산 (SA §9-4)
- 예약·결제 상태 머신 전이 (SA §5)
- 스케줄러 — 노쇼 판정·승인 타임아웃·결제 정산 (SA §9-7)
- DB 스키마 — 테이블·컬럼·제약·인덱스 등 **마이그레이션이 필요한 모든 DDL** — 와 Soft Delete, 스냅샷 필드 (SA §4). 단, 락·트랜잭션과 무관한 DDL(예: 조회용 인덱스)은 동시성 테스트 대신 Level 3 통합 검증으로 충분하다

## 브랜치·커밋·PR

`docs/collaboration/github-rules.md`(팀 합의 정본)를 따른다. 요약:

- `feature/도메인명` → `develop`으로 병합하고, 배포 시점에만 `develop` → `main`. 같은 도메인 병렬 작업 시에는 전원이 `feature/도메인명-작업키`를 쓴다.
- 커밋은 한국어 컨벤션: `feat:`/`fix:`/`docs:`/`style:`/`refactor:`/`test:`/`chore:`/`remove:`/`build:`/`rename:` + 50자 이내 명령형, 마침표 없음.
- PR 제목은 `[타입] 작업 내용 요약`, 본문은 팀 템플릿(AI 사용 내역 표 포함)을 채운다. 관련 이슈는 `Closes #이슈번호` 형식으로 연결한다(작업 보드 자동화 트리거).
- PR은 2인 이상 승인 후 팀원이 merge한다. **AI는 merge하지 않는다.**
- PR 본문에는 실행한 검증 명령·결과와 미검증 항목을 사실대로 적는다.

## AI 코드 리뷰 출력 규칙 (팀 합의, PR #52)

- 모든 코드 리뷰 요약과 인라인 리뷰 코멘트를 한국어로 작성한다.
- 문제의 원인, 영향, 수정 방법을 한국어로 명확하게 설명한다.
- 클래스명, 메서드명, 변수명, 코드, 명령어와 오류 메시지는 원문을 유지한다.
- 심각도 표시는 P0, P1, P2 형식을 유지한다.

## 보안

- PortOne 인증정보·JWT 시크릿을 코드·문서·로그에 남기지 않는다.
- 빌링키·카드번호 원본을 저장·출력하지 않는다(암호화 저장, 표시는 brand·last4만).
- 요청 body/query/path의 `memberId`·`hospitalId`를 신뢰하지 않는다 — `@AuthenticationPrincipal`로만 식별.
