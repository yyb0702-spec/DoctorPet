# PR #77 진료비 청구 3차 검증 — 후속 처리 항목

> 작성일: 2026년 8월 3일
> 대상 브랜치: `feature/payment-billing`
> 기준 커밋: `ebfd9ea` (3차 독립 검증 반영 후)
> 검증 방식: 문서 정합성·보안·동시성/트랜잭션 다각도 감사 + 실 MySQL/Redis 전체 테스트(454 PASS)

리뷰와 별개로 수행한 3차 독립 검증에서 **P0 없음**을 확인했다. P1 1건과 즉시 처리 가능한 P2는 반영했고(아래 1절), 이 PR 범위에서 안전하게 처리할 수 없어 **후속으로 미룬 항목**을 여기 남긴다(2절). PR 머지 후에도 추적되도록 문서로 고정한다.

## 1. 이 PR에서 반영 완료 (커밋 `ebfd9ea`)

- **[P1] 외부 승인의 계약 외 예외 흡수** — `attemptBillingKeyCharge`가 `PaymentGatewayException`만 흡수해, 빌링키 복호화 실패나 게이트웨이 어댑터의 비-계약 `RuntimeException`이 `charge()` 밖으로 새면 HTTP 500 + Tx1의 PENDING 고아가 남던 구멍을 교정. 복호화 실패(청구 미발생 확실)는 `OFFLINE_REQUIRED`(`BILLING_KEY_DECRYPT_FAILED`), 그 외 오케스트레이션 예외(승인 도달 불명)는 `PENDING`(`CHARGE_ORCHESTRATION_ERROR`)으로 흡수. 두 경로 회귀 테스트 추가.
- **[P2] 문서 drift** — SA §8-7·§9-4 금액 검증 문구를 실제 동작(0·음수 → `VALIDATION_FAILED`, 상한 초과 → `INVALID_AMOUNT`)에 맞게 정정.
- **[P2] 주석 보강** — `DUPLICATE_CHARGE` catch에 도달 가능한 무결성 위반이 `reservation_id` UNIQUE 경쟁뿐이라는 근거 명시.

## 2. 후속 처리 항목 (이 PR 미보장 — 담당·시점 명시)

### 2-1. [P1 관련] PENDING 고아 회수 — 정산 스케줄러 #35
- **내용**: Tx1(PENDING 선기록) 커밋 후 앱 크래시 등으로 Tx2가 실행되지 않으면 PENDING 레코드가 남고, `existsByReservationId`가 재청구를 막아 해당 예약이 청구 불가로 고착될 수 있다.
- **현재 완화**: 3차 P1 반영으로 복호화 실패발 고아와 500 누수는 제거됐다. 그러나 프로세스 크래시로 인한 고아는 코드로 막을 수 없다.
- **필요 조치**: 정산 스케줄러(#35)가 일정 시간 이상 `PENDING`인 건을 단건 조회로 `PAID`/`OFFLINE_REQUIRED` 확정. **게이트웨이에 거래 기록이 아예 없는(승인 시도 전 크래시) PENDING**도 일정 시간 후 `OFFLINE_REQUIRED`로 에스컬레이션하는 규칙이 필요하다(단순 재조회만으로는 영구 PENDING).
- **담당/시점**: 이슈 #35.

### 2-2. [P2] 동시 finalize 방어 — 낙관적 락 / 조건부 UPDATE
- **내용**: `finalizeOutcome`의 상태 전이 가드는 in-memory `ensurePending()`뿐이다. 현재는 청구당 finalize 1회 전제라 안전하나, #35가 같은 PENDING을 finalize하는 두 번째 경로를 도입하면 두 트랜잭션이 각자 PENDING을 읽어 last-write-wins가 될 수 있다. 또한 이미 확정된 건에 finalize가 재호출되면 `ensurePending`이 `IllegalStateException`을 던져 500이 된다(도메인 예외 미매핑).
- **필요 조치**: `@Version`(낙관적 락) 또는 `WHERE status='PENDING'` 조건부 UPDATE로 전이를 DB에서 강제. `ensurePending`은 도메인 `ServiceException`으로 던져 500을 회피. `@Version`은 스키마(DDL) 변경이고 `BaseEntity` 전체에 영향을 주므로 STRICT 검증(동시성 테스트) 대상.
- **담당/시점**: 이슈 #35 착수 시 **필수**.

### 2-3. [P2] 무결성 위반 제약별 분기 (DUPLICATE_CHARGE 정밀화)
- **내용**: `saveAndFlush`의 `DataIntegrityViolationException`을 무조건 `DUPLICATE_CHARGE`로 변환한다. 지금은 도달 가능한 위반이 `reservation_id` UNIQUE 경쟁뿐이라 안전하나, 향후 다른 제약/NOT NULL을 추가하면 오분류된다.
- **왜 지금 안 하나**: catch 내부 재조회는 트랜잭션이 rollback-only라 위험하고, 예외 메시지의 제약명 파싱은 DB/드라이버 의존이라 fragile(정상 경쟁을 500으로 만들 회귀 위험).
- **필요 조치**: payments에 새 제약을 추가하는 시점에, 안전한 판별 방식(제약명 상수 매칭 등)을 함께 도입.
- **담당/시점**: 새 제약 추가 PR.

### 2-4. [P2] 청구 금액 감사·이상 탐지
- **내용**: 청구 금액은 스태프 입력 최종 진료비를 그대로 쓰며 예약별 기대액과 대조하지 않는다. 유일한 방어는 전역 상한(300만원). 정상/탈취 스태프 계정의 과다청구를 코드가 막지 못한다(단, `UNIQUE(reservation_id)`로 예약당 1회).
- **필요 조치(선택)**: 청구 감사 로그(누가·얼마·어느 예약), 이상 금액 알림, 또는 진료 항목 기반 상한. 후불 최종액 입력은 PRD/SA 설계 의도라 MVP 수용 가능 범위.
- **담당/시점**: 별도 백로그 이슈(설계 결정 필요).

### 2-5. [P2] 청구 엔드포인트 rate limit / 재시도 백오프 스레드 점유
- **내용**: `POST charge`에 요청 속도 제한이 없다. 재시도 유효 실패 시 백오프(500+1000+2000ms)가 HTTP 요청 스레드를 최대 ~3.5초 동기 점유한다(게이트웨이 타임아웃 별도). 현장 트리거라 동시성이 낮아 MVP 수용 가능하나, 부하 시 스레드풀 고갈 여지.
- **필요 조치(선택)**: 병원·스태프 단위 rate limit, 또는 청구 전체 데드라인 캡.
- **담당/시점**: 부하 대비 별도 백로그 이슈.

### 2-6. [참고] SA §7 에러코드 카탈로그 불일치
- **내용**: SA §7의 에러코드 요약표(`PaymentErrorCode` 행)가 실제 구현(`INVALID_AMOUNT`, `RESERVATION_NOT_CHARGEABLE`, `FORBIDDEN_HOSPITAL`, `DUPLICATE_CHARGE`, `PAYMENT_NOT_FOUND`)과 다르다(문서에 `ALREADY_PAID`, `DUPLICATE_PAYMENT`, `OFFLINE_PRECONDITION_FAILED` 등 기재).
- **왜 지금 안 하나**: 해당 표는 #36(오프라인 정산)·환불 등 여러 PR 코드가 섞인 미래지향 목록이라 #34 단독으로 재작성하면 다른 도메인과 충돌할 수 있다.
- **담당/시점**: 결제 도메인 전체가 병합된 뒤 카탈로그 일괄 정리.
