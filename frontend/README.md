# DoctorPet 프론트엔드 (보호자·병원 스태프 웹)

React + TypeScript + Vite 기반 SPA. 보호자용 화면과 병원 스태프용 화면(역할 기반 라우팅)을
같은 앱에서 서비스한다. 모노레포(`SmartCare-Project`) 안의 `frontend/`로 git에 포함돼 있다.

## 실행

```bash
npm install
npm run dev        # 실연동(목 OFF). http://localhost:5173, /api·/ws/chat → http://localhost:8080 프록시
npm run dev:mock   # 백엔드 없이 UI만 (전체 MSW 목)
```

- `npm run dev`: **목 OFF, 실연동 기본.** 백엔드(8080)의 실제 API와 데이터를 사용한다.
- `npm run dev:mock`: 백엔드 없이 전체 UI를 클릭해보는 목 모드(`.env.mock`, `VITE_ENABLE_MOCKS=true`).
  핸들러가 없는 요청은 프록시로 전달되므로 일부 흐름에는 백엔드가 필요할 수 있다.

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
  mocks/      MSW 데모 핸들러·시드
```

## 실연동 vs mock

MSW는 `VITE_ENABLE_MOCKS=true`인 `dev:mock`에서만 켜진다. 백엔드 구현 여부와 관계없이
화면 데모에 필요한 요청을 목 응답으로 처리하며, 핸들러가 없는 요청은 프록시로 백엔드에 전달한다.

**실연동(`npm run dev`)**: 인증·회원·펫, AI 상담, 병원 검색·상세·보호자용 예약 슬롯, 예약 목록·상세·요청·취소,
결제수단·결제 내역, 알림·SSE, 채팅, 병원 스태프의 예약 관리와 예약 상세 청구·오프라인 정산·환불, 결제 목록 대시보드(`GET /api/hospital/payments`)를 백엔드 API에 연결한다. 병원 스태프의 슬롯 관리 화면은 일 단위 임시휴진으로 대체돼 제거됐다.

**목 모드(`npm run dev:mock`)**: 백엔드 없이 주요 보호자·병원 스태프 화면을 확인하기 위한 데모 모드다.
실제 연동 검증에는 사용하지 않으며, 목 응답이 없는 요청은 백엔드가 필요할 수 있다.

## 백엔드 계약

프론트 타입과 요청 형식은 현재 백엔드 DTO를 따른다. 예약 요청 body는
`{ petId, slotId, paymentMethodId }`이며, 반려동물 이름·종 스냅샷은 서버가 소유 펫에서 확정한다.
회원 탈퇴, 병원 검색·슬롯, 예약 목록·상세, 보호자 결제 내역도 실연동돼 있다.
`MemberResponse`는 `memberId`와 역할에 따른 `hospitalId`, `PetResponse`는 `petId`를 사용한다.

## 아직 안 만든 것 / 후속

- **PortOne 실제 운영 인증**: 결제수단 화면의 Browser SDK 카카오페이 빌링키 발급·등록은 구현됐다. PC iframe·모바일 `/payment-methods` 복귀를 실제로 검증하려면 Vercel의 `VITE_PORTONE_STORE_ID`·`VITE_PORTONE_CHANNEL_KEY`를 주입하고 실인증을 확인해야 한다.
- **실시간 push**: 실시간 알림은 SSE로 연동 완료(`features/notifications/notificationSse.ts`, 티켓 인증·1회성 티켓 재구독 계약 포함). 폴링(`useNotifications`)은 끊김 시 백업 경로로 유지.
- **예약당 채팅**: 보호자·병원 스태프 예약 상세에서 REST 이력 조회 후 native WebSocket + STOMP로 실시간 수신한다. `dev:mock`은 실제 STOMP 연결을 시도하지 않고 안내만 표시한다. 운영 Nginx의 `/ws/chat` Upgrade 프록시 설정도 반영돼 있다.
- **결제 복구·정정 UI**: 백엔드의 보호자 셀프 재청구와 병원 스태프 정정 재청구 API는 구현됐지만 이를 실행하는 프론트 화면은 아직 없다.
- **병원 스태프 슬롯 관리**: 슬롯 단위 관리 화면은 제거됐고 일 단위 임시휴진(`/api/hospital/temporary-closures`)으로 대체됐다. 결제 목록 대시보드(`/staff/payments`)와 예약 상세의 청구·오프라인 정산·환불은 `GET /api/hospital/payments` 구현으로 상시 노출되며 현재 실연동 범위다.
- **병원 스태프 계정 발급**: 공개 회원가입은 보호자용이며, 스태프 계정 발급·초대 화면은 범위 밖이다.
- 지도 SDK는 연동하지 않았으며 위치 정보는 목록·상세의 텍스트와 거리 정보로 제공한다.
