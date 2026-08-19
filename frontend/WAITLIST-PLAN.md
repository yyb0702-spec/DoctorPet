# 예약 대기열(Waitlist) 프론트 연동 계획서

> 작성 2026-08-19 · 대상: 보호자 웹 프론트(`frontend/`) · 다른 채팅에서 이어서 작업할 핸드오프용
> 목적: 백엔드에 이미 완비된 대기열 API(`/api/reservation-waitlists`)를 프론트에 연동한다. **현재 프론트에 대기열 UI가 전혀 없다.**

---

## 1. 현황

- **백엔드**: `ReservationWaitlistController`(`/api/reservation-waitlists`) + 상태 머신 + 승급 제안/만료 배치 + 알림(`WAITLIST_OFFERED`)까지 완비, develop 반영됨.
- **프론트**: 대기열 관련 코드 **0건**(신규 구축).

## 2. API 계약 (백엔드 코드 기준)

전부 `ApiResponse` 래핑, 보호자 인증(`@AuthenticationPrincipal`).

| 메서드·경로 | 요청 | 응답 | 용도 |
|---|---|---|---|
| `POST /api/reservation-waitlists` | `{ slotId }` | 201 `ReservationWaitlistResponse` | 만석 슬롯 대기 신청 |
| `GET /api/reservation-waitlists` | - | `ReservationWaitlistResponse[]` | 내 대기 목록 |
| `GET /api/reservation-waitlists/{id}` | - | `ReservationWaitlistResponse` | 단건 |
| `DELETE /api/reservation-waitlists/{id}` | - | Void | 대기 취소(WAITING만) |
| `POST /api/reservation-waitlists/{id}/accept` | `{ petId, paymentMethodId }` | 201 `ReservationResponse` | 승급 제안 **수락** → 예약 생성 |
| `PATCH /api/reservation-waitlists/{id}/reject` | - | Void | 승급 제안 **거절** |

**`ReservationWaitlistResponse`**: `waitlistId, slotId, status, requestedAt, offeredAt, offerExpiresAt, respondedAt, canceledAt`

**상태(`ReservationWaitlistStatus`)와 흐름**:
- `WAITING` → 슬롯이 비면 → `OFFERED`(다음 순번에게 제안, `offerExpiresAt` 마감) → `ACCEPTED`(수락→예약) / `REJECTED`(거절) / `EXPIRED`(마감 초과, 배치가 처리)
- `WAITING` → `CANCELED`(사용자가 대기 취소)
- 취소(DELETE)는 **WAITING만**, 수락/거절은 **OFFERED만** 가능.

## 3. 작업 범위

### A. 인프라 (`features/waitlist/`)
- `types.ts`: `WaitlistStatus`(6값)·`Waitlist`(응답 DTO) 타입
- `api.ts`: 위 6개 엔드포인트
- `hooks.ts`: `useMyWaitlists`(폴링), `useRegisterWaitlist`, `useCancelWaitlist`, `useAcceptWaitlist`, `useRejectWaitlist`

### B. 대기 신청 진입점
- 슬롯 선택(`ReservationRequestPanel`/`HospitalDetailPage`)에서 **만석 슬롯**(`availabilityStatus === 'RESERVED'`)에 "대기 신청" 액션 추가 → `POST` `{slotId}`
- 지금은 RESERVED 슬롯이 비활성(선택 불가)이라, 여기에 대기 신청 경로를 얹는다.

### C. 내 대기열 화면 (신규 `/waitlists` 또는 마이페이지 섹션)
- `GET` 목록 → 상태 배지(대기중/제안됨/수락/거절/만료/취소) + 슬롯 시간
- `WAITING`: "대기 취소"(DELETE)
- `OFFERED`: `offerExpiresAt`까지 **카운트다운** + "수락"/"거절". 수락은 펫·결제수단 선택(예약 요청 패널의 선택 UI 재사용) → `accept` → 생성된 예약 상세로 이동

### D. 알림 연동
- `WAITLIST_OFFERED` 알림 타입을 `NotificationBell`에 추가, 딥링크(내 대기열/해당 제안). SSE·폴링 기존 채널 그대로.

### E. mock (dev:mock)
- `demoHandlers`에 대기열 6개 엔드포인트 목 + 상태 전이 시뮬레이션(선택)

## 4. 확인 필요 (착수 전)

- **대기 신청 허용 조건**: RESERVED 슬롯만인지, `LEAD_TIME_CLOSED`도 되는지 — `ReservationWaitlistService.register` 검증 로직 확인.
- **순번(position) 없음**: 응답에 대기 순번 필드가 없다. "몇 번째 대기" 표시는 불가 → 내 항목의 `requestedAt`·상태만 보여주는 선에서 설계(순번이 필요하면 백엔드 변경, 별도 논의).
- **`WAITLIST_OFFERED` 알림의 `resourceType`/`resourceId`**: 대기열 id인지 예약 id인지 확인해 딥링크 대상 결정.
- **중복 신청·이미 예약 보유** 등 에러 코드(409 류) 확인해 사용자 안내 문구 매핑.

## 5. 권장 순서

1. A 인프라(types/api/hooks) → C 내 대기열 화면(조회·취소) — 먼저 조회/취소로 골격
2. B 대기 신청 진입점(슬롯 선택에 얹기)
3. OFFERED 수락/거절(카운트다운·펫·결제수단 재사용)
4. D 알림 딥링크 → E mock
- 각 단계 `tsc`/`eslint`/`vitest` + dev:mock/실연동 스모크

## 6. 검증 규칙

- 기존과 동일: 실행 안 한 검증을 PASS라 하지 않음(PASS/FAIL/PARTIAL/BLOCKED). `npm run dev`=실연동, `dev:mock`=오프라인.
- 실연동 시 만석 슬롯을 만들려면 슬롯 1개를 다른 계정으로 먼저 예약해 RESERVED로 만든 뒤 대기 신청 → 그 예약을 취소해 OFFERED 유도(제안 흐름 확인).
