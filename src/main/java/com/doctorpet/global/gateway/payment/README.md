# PaymentGateway (결제 게이트웨이)

애플리케이션 결제 로직과 PortOne V2 연동을 분리하는 추상화다(SA §3·§9-4, 이슈 #38).
결제 상태 전이·재시도 루프·금액 검증은 이 계층이 아니라 **상위 결제 서비스**의 책임이다.
게이트웨이는 외부 호출을 감싸 도메인 중립 DTO로 변환하고, 실패를 **재시도 성격으로 분류**해 던진다.

## 구성

| 파일 | 역할 |
| --- | --- |
| `PaymentGateway` | 계약(인터페이스). 발급 검증·승인·조회 + 취소/환불 확장 지점(`default` 미지원 예외) |
| `dto/*` | 도메인 중립 요청/응답(record). 빌링키·카드 원본은 담지 않는다 |
| `GatewayPaymentStatus` | 공급자 중립 상태 (PAID / PENDING / FAILED) |
| `GatewayFailureReason` | 실패 재시도 성격 (RETRIABLE / NON_RETRIABLE / UNKNOWN) |
| `PaymentGatewayException` | 실패 사유 + 공급자 코드 전달 |
| `support/SensitiveDataMasker` | 로그용 API 키·빌링키·카드번호 마스킹 |
| `portone/PortOneErrorCodeMapper` | PortOne 오류 코드·HTTP 상태 → `GatewayFailureReason` |
| `portone/PortOnePaymentGateway` | 실연동 구현체(운영). 실 요청/응답 바인딩은 실연동 시 연결 |
| `portone/PortOneProperties` | 타임아웃·재시도·인증정보 설정 |
| `fake/FakePaymentGateway` | 로컬/테스트용. 성공·실패·미확정 시나리오 주입 |

## 환경별 설정 · Secret 주입

- 설정 prefix: `payment.portone` (`PortOneProperties`). 예시는 `application-local.yml.example` 참고.
- **인증정보(`api-secret`·`store-id`)는 코드·문서·로그에 남기지 않는다**(보안 규칙). `application-local.yml`(gitignore) 또는 환경변수/시크릿 매니저로 주입한다.
- 빈 선택은 `payment.gateway`로 한다. **미설정이면 어떤 게이트웨이도 등록되지 않는다(fail-safe)** — 운영 설정 누락이 곧 미청구 결제 성공으로 이어지지 않게 하기 위함이다.
  - `fake` → `FakePaymentGateway`. `local`/`test`에서만 쓰고 운영에 설정 금지(`approve`가 PAID 반환).
  - `portone` → `PortOnePaymentGateway`. `base-url`·`api-secret`·`store-id` 미설정 시 생성 시점에 실패(오설정 조기 감지).
- 타임아웃·재시도 수치는 설정값이라 배포 없이 조정한다: `connect-timeout-ms`(기본 2000), `read-timeout-ms`(기본 5000), `max-retry`(기본 3, SA §9-4), `backoff-initial-ms`(기본 500).

## 미확정 · 실연동 시 확정 (SA 부록 A / 이슈 후속)

- **PortOne V2 실제 엔드포인트·인증 헤더·요청/응답 JSON 필드 바인딩** — 인증정보·엔드포인트 확정 후 `PortOnePaymentGateway`에 연결. 그 전까지 호출은 미지원 예외로 경계를 알린다(추측 페이로드 금지).
- **오류 코드 표** — `PortOneErrorCodeMapper`의 코드 문자열은 대표값이며 실연동 시 PortOne 문서로 보강. 미인식 코드는 `UNKNOWN`으로 안전 분류(단건 조회 우선).
- 재시도 루프·상태 전이(`OFFLINE_REQUIRED` 확정)·금액 검증은 후속 결제 서비스 이슈(#34 청구·#35 정산 스케줄러)에서 구현한다.
