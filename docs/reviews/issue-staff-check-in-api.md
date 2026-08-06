# 직원용 도착 확인 API

## 작업 내용

병원 직원이 예약 환자의 실제 도착을 기록할 수 있는 직원용 도착 확인 API를 구현한다.
도착 확인 정보를 자동 노쇼 스케줄러와 연동하여, 내원했거나 진료·결제가 완료된 예약이 자동으로 `NO_SHOW` 처리되지 않도록 한다.

## 작업 정보

- 우선순위: P1
- 담당 영역: reservation

## 포함 범위

- 직원용 도착 확인 API 구현
  - `PATCH /api/hospital/reservations/{reservationId}/check-in`
  - 요청 body 없이 인증된 직원 정보를 `@AuthenticationPrincipal`로 식별
  - 예약 병원과 직원의 소속 관계 검증
- 예약 상태 전이 구현
  - `CONFIRMED → CHECKED_IN`
  - 프로젝트에 기존 `CHECKED_IN` 상태·필드·이력이 있으면 재사용
  - 없는 경우 기존 상태 머신과 일관되게 추가
- 도착 시각과 처리 주체 저장
- 중복 도착 확인 요청의 멱등 처리
- 최종 상태 예약에 대한 부적절한 도착 확인 차단
- 자동 노쇼 스케줄러 조건 보완
  - 도착 확인 기록이 없는 예약만 대상
  - 진료 시작·진료 완료 기록이 있는 예약 제외
  - 결제 완료 예약 제외
  - 처리 직전 예약 상태가 여전히 `CONFIRMED`인지 재확인
- 직원 도착 확인 API와 자동 노쇼 스케줄러의 동시성 제어
- `NO_SHOW_PENDING` 상태를 추가하여 최종 노쇼 확정 전 추가 유예기간 제공
  - 예약 시작 후 10분 초과 시 `NO_SHOW_PENDING`
  - 추가 유예기간 기본값 5분, +15분 초과 시 최종 `NO_SHOW`
- 상태 변경·이력 저장·알림 발송 순서 정비
  - 최종 `NO_SHOW` 확정 전 환자에게 노쇼 확정 알림을 발송하지 않음
- 필요한 엔티티·마이그레이션·인덱스 변경
- 단위 테스트·통합 테스트·다중 스레드 동시성 테스트 작성
- 검증 결과를 `docs/testing/verification-guide.md` 양식으로 기록
- 변경된 상태 머신과 정책을 `docs/architecture/DoctorPet-SA.md`에 반영

## 제외 범위

- 환자용 셀프 체크인, QR 체크인, 키오스크 구현
- 진료 시작·진료 완료 API 전체 구현
- 결제 환불 정책 및 환불 API
- SSE/WebSocket 기반 실시간 알림
- 병원 직원용 프론트엔드 화면 구현
- 이미 확정된 `NO_SHOW`를 자동으로 `CHECKED_IN`으로 되돌리는 기능
- 운영자용 노쇼 정정·복구 화면

## 실제 실행 검증 결정

<!-- API 동작·런타임 설정·인프라 연결이 바뀌면 기본값 YES. 문서·운영만 바뀌면 NO 가능. -->

Level 5(로컬 기동) required: YES

Level 5 reason: 예약 상태 전이, 인증·권한 검증, 트랜잭션, 스케줄러 런타임 설정이 변경되므로 애플리케이션을 실제로 기동하여 빈·설정·마이그레이션·스케줄러 초기화 성공 여부를 확인해야 한다.

Level 6(실제 HTTP) required: YES

Level 6 reason: 직원용 HTTP API의 인증·권한·병원 소속 검증·응답 포맷·멱등 동작을 실제 요청으로 확인해야 하며, 도착 확인 직후 자동 노쇼 대상에서 제외되는 연동까지 검증해야 한다.

## API 계약

### 요청

```http
PATCH /api/hospital/reservations/{reservationId}/check-in
```

- 요청 body 없음
- `reservationId`는 대상 예약 조회에만 사용
- `hospitalId`, `memberId`, 처리 직원 식별자는 요청 body·query로 받지 않음
- 처리 직원과 병원은 `@AuthenticationPrincipal` 기반으로 식별

### 성공 응답

```json
{
  "code": "SUCCESS",
  "message": "예약 도착이 확인되었습니다.",
  "data": {
    "reservationId": 123,
    "status": "CHECKED_IN",
    "checkedInAt": "2026-08-06T10:09:50+09:00"
  }
}
```

프로젝트 공통 응답 포맷 `ApiResponse{ code, message, data }`를 사용한다. 실패는 도메인별 ErrorCode와 `GlobalExceptionHandler`를 따른다.

## 상태·동시성 정책

도착 확인 API와 자동 노쇼가 동시에 실행될 수 있으므로 둘 다 처리 직전에 상태 조건을 확인한다.

```text
도착 확인: CONFIRMED/NO_SHOW_PENDING → CHECKED_IN
자동 노쇼: CONFIRMED → NO_SHOW_PENDING → NO_SHOW
```

먼저 조건을 만족한 하나의 전이만 성공해야 한다. 이미 도착 확인·진료·결제가 기록된 예약은 자동 노쇼 처리 결과가 `SKIPPED`가 되어야 한다.

중복 도착 확인 요청은 최초 도착 시각과 처리 주체를 덮어쓰지 않고 성공 응답을 반환한다. `CANCELLED`, `NO_SHOW`, `COMPLETED` 등 최종 상태는 이 API로 변경하지 않는다.

`NO_SHOW_PENDING`을 도입하는 경우 상태 흐름은 다음과 같다.

```text
CONFIRMED → NO_SHOW_PENDING → NO_SHOW
                 ↓
             CHECKED_IN
```

`NO_SHOW_PENDING` 단계에서는 최종 노쇼 알림을 보내지 않고, 추가 유예기간 동안 도착·진료·결제 상태를 재확인한다.

## 완료 조건

- [x] 기능이 요구사항대로 동작한다
- [ ] 필요한 테스트 작성 및 통과 (docs/testing/verification-guide.md 양식으로 기록)
- [ ] 관련 문서 갱신
- [ ] 범위 밖 변경이 없다
- [ ] Pull Request 생성 및 리뷰 완료

추가 판정 기준:

- [ ] 직원이 소속 병원의 예약에 대해서만 도착 확인할 수 있다
- [ ] 중복 요청에도 도착 이력과 상태 이력이 중복 생성되지 않는다
- [ ] 도착 확인된 예약이 자동 노쇼로 변경되지 않는다
- [ ] 진료·결제 완료 예약이 자동 노쇼로 변경되지 않는다
- [ ] API와 스케줄러의 동시 실행에서 `CHECKED_IN` 또는 `NO_SHOW` 중 유효한 전이 하나만 성립한다
- [ ] 최종 노쇼 확정 전 환자에게 노쇼 확정 알림이 발송되지 않는다
- [ ] Level 5 로컬 기동 검증 결과를 기록한다
- [ ] Level 6 실제 HTTP 검증 결과를 기록한다
- [ ] 예약 상태 머신 변경 시 SA를 갱신한다

## 참고 문서

<!-- docs/ai/context-router.md에서 해당 hot path의 필수 문서 -->

- `docs/product/DoctorPet-PRD.md`
- `docs/architecture/DoctorPet-SA.md`
- `docs/architecture/DoctorPet-코드컨벤션.md`
- `docs/domain/반려동물병원예약-정책정리본.md`
- `docs/ai/implementation-guardrails.md`
- `docs/testing/verification-guide.md`
- `docs/ai/review-gate.md`
- `docs/reviews/issue-29-auto-no-show-guide.md`

## 승인 기록

- 설계 변경 승인: 사용자 승인
- 승인 범위: 직원용 도착 확인 API, 자동 노쇼 연동, 상태 전이, 동시성·통합 검증
- 확정 사항: `NO_SHOW_PENDING` 필수 도입, 추가 유예시간 기본값 5분
