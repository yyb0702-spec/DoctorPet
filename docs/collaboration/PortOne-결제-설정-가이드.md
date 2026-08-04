# PortOne 결제 설정 가이드 (로컬·배포)

> 팀원이 pull 받은 뒤 결제를 켜는 방법. **코드는 커밋되지만 시크릿은 커밋되지 않는다**(보안). 이 문서엔 실제 값·시크릿을 적지 않는다 — "무엇을, 어디서 받아, 어디에 넣는지"만 정리한다.

## 0. 왜 pull 받으면 결제가 바로 안 되나

- 결제 코드(PortOne 게이트웨이·웹훅 등)는 저장소에 있다.
- 시크릿·상점 설정은 `application-local.yml`(= `.gitignore` 대상)에만 있어 **커밋되지 않는다.** 그래서 pull 하면 "코드는 있지만 설정은 없는" 상태다 → 의도된 보안 동작.
- **`payment.gateway: fake`면 시크릿 없이도 결제 흐름 개발이 된다**(승인이 가짜 `PAID` 반환). 실제 PortOne 결제·웹훅을 돌릴 때만 아래 설정이 필요하다.

## 1. 로컬 설정

1. `src/main/resources/application-local.yml`이 없으면 `application-local.yml.example`을 복사해 만든다.
2. 아래 표대로 값을 채운다.
3. 실제 PortOne 결제를 테스트할 때만 `payment.gateway: portone`으로 바꾼다(평소 개발은 `fake` 유지).

### 채울 값

| 키 | 비밀? | 어디서 받나 | 비고 |
| --- | --- | --- | --- |
| `payment.gateway` | — | 직접 선택 | 개발=`fake`, 실 PortOne 테스트=`portone` |
| `payment.portone.base-url` | 아니오 | 고정값 | `https://api.portone.io` |
| `payment.portone.store-id` | 아니오 | 콘솔 → 연동 정보 상단 상점아이디(`store-...`), 또는 팀원 | 팀 공용 테스트 상점 |
| `payment.portone.channel-key` | 아니오 | 콘솔 → 연동 정보 → 채널 관리 → **결제(정기결제) 채널**의 채널 키(`channel-key-...`) | 빌링키 결제 대상 채널 |
| `payment.portone.api-secret` | **예** | 콘솔 → 식별코드·API Keys → **V2 API** 발급, 또는 팀 시크릿 저장소 | **커밋 금지** |
| `payment.portone.webhook-secret` | **예** | 콘솔 → 결제알림(Webhook) → **결제모듈 V2** → 시크릿 발급, 또는 팀 시크릿 | **커밋 금지** (#48) |
| `payment.billing-key.enc-key` | **예** | 팀 시크릿 저장소 | **환경 안에서 하나로 고정**(아래 §4) |
| `jwt.secret` | **예** | 팀 시크릿 저장소 | 커밋 금지 |

> 비밀 값(**예**)은 git·PR·이슈·로그·메신저에 남기지 않는다. 팀 비밀번호 관리자(1Password 등) 같은 안전한 채널로만 공유한다. 노출되면 콘솔에서 즉시 재발급(rotate).

## 2. 실제 결제(빌링키 승인) 테스트 흐름

1. **빌링키 발급** — PortOne 결제창(정기결제)에서 **테스트 카드**를 입력해 빌링키를 받는다(KG이니시스 테스트 카드). 카드 입력은 사람이 직접 한다.
2. 발급된 빌링키를 `POST /api/payment-methods`로 등록(보호자 JWT).
3. 예약(진료 완료 상태)·병원 스태프 계정을 준비한 뒤 청구 → PortOne 승인 → `PAID`.

## 3. 웹훅 테스트 (로컬)

1. `ngrok http 8080` (ngrok 무료 계정 + authtoken 필요: `ngrok config add-authtoken <토큰>`).
2. 콘솔 → 결제알림(Webhook) → **결제모듈 V2** → 설정 모드 **테스트** → 웹훅 URL `https://<ngrok>/api/payments/webhook` 저장 → **웹훅 시크릿 발급** → `payment.portone.webhook-secret`에 넣기.
3. **서명 방식은 Standard Webhooks(V2)** — `webhook-id/timestamp/signature`, `whsec_` 시크릿. **V1 아님.**
4. ⚠️ 웹훅 URL은 **테스트 상점당 하나**다. 여러 명이 각자 ngrok로 동시에 등록하면 서로 덮어쓴다 → 개인 로컬 웹훅 테스트는 **한 번에 한 명**, 또는 공용 스테이징에서.
5. ngrok 무료 URL은 재시작마다 바뀐다. **고정 도메인**(계정당 1개 무료)을 만들어 `ngrok http --url=<고정도메인> 8080`으로 띄우면 URL이 안 바뀌어 웹훅 URL 재등록이 불필요하다.

## 4. 배포 (스테이징·운영)

- 로컬 파일이 아니라 **서버 `.env`(또는 GitHub Actions Secrets)**에 시크릿을 주입한다. `docker-compose.yml`이 `.env`를 읽고, Spring이 환경변수를 완화 바인딩으로 매핑한다(`PAYMENT_PORTONE_API_SECRET` → `payment.portone.api-secret` 등).
- 주입할 것: `PAYMENT_PORTONE_API_SECRET`, `PAYMENT_PORTONE_STORE_ID`, `PAYMENT_PORTONE_CHANNEL_KEY`, `PAYMENT_PORTONE_WEBHOOK_SECRET`, `PAYMENT_BILLING_KEY_ENC_KEY`, `JWT_SECRET`, 그리고 `PAYMENT_GATEWAY=portone`.
- 웹훅은 개인 ngrok 대신 **스테이징 고정 도메인**을 콘솔에 등록해 팀 공용으로 쓴다.

## 5. 주의 — enc-key 일관성

빌링키는 `payment.billing-key.enc-key`로 암호화해 저장한다. **A 키로 저장한 빌링키는 B 키로 복호화되지 않는다.** 따라서 한 환경(예: 운영 서버)에서는 **enc-key를 하나로 고정**해야 청구 시 복호화가 된다. 로컬은 각자 DB라 각자 키여도 무방하다.

## 6. 보안 체크리스트

- [ ] `application-local.yml`·`.env`가 `.gitignore`에 있는지 확인(커밋 금지).
- [ ] 시크릿을 PR 본문·이슈·로그·메신저에 붙이지 않았는지.
- [ ] 시크릿이 노출됐다면 콘솔에서 즉시 재발급.
- [ ] 표시·로그는 카드 `brand`·`last4`만(원본 카드번호·빌링키 금지).
