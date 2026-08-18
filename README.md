# DoctorPet

반려동물의 증상을 바탕으로 진료 가능한 동물병원을 찾고, 병원 승인형 예약부터 진료 후 결제까지 연결하는 서비스입니다.

보호자는 AI 증상 상담과 조건 기반 병원 검색을 이용해 예약을 요청할 수 있습니다. 병원 스태프는 예약을 승인하고 진료 상태를 관리하며, 진료 완료 후 청구 항목을 작성해 후불 결제를 처리합니다. 예약 알림은 SSE, 예약별 상담은 WebSocket STOMP로 제공합니다.

## 주요 기능

### 회원과 반려동물

- 보호자·병원 스태프 역할 기반 JWT 인증과 Refresh Token 재발급
- 이메일 인증, 비밀번호 재설정, 로그인 실패 횟수 기반 계정 잠금
- 회원 정보 조회·수정·탈퇴와 탈퇴 회원 개인정보 익명화
- 반려동물 프로필 CRUD와 S3 presigned URL 기반 이미지 업로드

### 병원 탐색

- 공공데이터 수집 및 자체 DB 기반 동물병원 검색
- 지역·거리·진료역량 등 조건을 조합한 QueryDSL 동적 검색
- Redis 원격 캐시를 이용한 기본 검색 결과 캐싱
- 병원 상세, 진료역량, 운영시간, 임시 휴무, 예약 가능 슬롯 조회
- 병원 찜과 병원별 리뷰 목록·평점 집계

### AI 증상 상담

- 증상 텍스트를 진료역량 코드로 구조화하고 병원 검색으로 연결
- OpenAI Structured Outputs 연동과 Fake Gateway 지원
- 인증·익명 사용자별 Rate Limit, Circuit Breaker, 타임아웃
- 민감 증상 원문 보존 기간 제한과 정기 삭제

> AI 상담은 의료 진단을 대신하지 않으며, 응급 징후가 있으면 즉시 가까운 동물병원에 연락해야 합니다.

### 예약

- 보호자의 예약 요청과 병원 스태프의 승인·거절
- 체크인, 진료 시작·완료, 보호자·병원 취소, 노쇼 상태 전이
- 낙관적 락과 DB 제약을 이용한 동일 슬롯 중복 점유 방지
- 승인 기한 만료·노쇼 판정 스케줄러와 예약 이벤트 이력
- 마감 슬롯 대기열 등록, 제안, 수락·거절·만료 처리
- 결제 선기록 전 예약 결제수단 재지정

### 결제

- PortOne V2 빌링키 기반 진료 후 자동결제와 Fake Gateway
- 회원별 결제수단 등록·조회·기본값 지정·삭제
- 서버가 청구 항목 합계로 산출하는 진료비 청구
- 승인 결과 웹훅 검증, 불확정 결제 정산 스케줄러, 오프라인 정산
- 빌링키 자동결제 완료 건의 전액 환불
- `OFFLINE_REQUIRED` 결제의 보호자 셀프 재청구와 오프라인 정산 간 이중 수납 방지
- 전액 환불 후 새 결제 이력을 만드는 병원 스태프 정정 재청구
- 보호자·병원용 구조화 JSON 영수증
- 결제·환불 감사 로그와 멱등·동시성 제어

### 알림과 채팅

- 회원·병원 수신자별 알림 저장, 목록, 읽음, 읽지 않은 개수
- 일회성 구독 티켓을 사용한 SSE 실시간 알림
- 예약 참여자 전용 WebSocket STOMP 채팅
- 메시지 저장 커밋 후 전송, 재전송 멱등 처리, 읽음 상태 관리

### 운영

- Docker Compose 기반 Nginx·Spring Boot·MySQL·Redis 구성
- Blue/Green 무중단 배포와 실패 시 롤백
- Actuator 헬스체크와 Prometheus·Grafana 모니터링
- GitHub Actions에서 백엔드 단위·슬라이스 테스트와 통합 테스트를 병렬 실행

## 기술 스택

| 구분 | 기술 |
| --- | --- |
| Backend | Java 17, Spring Boot 4.1, Spring MVC, Spring Security |
| Data | Spring Data JPA, QueryDSL, MySQL 8, Redis Lettuce |
| Auth | JWT Access/Refresh Token |
| Realtime | SSE, native WebSocket, STOMP, Spring SimpleBroker |
| External | OpenAI API, PortOne V2, 공공데이터 API, SMTP, AWS S3 |
| Test | JUnit 5, Mockito, Spring Boot Test |
| Frontend | React 19, TypeScript, Vite, TanStack Query, Tailwind CSS |
| Infra | Docker Compose, Nginx, GitHub Actions, Prometheus, Grafana |

## 구조

```text
DoctorPet
├── src/main/java/com/doctorpet
│   ├── domain
│   │   ├── ai              AI 증상 상담
│   │   ├── chat            예약별 채팅
│   │   ├── hospital        병원·검색·운영시간·찜
│   │   ├── member          인증·회원
│   │   ├── notification    알림·SSE
│   │   ├── payment         결제수단·청구·정산·환불·영수증
│   │   ├── pet             반려동물 프로필
│   │   ├── reservation     예약·슬롯·대기열·상태 머신
│   │   └── review          병원 리뷰
│   └── global              공통 설정·보안·예외·외부 Gateway
├── src/test                단위·슬라이스·통합 테스트
├── frontend                React 기반 보호자·병원 스태프 웹
├── docs                    제품·아키텍처·정책·검증 문서
├── nginx                   Blue/Green 리버스 프록시 설정
└── .github/workflows       CI/CD와 문서 하네스 검증
```

도메인 사이의 직접 Repository 참조를 피하고 서비스 또는 Port를 통해 협력합니다. 외부 연동은 Gateway 인터페이스 뒤에 격리해 로컬·테스트 환경에서는 Fake 구현체로 대체할 수 있습니다.

## 로컬 실행

### 준비물

- JDK 17
- Docker와 Docker Compose
- Node.js 24 이상과 npm — 프론트엔드를 실행할 때만 필요

### Docker Compose로 백엔드 실행

1. 환경변수 예시 파일을 복사합니다.

```powershell
Copy-Item .env.example .env
Copy-Item nginx/conf.d/upstream-active.conf.example nginx/conf.d/upstream-active.conf
```

2. `.env`에서 최소한 다음 값을 안전한 로컬 값으로 교체합니다.

```dotenv
JWT_SECRET=<256-bit 이상의 임의 문자열>
PAYMENT_BILLING_KEY_ENC_KEY=<Base64로 인코딩한 256-bit AES 키>
MYSQL_ROOT_PASSWORD=<로컬 MySQL 비밀번호>
SPRING_DATASOURCE_PASSWORD=<위 MySQL 비밀번호>
```

외부 서비스를 호출하지 않으려면 `PAYMENT_GATEWAY=fake`, `AI_GATEWAY=fake`, `MAIL_PROVIDER=fake`, `IMAGE_STORAGE_PROVIDER=fake`를 유지합니다. `.env`에는 비밀값이 포함되므로 커밋하지 않습니다.

3. 기본 Blue 인스턴스와 의존 서비스를 실행합니다.

```powershell
docker compose up -d --build mysql redis app-blue nginx
```

4. 상태를 확인합니다.

```powershell
docker compose ps
Invoke-WebRequest http://localhost:8080/healthz
```

API는 `http://localhost:8080`에서 제공됩니다. 종료할 때는 다음 명령을 사용합니다.

```powershell
docker compose down
```

운영 서버에서는 위 수동 기동 명령 대신 Blue/Green 배포 워크플로를 사용합니다. `prod` 프로파일은 PortOne·SMTP·S3 실연동 설정이 없으면 안전장치에 의해 부팅되지 않습니다.

### 프론트엔드 실행

```powershell
Set-Location frontend
npm ci
npm run dev
```

개발 서버는 기본적으로 `http://localhost:5173`에서 실행되며 `/api`와 `/ws/chat` 요청을 백엔드로 프록시합니다. 백엔드 없이 UI만 확인하려면 `npm run dev:mock`을 사용합니다. 자세한 내용은 [frontend/README.md](frontend/README.md)를 참고하세요.

## 검증

```powershell
# 전체 백엔드 테스트
./gradlew test

# MySQL·Redis가 필요 없는 단위·슬라이스 테스트
./gradlew unitTest

# 실제 MySQL·Redis를 사용하는 통합 테스트
./gradlew integrationTest

# 빌드와 실행 JAR 생성
./gradlew build
```

```powershell
# 프론트엔드 검증
Set-Location frontend
npm run typecheck
npm run lint
npm test
npm run build
```

검증 Level과 실행 조건은 [검증 가이드](docs/testing/verification-guide.md)를 따릅니다.

## API 문서

Swagger UI와 OpenAPI 문서는 보안을 위해 기본적으로 비활성화되며 `local` 프로파일에서만 활성화됩니다.

- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`

공통 응답은 다음 형식을 사용합니다.

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공했습니다.",
  "data": {}
}
```

## 프로젝트 문서

| 문서 | 내용 |
| --- | --- |
| [제품 요구사항](docs/product/DoctorPet-PRD.md) | 사용자 요구사항과 제품 범위 |
| [소프트웨어 아키텍처](docs/architecture/DoctorPet-SA.md) | ERD, API, 상태 머신, 핵심 설계 |
| [코드 컨벤션](docs/architecture/DoctorPet-코드컨벤션.md) | 패키지·클래스·코드 작성 규칙 |
| [도메인 정책](docs/domain/반려동물병원예약-정책정리본.md) | 예약·결제 등 상세 정책 |
| [고도화 현황](docs/enhancement/README.md) | MVP 이후 기능의 구현 범위와 상태 |
| [협업 규칙](docs/collaboration/github-rules.md) | 브랜치·커밋·PR 규칙 |
| [AI 작업 규칙](AGENTS.md) | AI 도구 공통 작업 기준 |

문서가 충돌하면 PRD, SA, 코드 컨벤션 순으로 적용합니다.

## 라이선스

현재 저장소에는 별도의 라이선스가 명시되어 있지 않습니다.
