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

## 확정된 핵심 결정 (재논의 금지, 근거는 SA)

- 동시성: 낙관적 락(`@Version`) 실채택, 조건부 UPDATE는 비교 베이스라인. `ReservationLockStrategy` 인터페이스로 추상화. Redis 분산 락 미사용 (SA §9-3)
- 검색 캐시: Redis 원격 캐시. Caffeine 미사용 (SA §9-2)
- 실시간 알림: MVP는 폴링. push는 `NotificationPusher` 인터페이스만 추상화 (SA §9-8)
- 환불: MVP 제외 (SA §4 payments·§9-4). 예약 상태에 `PAYMENT_COMPLETED` 없음 — 결제완료는 예약+결제 상태 조합으로 표현 (SA §5-4)
- 응답 포맷: `ApiResponse{ code, message, data }`, 성공 `code="SUCCESS"`, 실패는 예외 → GlobalExceptionHandler
- ErrorCode: `{DOMAIN}_{3자리}`, 도메인별 enum 분리 (글로벌 통합 enum 금지)

## 미확정 — 구현하지 말고 질문 (SA 부록 A)

LLM 모델/제공자·프롬프트 외부화 방식, 진료역량 화이트리스트 구체 값, AI 입력 길이·Rate Limit 수치, 실시간 push 방식(SSE/WebSocket).

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
