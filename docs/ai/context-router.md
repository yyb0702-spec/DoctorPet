# Context Router — 작업별 읽을 문서 지도

작업 시작 시 `docs/`를 재귀적으로 읽지 않는다. 아래 hot path에서 직접 관련된 정본 3~5개만 선택한다.
읽는 기준은 "변경되는 파일"이 아니라 **"변경되는 계약(정책·상태·스키마)"**이다. 규칙 충돌 시 정본 판별은 [규칙 정본 지도](rule-source-map.md)를 따른다.

## 공통 진입

- 시작: [`AGENTS.md`](../../AGENTS.md)에서 작업 규칙을 확인하고 여기서 hot path를 고른다.
- 제외: 이 목록에 없다는 이유만으로 `docs/` 하위 문서를 전부 읽지 않는다.
- 추가 탐색: 문서 간 규칙이 충돌하거나 hot path에 없는 용어·정책이 나올 때만 [규칙 정본 지도](rule-source-map.md)에서 정본을 찾아 한 단계 확장한다.
- STRICT 판단: STRICT 여부의 최종 판단은 hot path 라벨이 아니라 [`AGENTS.md`](../../AGENTS.md)의 **STRICT 신호 목록**으로 한다. 아래 라벨은 힌트일 뿐이다.

## 개발 hot path

### 회원·인증·탈퇴

- 필수: [SA §6(인증·인가)](../architecture/DoctorPet-SA.md), SA §4 members, [PRD §3(사용자·요구사항)](../product/DoctorPet-PRD.md)
- 조건부: Refresh Token 저장·회전이 바뀌면 SA §1(Redis 역할)·§6-1(토큰 저장·회전)을 추가한다.
- 제외: 예약·결제·AI 문서는 인증 계약을 바꾸지 않으면 읽지 않는다.
- 주의: 탈퇴(Soft Delete·이메일 익명화)나 members 스키마를 바꾸면 STRICT다(AGENTS의 스키마·Soft Delete 신호).

### 반려동물 프로필

- 필수: SA §4 pet_profiles(Soft Delete·스냅샷), SA §8-2
- 조건부: 예약 상세 표시가 바뀌면 SA §4 reservations(스냅샷 필드)를 추가한다.
- 제외: 결제·병원 문서는 읽지 않는다.
- 주의: Soft Delete·스냅샷 필드를 바꾸면 STRICT다.

### 병원 검색·캐싱

- 필수: SA §9-1(QueryDSL), SA §9-2(Redis 캐시), SA §8-3, SA §4 hospitals·hospital_details·hospital_capabilities
- 조건부: 성능 목표가 쟁점이면 PRD §9(성과 지표)를 추가한다.
- 제외: 예약·결제 상태 머신은 검색 계약과 무관하면 읽지 않는다.
- 추가 탐색: 제휴/비제휴 노출 정책이 불명확하면 PRD §6-1을 추가한다.

### AI 상담 (STRICT 아님, 단 안전 정책 필수)

- 필수: [PRD §7(AI 안전 정책)](../product/DoctorPet-PRD.md), SA §9-5(AiGateway·Tool Calling), SA §8-4, SA §4 ai_consultations
- 조건부: 검색 Tool 인자가 바뀌면 SA §9-1을 추가한다.
- 제외: 결제·예약 상태 머신은 읽지 않는다.
- 추가 탐색: 미확정 항목(모델·프롬프트 외부화·Rate Limit)은 구현하지 않고 질문으로 남긴다(SA 부록 A).

### 예약 요청·승인·취소 (STRICT: 상태 머신 전이)

- 필수: PRD §6-3~6-5, SA §5-1(상태 머신), SA §8-5·§8-6
- 조건부: 슬롯 점유가 바뀌면 아래 "슬롯·동시성"으로 전환한다. 취소·거절의 슬롯 반환(RESERVED→OPEN)을 바꾸면 SA §5-3을 추가한다. 예약자 이력 표시가 바뀌면 PRD §6-6을 추가한다.
- 제외: 결제 상세(§9-4)는 청구 계약을 바꾸지 않으면 읽지 않는다.

### 슬롯·동시성 (STRICT)

- 필수: SA §9-3(낙관적 락·ReservationLockStrategy), SA §5-3(슬롯 상태), SA §4 reservation_slots, [코드컨벤션 테스트 규칙](../architecture/DoctorPet-코드컨벤션.md)
- 조건부: 슬롯 생성 배치가 바뀌면 SA §9-9를 추가한다.
- 제외: AI·알림 문서는 읽지 않는다.
- 추가 탐색: 확정 결정(낙관적 락)을 재논의하지 않는다 — AGENTS.md 확정 목록 참조.

### 노쇼·스케줄러 (STRICT)

- 필수: PRD §5-5·§6-6, SA §9-7(스케줄러 표), SA §5-1, SA §4 reservation_events
- 조건부: noShowCount 집계가 바뀌면 SA §5-1 노쇼 집계 절을 추가한다.
- 제외: 결제 정산 스케줄러는 결제 hot path에서 다룬다.

### 결제·오프라인 정산 (STRICT)

- 필수: PRD §6-7, SA §5-2(결제 상태), SA §9-4(멱등·실패 분기), SA §8-7, SA §4 payments·payment_methods
- 조건부: 결제수단 등록·삭제가 바뀌면 SA §4 payment_methods 삭제 정책 절을 추가한다.
- 제외: AI·검색 문서는 읽지 않는다.
- 추가 탐색: 환불이 요구되면 구현하지 않는다 — MVP 제외(PRD §6-7), 질문으로 분리.

### 알림

- 필수: SA §9-8(폴링 확정·NotificationPusher 추상화), SA §8-8, SA §4 notifications
- 제외: push 방식(SSE/WebSocket)은 미확정 — 단정하지 않는다(SA 부록 A).

### 공공데이터·슬롯 생성

- 필수: PRD §6-1, SA §9-6(배치·2계층 매핑), SA §9-9(슬롯 14일치)
- 조건부: 검색 노출이 바뀌면 SA §9-1을 추가한다.

## 검토·검증 hot path

### 코드 리뷰

- 필수: [Review Gate](review-gate.md), [구현 가드레일](implementation-guardrails.md), [검증 가이드](../testing/verification-guide.md), [반복 실수](agent-mistakes.md)
- 조건부: 코드 스타일이 지적의 직접 근거이면 [코드컨벤션](../architecture/DoctorPet-코드컨벤션.md)을, AI 산출물 품질이 쟁점이면 [AI 슬롭 제거](remove-ai-slop.md)를 추가한다.
- 제외: 변경과 무관한 도메인 문서 전체는 읽지 않는다.

### 테스트 작성·검증

- 필수: [검증 가이드](../testing/verification-guide.md), SA 부록 B(필수 테스트 14개), 코드컨벤션 테스트 규칙
- 조건부: 변경 영역의 개발 hot path를 추가한다.

### 하네스 변경

- 필수: [`AGENTS.md`](../../AGENTS.md), [규칙 정본 지도](rule-source-map.md), 이 문서
- 조건부: 변경하는 규칙의 정본 문서만 추가한다.
- 제외: 애플리케이션 코드·도메인 문서는 하네스 계약이 직접 참조하지 않으면 읽지 않는다.

## 조건부 참조 문서

- [팀 깃허브 규칙](../collaboration/github-rules.md) — 브랜치 생성·커밋·PR 작성 시점에만.
- [정책 정리본](../domain/반려동물병원예약-정책정리본.md) — PRD·SA에 없는 정책 배경이 필요할 때만. PRD·SA와 다르면 PRD·SA 우선.
- [완료 체크리스트](completion-checklist.md) — PR 열기 직전에만.

## 유지 규칙

- 표에 없는 새 작업 유형이면 사용자에게 기준 문서를 묻고 이 문서에 hot path를 추가한다.
- 여기 선언된 상대 링크 경로가 실제 파일과 어긋나면 발견 즉시 고친다. `python scripts/harness_check.py`가 링크·경로·섹션 참조를 검사한다(하네스 변경 PR에서 CI 자동 실행).
