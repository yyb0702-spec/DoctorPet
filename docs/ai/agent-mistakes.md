# 반복 실수 기록

AI(또는 사람)가 잘못 제안한 내용, 잘못된 이유, 수정 방식을 기록한다. 다음 작업의 계획 단계에서 이 표를 확인해 같은 실수를 반복하지 않는다.

| 날짜 | 실수 | 수정 |
| --- | --- | --- |
| 2026-08-03 | 정산 단계에 새 불확실 결제 사유 `RECONCILE_AMOUNT_MISMATCH`(PG가 PAID지만 금액·pgId 불일치)를 도입하면서 `PaymentRepository.findReconcileTargets`의 제외 목록에는 넣지 않아, 이미 청구됐을 수 있는 결제가 다음 배치에서 재조회→OFFLINE 전환되어 이중결제 위험이 남았다(PR #81 후속 리뷰). | 불확실 결제 사유를 새로 만들면 `findReconcileTargets`의 `failureReason not in (...)` 제외 목록에 반드시 함께 추가한다. `reconcileTargets_excludeUncertainPaidReasons` 통합 테스트에 새 사유 케이스를 넣어 재선택되지 않음을 검증한다. |
| 2026-08-03 | 정산 소진 정책을 `임계 초과 시 OFFLINE_REQUIRED 전환`에서 `RECONCILE_STUCK로 PENDING 유지`로 바꾸면서 구현만 고치고 `PaymentReconcileService` 클래스 주석·`application.yaml`의 `max-attempts` 주석·PR 본문 설명은 옛 정책 그대로 두어 문서-구현이 어긋났다(PR #81 후속 리뷰). | 상태 전이·안전 정책(특히 자동 현장수납 전환 여부)을 바꾸면 코드와 함께 관련 주석(클래스 Javadoc·설정 파일 주석)과 PR 본문 설명을 같은 커밋에서 갱신한다. |

## 기록 조건

다음을 모두 만족할 때만 기록한다.

- 같은 실수가 다음 작업에서도 반복될 가능성이 있다.
- 원인과 수정 방식이 확인되었다 (재현 가능한 실제 실패 — Issue/PR 번호나 확인 명령 포함).
- 나중에 읽는 사람이 바로 피할 수 있는 구체적 내용이다.

단순한 추측, 한 번뿐인 일시적 환경 오류, 이미 문서에 충분히 적힌 일반 규칙은 기록하지 않는다.

