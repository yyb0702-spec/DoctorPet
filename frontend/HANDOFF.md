# DoctorPet 프론트엔드 — 새 채팅 인수인계 프롬프트

> 아래 전체를 새 채팅 첫 메시지로 붙여넣으면 됩니다. (한국어로 응답해 주세요.)

---

너는 DoctorPet(반려동물 병원 예약·후불결제) 프로젝트의 **보호자용 웹 프론트엔드**를 이어서 개발한다. **한국어로 응답**한다.

## 저장소·작업 방식 (중요)
- 저장소 루트: `C:\Users\김준형\Desktop\java\doctorpet\SmartCare-Project` (백엔드 Spring Boot)
- 프론트는 그 안의 `frontend/` 폴더(React+TS+Vite). 모노레포에 편입되어 git으로 추적된다(PR #127).
- 먼저 읽을 것: `AGENTS.md`, `docs/frontend/DoctorPet-프론트엔드-기술스택.md`, `docs/frontend/DoctorPet-화면-메모.md`, `docs/architecture/DoctorPet-SA.md`(§5 상태머신·§7 공통응답·§8 API). 그리고 `frontend/README.md`.
- 자동 로드되는 메모리 파일이 있다(`frontend-scaffold`, `backend-api-deviations`). 이미 최신화돼 있으니 참고.

## 스택
React 19 + TypeScript + Vite / TanStack Query / axios(+401 재발급 인터셉터) / React Router / React Hook Form + Zod / Zustand / Tailwind v4 + shadcn/ui / Vitest / ESLint+Prettier / MSW.
`@`→`src` 별칭. 공통응답 `ApiResponse{code,message,data}`(성공 code="SUCCESS").

## 실행 (목 정책)
- `npm run dev` = **실연동(목 OFF)**. dev proxy `/api`→`http://localhost:8080`. 백엔드 실데이터.
- `npm run dev:mock` = **백엔드 없이 전체 UI**(MSW full 목, `.env.mock`의 `VITE_ENABLE_MOCKS=true`). `src/mocks/handlers.ts`(공용)+`demoHandlers.ts`(dev:mock 전용).
- 백엔드 인프라: 프로젝트 루트에서 `docker compose up -d`(mysql 3307·redis 6380). 백엔드 앱은 `.\gradlew.bat bootRun`(프로파일 local 기본).
- ⚠️ 한글 경로 때문에 gradle 빌드 산출물은 `C:/agora-build/...`로 리다이렉트됨(`~/.gradle/gradle.properties`의 `localBuildDir`). 테스트 포함 빌드는 워커 argfile이 깨질 수 있어 `bootJar -x test` 권장.
- ⚠️ 공공데이터포털(data.go.kr) 점검으로 병원 실데이터 시드 불가 → 대신 `frontend/dev-seed-sample-hospitals.sql`로 샘플 병원 5곳(제휴/비제휴/임시휴업 다양) DB 주입:
  `cat frontend/dev-seed-sample-hospitals.sql | docker exec -i doctorpet-mysql mysql -uroot -plocaldev smartcare` 후 `docker exec doctorpet-redis redis-cli FLUSHALL`.

## 지금까지 구현·검증 완료된 화면
메인(히어로+검색+추천병원) / 회원가입 / 로그인 / 병원 검색(실연동, 페이지네이션·필터·거리·예약가능배지, 카드 클릭 시 인라인 지도·상세 이동) / 병원 상세(실연동, 위치 지도 포함) / 펫 프로필(실연동 CRUD, 인라인 수정) / 결제수단(실연동 등록/조회/삭제) / 예약 요청 패널(슬롯·펫·결제수단 선택) / 내 예약 목록(실연동, progressStatus 배지·페이지네이션·상태필터) / 예약 상세(실연동, 상태 배너+진행스텝+취소) / 마이페이지(회원정보 조회+펫·결제 요약, 우상단 [닉네임▾] 메뉴). 지도는 좌표가 API에 없어 주소 기반 Google 임베드(키 불필요).

## 실연동 vs 아직 목/미구현 (백엔드 기준)
- 실연동 O: auth(가입/로그인/재발급/로그아웃), 회원정보 조회, 병원 검색·상세, 슬롯 조회(방금 머지, 프론트 미반영), 펫 CRUD, 결제수단 CRUD, 예약 요청/취소/목록/상세, 프로필 수정(방금 머지)·탈퇴(방금 머지)·이메일인증·비번재설정(방금 머지).
- 아직 미구현(목 유지): 보호자 **결제내역 조회**(`GET /reservations/{id}/payments`), **알림**, **AI 상담**.

## ★ 다음에 할 작업 — 어제~오늘 새로 머지된 API 반영 (아직 프론트 미반영, origin/develop을 현재 브랜치에 이미 머지해둠)

### 1) 예약 시간 선택 실연동 (최우선, 그동안 막혀 있던 핵심)
`GET /api/hospitals/{hospitalId}/slots?date=yyyy-MM-dd` (date 필수) → `HospitalSlotLookupResponse`:
- `selectedDate: LocalDate`
- `dateAvailabilities: [{ date, reservationAvailable }]`  ← 날짜 선택 캘린더용
- `slots: [{ slotId, startAt, endAt, availabilityStatus }]`
- `availabilityStatus`: `AVAILABLE | RESERVED | LEAD_TIME_CLOSED`
→ 할 일: `features/hospitals/types.ts`의 Slot을 실계약으로 교체 + SlotLookup 타입 추가, `api.getSlots(hospitalId, date)`에 date 파라미터, `useHospitalSlots(hospitalId, date)`, `ReservationRequestPanel`에 **날짜 선택→슬롯 표시(AVAILABLE만 선택 가능, RESERVED/LEAD_TIME_CLOSED 비활성)→예약 요청** 흐름. `HospitalDetailPage`의 "가장 빠른 진료 가능"도 새 계약에 맞춤. mocks(handlers/demoHandlers)의 slots를 새 형태로. → 이걸로 예약 생성 E2E가 실연동으로 열림.

### 2) 마이페이지 회원정보(닉네임) 수정 활성화
`PATCH /api/members/me { nickname }`(max 255) → MemberResponse. → MyPage의 "정보 수정(준비 중)" 비활성 버튼을 실제 닉네임 수정 폼/다이얼로그로. (email·password는 이 API 대상 아님)

### 3) 회원 탈퇴
`DELETE /api/members/me` → Void. **활성 예약(CONFIRMED·CHECKED_IN) 보유 시 보류(에러)**. → 마이페이지에 탈퇴 버튼+확인 모달, 보류 에러 안내, 성공 시 로그아웃 처리.

### 4) 비밀번호 재설정 화면
- `POST /api/auth/password-reset/request { email }` → 항상 200(계정 노출 안 함). 로그인 화면에 "비밀번호를 잊으셨나요?" → 이메일 입력 → 안내.
- `POST /api/auth/password-reset/confirm { token, newPassword(8자+·UTF-8 72바이트 이하) }` → 이메일 링크의 token 쿼리를 받는 재설정 페이지.

### 5) 이메일 인증 화면
- `GET /api/auth/verify-email?token=...`(현재 이메일 링크가 백엔드를 직접 가리킴 — 프론트 페이지가 이 API를 fetch로 호출하는 구조로 바꿔야 함), `POST /api/auth/verify-email/resend { email }`.
- ⚠️ **확인 필요**: 로그인이 `email_verified`를 요구하는지(요구하면 기존 테스트 계정 로그인 막힘). AuthService/login 로직 확인 후 미인증 안내 UX 결정.

권장 우선순위: **1 → 2·3(마이페이지 묶어서) → 4 → 5**. 검색 때와 동일하게 실연동은 백엔드, dev:mock은 오프라인 목 유지(A안). 각 단계마다 `tsc`/ESLint/Vitest + 브라우저 검증.

## 검증 규칙
- 실행 안 한 검증을 통과라 하지 않는다. `npx tsc -b --noEmit`, `npx eslint src`, `npx vitest run`으로 확인.
- 브라우저 자동화(mcp Claude Browser) 함정: **프로그램적 클릭/폼입력이 React 핸들러(onChange/onClick)를 못 태우는 경우가 잦다.** 검증 시 `javascript_tool`로 `element.click()` 또는 native setter+`dispatchEvent(new Event('input',{bubbles:true}))`로 확인하거나, 페이지 컨텍스트 `fetch`로 API 왕복을 증명한다. (제품 버그 아님)
- dev:mock에서 인증 상태가 필요하면 `localStorage.setItem('dp.accessToken','demo'); localStorage.setItem('dp.refreshToken','demo')` 후 새로고침(토큰 키는 tokenStore 기준).

## 논의/결정 사항
- 회원정보 수정은 백엔드가 없어 보류였는데 이번에 **PATCH 생김** → 활성화 대상.
- 지도: 좌표 미제공이라 주소 기반 Google 임베드로 감(정식 카카오/네이버 지도는 JS 키 필요, 후속).
- 예약 생성 시간선택이 슬롯 API 부재로 막혀 있었는데 이번에 **슬롯 조회 생김** → 실연동 가능.
