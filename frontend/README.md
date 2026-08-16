# DoctorPet 프론트엔드 (보호자·병원 스태프 웹)

React + TypeScript + Vite 기반 SPA. 보호자용 화면과 병원 스태프용 화면(역할 기반 라우팅)을
같은 앱에서 서비스한다. 모노레포(`SmartCare-Project`) 안의 `frontend/`로 git에 포함돼 있다.

## 실행

```bash
npm install
npm run dev        # 실연동(목 OFF). http://localhost:5173, /api·/ws/chat → http://localhost:8080 프록시
npm run dev:mock   # 백엔드 없이 UI만 (전체 MSW 목)
```

- `npm run dev`: **목 OFF, 실연동 기본.** 백엔드(8080) 실데이터를 그대로 본다. 백엔드 미구현
  API(병원 검색·예약 목록·알림 등)는 에러/빈 상태로 보이는 게 정상이다.
- `npm run dev:mock`: 백엔드 없이 전체 UI를 클릭해보는 목 모드(`.env.mock`, `VITE_ENABLE_MOCKS=true`).
  단 `/pets`는 백엔드 실동작이라 목에서 제외 — 프록시로 백엔드에 간다.

백엔드 CORS 미설정이라 dev 서버 프록시로 우회한다. REST `/api`와 채팅 native WebSocket
`/ws/chat`은 각각 프록시하며, 대상은 `VITE_PROXY_TARGET`으로 바꿀 수 있다.

## 스크립트

| 명령 | 설명 |
| --- | --- |
| `npm run dev` | 개발 서버 |
| `npm run build` | 타입체크 + 프로덕션 빌드 |
| `npm run typecheck` | 타입 검사만 |
| `npm run lint` | ESLint |
| `npm run format` | Prettier 포맷 |
| `npm test` | Vitest 실행 |

## 스택

React 19 · TypeScript · Vite · TanStack Query · axios(+401 재발급 인터셉터) ·
React Router · React Hook Form + Zod · Zustand · Tailwind CSS v4 + shadcn/ui ·
Vitest · ESLint · Prettier · MSW(mock).

## 구조

```
src/
  app/        Provider·라우터·인증 가드·QueryClient
  lib/        axios·에러/언랩·토큰 저장소·인증 스토어·상태 매핑 유틸
  types/      ApiResponse·enum (SA §7·§8 계약)
  features/   도메인별 api·hooks·schema·컴포넌트
  components/ ui(shadcn)·common(레이아웃·상태배지·상태 컴포넌트)
  pages/      라우트 화면
  mocks/      MSW 핸들러·시드 (미구현 API 전용)
```

## 실연동 vs mock

MSW는 개발 + `VITE_ENABLE_MOCKS=true`일 때만 켜지고, **미구현 API만** 가로챈다.
핸들러가 없는 요청(실연동 대상)은 프록시로 백엔드에 그대로 넘어간다.

**실연동 (백엔드 존재)**: 회원가입·로그인·재발급·로그아웃, `GET /members/me`,
`GET /hospitals/{id}`, 결제수단 등록/조회/삭제, 예약 요청/취소.

**mock (백엔드 미구현)**: 병원 검색·슬롯, 예약 목록/상세, 결제 내역, 알림, 펫 CRUD(PR 리뷰 중).
백엔드가 나오면 `src/mocks/handlers.ts`에서 해당 핸들러만 제거하면 실연동으로 전환된다.

## 백엔드 계약 확인 결과 (SA와 다른 점 — 실제 코드 기준)

작업 중 백엔드 컨트롤러/DTO를 확인해 맞춘 부분. SA 문서와 어긋나므로 팀 확인 권장.

- **예약 요청 body**: SA §8-5는 `{ petId, slotId, paymentMethodId }`이지만 실제
  `ReservationRequest`는 `petNameSnapshot`·`petSpeciesSnapshot`(문자열)도 **필수**로 받는다.
  프론트는 선택한 펫에서 스냅샷을 채워 전송한다.
- **회원 탈퇴** `DELETE /members/me`: SA §8-1에 있으나 **미구현**(보류). 화면에서 제외.
- **병원 검색·슬롯, 예약 목록·상세, 보호자 결제 내역 조회**: SA §8에 있으나 **미구현** → mock.
- `MemberResponse`는 `memberId`(+`hospitalId`), `PetResponse`는 `petId` 사용. DELETE는 204(본문 없음).

## 아직 안 만든 것 / 후속

- **PortOne 빌링키 발급**(결제창 연동): 현재 결제수단 등록은 발급된 빌링키 문자열 입력. SDK 통합은 후속.
- **AI 상담**: Tool 실패 응답 방식이 SA 부록 A `[결정 필요]`라 깊게 구현하지 않음. 메인 검색은 병원 검색으로 연결.
- **실시간 push**: 실시간 알림은 SSE로 연동 완료(`features/notifications/notificationSse.ts`, 티켓 인증·1회성 티켓 재구독 계약 포함). 폴링(`useNotifications`)은 끊김 시 백업 경로로 유지.
- **예약당 채팅**: 보호자·병원 스태프 예약 상세에서 REST 이력 조회 후 native WebSocket + STOMP로 실시간 수신한다. `dev:mock`은 실제 STOMP 연결을 시도하지 않고 안내만 표시한다. 운영 Nginx는 `/ws/chat`에 `proxy_http_version 1.1`, `Upgrade`, `Connection "upgrade"` 헤더와 긴 `proxy_read_timeout`을 전달해야 한다(현재 저장소 nginx 설정에는 별도 location이 없어 배포 설정 작업이 필요).
- **병원 스태프 화면·환불**: 대시보드·예약 승인/거절·상태전이·청구·오프라인 정산·환불(`POST /api/hospital/payments/{paymentId}/refund`) 구현 완료. 결제/환불 내역 목록 조회(`GET /api/hospital/payments`)·슬롯 관리(`GET/PATCH /api/hospital/slots*`)는 백엔드 미착수라 해당 메뉴를 숨겨뒀다(`StaffLayout`) — 백엔드 머지 후 다시 노출. 스태프 계정 발급(회원가입)은 여전히 범위 밖(수동 DB 승격만 가능, `HANDOFF-hospital-and-sync.md` 참고).
- 지도 SDK, 병원 후기 API는 미제공 → 자리(placeholder)만.
- **알려진 취약점(대응 유보)**: `npm audit` high 1건이 남아 있다 — `react-router`/`react-router-dom` RSC Mode CSRF([GHSA-qwww-vcr4-c8h2](https://github.com/advisories/GHSA-qwww-vcr4-c8h2)). 이 앱은 RSC Mode를 쓰지 않아 직접 도달 가능성은 낮지만, 안전한 수정 버전이 아직 없고 `npm audit fix --force`는 `react-router-dom@7.11.0`으로 **다운그레이드**하는 breaking change라 지금은 적용하지 않았다. 패치 버전이 나오면 업그레이드할 것(`brace-expansion`은 이번에 `npm audit fix`로 이미 해결).
