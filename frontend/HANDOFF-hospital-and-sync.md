# DoctorPet 프론트엔드 — 새 채팅 인수인계 프롬프트 (실시간 알림 동기화 + 병원 스태프 화면)

> 아래 전체를 새 채팅 첫 메시지로 붙여넣으면 됩니다. (한국어로 응답)

---

너는 DoctorPet(반려동물 병원 예약·후불결제) 프로젝트의 **웹 프론트엔드**를 이어서 개발한다. **한국어로 응답**한다.

## 저장소·작업 방식 (중요)
- 저장소 루트: `C:\Users\김준형\Desktop\java\doctorpet\SmartCare-Project` (백엔드 Spring Boot 모노레포)
- 프론트는 그 안의 `frontend/`(React 19 + TS + Vite). 모노레포에 편입되어 git으로 추적된다(PR #127).
- 먼저 읽을 것: `AGENTS.md`, `frontend/HANDOFF.md`(기존 인수인계), `frontend/README.md`, `docs/frontend/DoctorPet-프론트엔드-기술스택.md`, `docs/frontend/DoctorPet-화면-메모.md`(§C 병원 스태프 플로우 개략), `docs/architecture/DoctorPet-SA.md`(§5 상태머신·§7 공통응답·§8-5/§8-6/§8-7/§8-8·§9-8 실시간 알림).
- 자동 로드 메모리(`frontend-scaffold`, `frontend-next-steps`, `backend-api-deviations`) 참고. **보호자(GUARDIAN) 화면은 2026-08-04 기준 전부 실연동 완료**(검색·상세·슬롯·펫·결제수단·예약 요청/취소/목록/상세·마이페이지·탈퇴·이메일인증·비번재설정·결제내역·AI 상담·알림 폴링). 이번 작업은 그 이후 백엔드 변경분 반영 + 병원 스태프 화면 신규다.

## 스택 / 실행
- React 19 + TS + Vite / TanStack Query / axios(+401 재발급 인터셉터) / React Router / RHF+Zod / Zustand / Tailwind v4 + shadcn/ui / Vitest / ESLint+Prettier / MSW. `@`→`src`. 공통응답 `ApiResponse{code,message,data}`(성공 code="SUCCESS").
- `npm run dev` = 실연동(목 OFF, `/api`→`http://localhost:8080`). `npm run dev:mock` = 백엔드 없이 전체 UI(MSW full 목). `src/mocks/handlers.ts`(공용)+`demoHandlers.ts`(dev:mock 전용).
- 백엔드 인프라(실연동 시): 루트에서 `docker compose up -d`(mysql 3307·redis 6380) 후 `.\gradlew.bat bootRun`. ⚠️ 한글 경로로 gradle 산출물이 `C:/agora-build/...`로 리다이렉트됨. 샘플 병원 시드는 `frontend/dev-seed-sample-hospitals.sql` 참고.
- 각 단계 검증: `tsc`/ESLint/Vitest + 브라우저(dev:mock 우선, 실연동은 인프라 기동 시).

---

# Part 1 — 보호자 앱 동기화 (2026-08-04 이후 백엔드 변경 반영)

## 1) 실시간 알림 SSE (#40) — 기존 폴링 알림 위에 실시간 전송 추가
현재 `NotificationBell`은 폴링(`GET /api/notifications`)으로만 동작한다. 여기에 SSE 실시간 수신을 얹는다.

- **티켓 발급**: `POST /api/notifications/subscribe-ticket` (JWT 인증) → `ApiResponse{ data: { ticket } }`. 티켓은 단기(30초)·**1회성**.
- **구독**: `GET /api/notifications/subscribe?ticket=<ticket>` (`text/event-stream`). 인증 헤더 불가라 티켓으로 식별.
- **이벤트 수신**: 서버가 이벤트명 **`"notification"`**으로 보낸다 → `EventSource.addEventListener('notification', ...)` (⚠️ `onmessage` 아님). payload는 `NotificationResponse`(아래 #2와 동일 형태) JSON.
- **★ 재연결 계약(필수, 안 지키면 첫 끊김 이후 실시간 멈춤)**: 티켓이 1회성이라 브라우저 `EventSource`의 자동 재연결은 소비된 티켓으로 401을 받아 실패한다. 따라서 `onerror`에서 자동 재연결에 의존하지 말고 **기존 연결을 닫은 뒤 새 티켓을 발급받아 새 `EventSource`로 재구독**한다(지수 백오프). 서버 emitter 타임아웃(30분) 만료 시에도 동일. 끊긴 동안 유실분은 폴링(`GET /api/notifications`) 재조회로 보정.
- **연결 상한**: 회원당 동시 5개, 초과 시 `429 SSE_TOO_MANY_CONNECTIONS`. → 재구독 전 반드시 기존 `EventSource`를 닫아 중복 연결을 만들지 말 것. 429면 잠시 후 재시도.
- **수신 시 동작**: 새 알림 이벤트가 오면 TanStack Query 알림 목록 캐시를 invalidate(또는 낙관적 prepend) + 미읽음 배지 증가. 실시간이 끊겨도 폴링이 백업이므로 UX가 깨지지 않게.
- **연결 수명**: 로그인 상태에서만 구독, 로그아웃 시 `EventSource.close()`. 앱 전역에서 단일 연결(예: 인증 컨텍스트/전용 훅 `useNotificationStream`)로 관리.
- **mock**: dev:mock에선 SSE 대신 기존 폴링만 두거나, `EventSource`를 흉내내는 목 스트림을 둔다(선택). 실연동에서만 SSE.

## 2) 예약·노쇼 알림 실제 발행 시작 (#88) — 딥링크 개선
과거 메모엔 "MVP는 PAYMENT_RESULT만 실제 발행"이라 예약 알림이 없었는데, **이제 예약·노쇼 알림이 실제로 발행된다.**
- `NotificationResponse{ id, type(String), content, resourceType('RESERVATION'|'PAYMENT'|null), resourceId(Long|null), isRead, readAt, createdAt }`.
- type별 발행·딥링크:
  - `RESERVATION_CONFIRMED`(승인), `RESERVATION_REJECTED`(수동 거절 + 승인 타임아웃 자동 거절), `NO_SHOW`(노쇼 — 수동 확정 또는 스케줄러 자동 판정 #102) → `resourceType='RESERVATION'`, `resourceId=reservationId` → **이제 `/reservations/{resourceId}`로 직접 딥링크 가능**(기존엔 예약 알림이 없어 못 하던 것).
  - `PAYMENT_RESULT`(결제 결과) → `resourceType='PAYMENT'`, `resourceId=paymentId` → 예약 직접 링크 불가라 예약 목록으로(기존 동작 유지).
- → `NotificationBell` 라우팅을 resourceType별로 갱신, 목(handlers/demoHandlers·data)에 예약/노쇼 알림 시드 추가해 딥링크 검증.
- 참고: 예약 알림은 승인·거절·노쇼 전이에서만 발행되고 체크인/진료중/완료/취소 전이는 발행되지 않는다(설계).

## 3) 기타 백엔드 머지 반영 점검 (프론트 마지막 작업 2026-08-04 이후 #89~#97)
프론트가 아직 못 본 머지들. 대부분 서버 내부라 보호자 계약 변화가 없다 — 아래 판정을 참고해 필요한 것만 반영한다.

- **[반영 필요] #93 AI 안전 게이트웨이 — `AiConsultationResponse`에 `locationRequired: boolean` 필드 추가**(기존 `fallback`·`locationRecommended` 옆). → `features/ai`의 타입·화면에서 `locationRequired`가 true면 "정확한 추천을 위해 위치 정보가 필요"함을 사용자에게 안내(위치 제공/재요청 유도). `locationRecommended`와 구분: **required=위치 없이는 병원 추천 자체가 제한**, recommended=있으면 더 정확. demoHandlers의 AI 목에도 필드 추가.
- **[변화 없음] #97 member — 로그아웃(`POST /api/auth/logout`)이 이제 해당 Access Token까지 무효화**(서버가 Authorization 헤더를 다시 읽어 처리). 프론트는 이미 로그아웃 요청에 bearer 토큰을 보내므로 **API 계약·코드 변경 불필요**(서버 동작만 강화됨).
- **[변화 없음·서버 내부] #94 승인 타임아웃 자동거절, #92 Rate Limit, #90 결제 중복청구 방지, #91 결제 감사로그, #95 PortOne 셋업, #96 결제 웹훅, #89 CI/CD** — 컨트롤러/응답 DTO 계약 변화 없음(확인함).
  - #94: 승인 데드라인 초과 REQUESTED가 자동으로 REJECTED가 된다 → 보호자는 기존 예약 상태 폴링으로 REJECTED를 보고, 알림은 #88의 `RESERVATION_REJECTED`로 받는다. `approval_deadline_at`은 보호자 응답 DTO에 노출되지 않아 새 필드 작업 없음.
  - #92: 공개 AI 상담 등에서 429가 날 수 있으니 429를 사용자 친화적으로 안내하면 좋다(선택).

---

# Part 2 — 병원 스태프 화면 (신규, MVP 이후 확장)

기존 화면-메모 §C는 "병원 기능은 화면 없이 API로만"이라 미구현이었으나, **이번에 병원 스태프용 화면을 신규로 만든다.** 와이어프레임 이미지는 없으니 아래 API·플로우 기준으로 깔끔한 운영 대시보드를 구성한다.

## 앱 구성 (확정)
같은 React 앱에 **역할 기반 라우팅**으로 병원 스태프 영역을 추가한다(별도 앱 아님). 스태프 전용 레이아웃 + `/hospital/*` 라우트를 role=HOSPITAL_STAFF로 가드하고, 인증·axios·토큰 인프라는 보호자와 공유한다.

## 병원 스태프 계정 확보 (dev) — 확인 완료
**중요: 코드에 스태프 시드가 전혀 없다.** 회원가입(`POST /api/auth/signup`)은 항상 GUARDIAN만 만들고(`Member.createGuardian`, AuthService 주석 "스태프는 시드로만 생성된다"), `data.sql`·스태프 시더도 현재 없다. 따라서 스태프 계정은 **직접 만들어야 한다.**
- `members` 스키마: `email, password(BCrypt 인코딩), nickname, role, hospital_id(nullable), email_verified, ...`. 스태프 = `role='HOSPITAL_STAFF'` + `hospital_id`가 유효 병원 id.
- 로그인은 `email_verified=1`을 요구한다(false면 403 MEMBER_009). 그리고 `/api/hospital/**`는 SecurityConfig가 **JWT 토큰의 ROLE_HOSPITAL_STAFF**로 가드하므로, role을 바꾼 뒤 **재로그인**해야 토큰에 반영된다.

### 실연동 dev 계정 만들기 (권장: 가입→승격, BCrypt 수동 계산 불필요)
1. 샘플 병원 시드(hospital id 1~5): `cat frontend/dev-seed-sample-hospitals.sql | docker exec -i doctorpet-mysql mysql -uroot -plocaldev smartcare`
2. 일반 회원가입으로 계정 생성(비밀번호는 앱이 BCrypt 처리): 프론트 회원가입 화면 또는 `POST /api/auth/signup { email:"staff1@doctorpet.dev", password:"Password123!", nickname:"스태프1" }`
3. 그 계정을 스태프로 승격 + 이메일 인증 처리:
   ```sql
   UPDATE members SET role='HOSPITAL_STAFF', hospital_id=1, email_verified=1
    WHERE email='staff1@doctorpet.dev';
   ```
   실행: `docker exec -i doctorpet-mysql mysql -uroot -plocaldev smartcare -e "UPDATE members SET role='HOSPITAL_STAFF', hospital_id=1, email_verified=1 WHERE email='staff1@doctorpet.dev';"`
4. **재로그인** → 토큰에 HOSPITAL_STAFF 반영 → `/api/hospital/**` 접근 가능. 이 계정은 hospital_id=1(‘행복동물메디컬센터’, 슬롯 시드 있음) 소속.
5. 승인 플로우 테스트용 REQUESTED 예약: 별도 보호자 계정으로 병원 1의 슬롯에 예약 요청 → 스태프 계정으로 대시보드에서 승인.

### dev:mock
계정 불필요 — MSW로 스태프 로그인 + 병원 예약/상태전이/청구/정산을 목으로 흉내낸다. UI/플로우는 dev:mock으로 먼저 완성하고 실연동은 위 계정으로.

> 팀 확인 권장: SA §6-2가 말하는 "스태프는 제휴 병원 시드로 생성"이 아직 미구현이다. 운영/데모용 스태프 시더(또는 dev SQL)를 정식 추가할지는 팀에 확인.

## 인증·라우팅
- 로그인 응답/`GET /api/members/me`의 `MemberResponse{ memberId, email, role, hospitalId }`로 role 판별. 로그인 후 **role로 분기**: GUARDIAN→기존 홈, HOSPITAL_STAFF→`/hospital` 대시보드. 스태프 라우트는 role 가드.
- 모든 병원 API는 `/api/hospital/**`이고 `ROLE_HOSPITAL_STAFF`로 서버 가드됨. 요청 body/path에 `memberId`·`hospitalId`를 절대 싣지 않는다(서버가 principal로 식별·자병원 재검증).

## 화면 & API 매핑 (전부 `ApiResponse` 래핑, 스태프 인증)
1. **스태프 로그인** — 공용 로그인 재사용 + role 리다이렉트(또는 `/hospital/login` 별도 진입).
2. **예약 대시보드(목록)** — `GET /api/hospital/reservations?status=REQUESTED&page=0&size=20` → `HospitalReservationPageResponse{ content[], page, size, totalElements, totalPages, first, last }`(page 0-base). item: `{ reservationId, memberId, petId, petName, reservedAt, reservationStatus, rejectionReason, reservationHistory }`. **status 탭**: REQUESTED / CONFIRMED / CHECKED_IN / IN_TREATMENT / TREATMENT_COMPLETED / NO_SHOW / REJECTED / CANCELED. `reservationHistory`로 해당 보호자 이력(노쇼 횟수 등) 노출.
3. **예약 요청 검토 → 승인/거절**:
   - 승인 `PATCH /api/hospital/reservations/{id}/approve` → 200 Void. (슬롯 미점유·승인 데드라인 경과 시 실패)
   - 거절 `PATCH /api/hospital/reservations/{id}/reject { rejectReason }`. `rejectReason`은 **한국어 값 화이트리스트**: `"직원 부족" | "슬롯 등록 오류" | "진료 불가" | "기타"` (NotBlank, ≤255). → 사유 선택 드롭다운.
4. **진료 상태 전이**(상태 머신 SA §5-1, 각 200 Void, 잘못된 상태면 `INVALID_STATUS`):
   - 체크인 `PATCH .../{id}/check-in` (예약시각 +10분 그레이스 내)
   - 진료 시작 `PATCH .../{id}/start`
   - 진료 완료 `PATCH .../{id}/complete`
   - 진행 스텝 UI(예약확정→내원완료→진료중→진료완료)로 현재 상태 강조.
5. **노쇼 처리**:
   - ⚠️ **자동 판정 있음(#102, 병합 예정)**: 예약시각 +10분 경과·미체크인 `CONFIRMED`는 서버 스케줄러가 자동으로 `NO_SHOW` 처리한다 → 대시보드에 **스태프 조작 없이도 NO_SHOW가 나타날 수 있으므로** 목록을 폴링(refetchInterval)으로 갱신한다. 아래 수동 확정은 자동 판정보다 먼저 확정하거나 보완하는 용도(수동 판단 우선).
   - 수동 확정 `PATCH .../{id}/no-show { reason }`(≤255). 예약시각 전이면 `NO_SHOW_TOO_EARLY`.
   - 정정 `PATCH .../{id}/restore { reason }`(NO_SHOW→CHECKED_IN, "사실 도착"). 자동·수동 어느 쪽으로 NO_SHOW가 됐든 정정 가능.
6. **진료비 청구(후불)** — `POST /api/hospital/reservations/{id}/payments { amount }`(Integer, 0 초과·상한 300만원, 초과 시 `INVALID_AMOUNT`) → **201** `PaymentChargeResponse`. 결과는 레코드 status로: `PAID`(빌링키 자동결제 성공) / `OFFLINE_REQUIRED`(자동결제 실패→현장수납 필요) 등.
7. **결제 결과·오프라인 정산**:
   - 결제 내역 `GET /api/hospital/reservations/{id}/payments` → `List<PaymentHistoryResponse>{ paymentId, reservationId, status, paymentChannel(BILLING_KEY|OFFLINE), amount, cardBrandSnapshot, cardLast4Snapshot, createdAt, paidAt, failedAt, offlineSettledAt }`.
   - `status==OFFLINE_REQUIRED`이면 **현장 수납 확정** `PATCH /api/hospital/payments/{paymentId}/offline-settle` → `OFFLINE_PAID`.
8. **실시간 없음(폴링)**: 백엔드 알림 수신자는 **보호자만**이라 스태프에겐 SSE가 없다. 새 예약 요청 등은 대시보드 폴링(TanStack Query refetchInterval)으로 갱신한다.

## 상태 배지·공통
- 예약 상태: REQUESTED/CONFIRMED/CHECKED_IN/IN_TREATMENT/TREATMENT_COMPLETED/NO_SHOW/REJECTED/CANCELED. 결제 상태: PENDING/PAID/OFFLINE_REQUIRED/OFFLINE_PAID. 보호자 앱의 `<StatusBadge>`를 재사용/확장.
- 환불(REFUNDED)은 백엔드 구현 완료(#37, PR #112 develop 머지) → `POST /api/hospital/payments/{paymentId}/refund { reason }`, `PaymentHistoryResponse.refundedAt`을 `features/staffPayments`·`features/payments`가 소비한다. 단 결제/환불 목록 조회(`GET /api/hospital/payments`)는 아직 백엔드 미착수라 해당 화면만 404.
- mock 우선: `demoHandlers`에 병원 예약 목록·상태 전이·청구·정산 목을 추가해 전체 플로우를 dev:mock으로 시연 → 이후 실연동.

## 권장 순서
Part 1 (실시간 알림 #40 → 딥링크 #88) 먼저 → Part 2 (병원: 역할 라우팅 → 예약 대시보드·승인/거절 → 상태 전이 → 청구·정산). 앱 구성(같은 앱)·스태프 계정 발급 방법은 위에 확정돼 있으니 별도 질문 없이 진행 가능.
