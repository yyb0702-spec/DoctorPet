# 완료 체크리스트 — PR 열기 전 확인

AI(Claude Code·Codex 등)가 구현이나 PR 작성에 관여한 PR은 **그 AI가 PR 준비 단계에서 이 체크리스트를 수행**한다 — 이 프로젝트에서는 사실상 대부분의 PR이 해당한다. AI 관여 없이 완전 수작업으로 만든 PR에만 권장 사항이며, 그 경우 사람용 최소 확인은 PR 템플릿의 체크리스트(6개)로 충분하다.

## 공통

- [ ] 대상 Issue 본문과 Context Router가 지정한 문서를 읽었다.
- [ ] Issue 범위 밖 구현이나 리팩토링을 하지 않았다.
- [ ] Controller / Service / Repository 책임이 섞이지 않았다 (코드컨벤션).
- [ ] Entity 상태 변경이 상태 머신(SA §5)의 허용 전이 안에 있다.
- [ ] 필요한 테스트 또는 검증 명령을 실제로 실행했다.
- [ ] 검증 결과를 [검증 가이드](../testing/verification-guide.md) 표 양식으로 기록했다 (Level·명령·결과·미검증 항목).
- [ ] Level 5(로컬 기동)·Level 6(실제 HTTP) 필요 여부를 YES/NO로 결정하고 이유를 남겼다.
- [ ] 실행하지 않은 검증을 완료로 적지 않았다 — 미검증 항목을 명시했다.
- [ ] API·정책·스키마가 바뀌었으면 관련 문서(SA 등)를 함께 갱신했다.
- [ ] 커밋 메시지가 팀 컨벤션(`docs/collaboration/github-rules.md`)을 따른다.
- [ ] PR 본문에 팀 템플릿의 AI 사용 내역 표를 작성했다.
- [ ] 관련 이슈를 `Closes #이슈번호`로 연결했다 (없으면 생략 사유를 비고에 적었다).
- [ ] 새로 발견한 반복 실수가 있으면 [반복 실수 기록](agent-mistakes.md)에 남겼다.
- [ ] 하네스·경량 문서(AGENTS·docs/ai·docs/testing·docs/lightweight)를 바꿨으면 `python scripts/harness_check.py`를 실행해 PASS를 확인했다. (로컬에 Python이 없으면 PR의 Harness Check CI 결과로 대신한다)

## STRICT 변경 추가 확인 (AGENTS.md STRICT 신호 해당 시)

- [ ] 동시성 변경: 다중 스레드 테스트로 "1건만 성립"을 검증했다.
- [ ] 결제 변경: 멱등키 중복 차단·재시도 중단을 테스트했다.
- [ ] 스키마·Soft Delete 변경: 이력 보존(스냅샷·익명화) 시나리오를 확인했다.
- [ ] 락·트랜잭션과 무관한 DDL만 바꾼 경우(조회용 인덱스 등): 동시성 테스트 대신 Level 3 통합 검증을 실행했다.
- [ ] 스케줄러 변경: 자동 판정과 수동 우선 규칙이 유지된다.

## 미확정 정책 확인

- [ ] SA 부록 A의 `[결정 필요]` 항목을 임의로 구현하지 않았다 — 필요하면 질문 Issue로 분리했다.
