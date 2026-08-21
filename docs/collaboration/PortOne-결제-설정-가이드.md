# PortOne 결제 설정 가이드 (로컬·배포)

> 팀원이 pull 받은 뒤 결제를 켜는 방법. **코드는 커밋되지만 시크릿은 커밋되지 않는다**(보안). 이 문서엔 실제 값·시크릿을 적지 않는다 — "무엇을, 어디서 받아, 어디에 넣는지"만 정리한다.

## 0. 왜 pull 받으면 결제가 바로 안 되나

- 결제 코드(PortOne 게이트웨이·웹훅 등)는 저장소에 있다.
- 시크릿·상점 설정은 `application-local.yml`(= `.gitignore` 대상)에만 있어 **커밋되지 않는다.** 그래서 pull 하면 "코드는 있지만 설정은 없는" 상태다 → 의도된 보안 동작.
- **`payment.gateway: fake`면 시크릿 없이도 결제 흐름 개발이 된다**(승인이 가짜 `PAID` 반환). 실제 PortOne 결제·웹훅을 돌릴 때만 아래 설정이 필요하다.

> **원칙: 로컬은 `fake`, 실 결제 확인은 스테이징.** 그러면 PortOne 시크릿(api-secret·webhook-secret 등)을 **팀원에게 개인별로 공유할 필요가 없다** — 로컬 개발자는 시크릿이 없어도 되고(§0), 실제 승인은 GitHub Actions Secrets로 값을 받는 배포 서버에서 확인한다(§4). 개인 노트북에서 굳이 실 PortOne을 켜는 경우에만 그 사람이 안전 채널로 시크릿을 개인적으로 받는다.

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
| `payment.portone.billing-key-result-url` | 아니오 | 프런트 배포 주소 | 모바일 콜백 완료 뒤 이동할 `/payment-methods` 주소(빌링키 원문 없음) |
| `payment.portone.api-secret` | **예** | 콘솔 → 식별코드·API Keys → **V2 API** 발급, 또는 팀 시크릿 저장소 | **커밋 금지** |
| `payment.portone.webhook-secret` | **예** | 콘솔 → 결제알림(Webhook) → **결제모듈 V2** → 시크릿 발급, 또는 팀 시크릿 | **커밋 금지** (#48) |
| `payment.billing-key.enc-key` | **예** | 팀 시크릿 저장소 | **환경 안에서 하나로 고정**(아래 §4) |
| `jwt.secret` | **예** | 팀 시크릿 저장소 | 커밋 금지 |

> 비밀 값(**예**)은 git·PR·이슈·로그·메신저에 남기지 않는다. 팀 비밀번호 관리자(1Password 등) 같은 안전한 채널로만 공유한다. 노출되면 콘솔에서 즉시 재발급(rotate).

## 2. 실제 결제(빌링키 승인) 테스트 흐름

1. **빌링키 발급** — PortOne 결제창(정기결제)에서 **테스트 카드**를 입력해 빌링키를 받는다(KG이니시스 테스트 카드). 카드 입력은 사람이 직접 한다.
2. 프런트가 보호자 JWT로 `POST /api/payment-methods/billing-key-issues`를 호출해 서버 1회성 `issueId`·콜백 주소를 받는다.
3. PC iframe 결과는 인증된 `POST /api/payment-methods/billing-key-issues/{issueId}/complete` body로 보내고, 모바일은 PortOne이 서버 콜백으로 보낸다. 서버가 PortOne 조회의 `merchantId`와 `issueId`를 대조한 뒤에만 저장한다. **빌링키를 프런트 URL·로그·수동 POST에 넣지 않는다.**
4. 예약(진료 완료 상태)·병원 스태프 계정을 준비한 뒤 청구 → PortOne 승인 → `PAID`.

## 3. 웹훅 테스트 (로컬)

1. `ngrok http 8080` (ngrok 무료 계정 + authtoken 필요: `ngrok config add-authtoken <토큰>`).
2. 콘솔 → 결제알림(Webhook) → **결제모듈 V2** → 설정 모드 **테스트** → 웹훅 URL `https://<ngrok>/api/payments/webhook` 저장 → **웹훅 시크릿 발급** → `payment.portone.webhook-secret`에 넣기.
3. **서명 방식은 Standard Webhooks(V2)** — `webhook-id/timestamp/signature`, `whsec_` 시크릿. **V1 아님.**
4. ⚠️ 웹훅 URL은 **테스트 상점당 하나**다. 여러 명이 각자 ngrok로 동시에 등록하면 서로 덮어쓴다 → 개인 로컬 웹훅 테스트는 **한 번에 한 명**, 또는 공용 스테이징에서.
5. ngrok 무료 URL은 재시작마다 바뀐다. **고정 도메인**(계정당 1개 무료)을 만들어 `ngrok http --url=<고정도메인> 8080`으로 띄우면 URL이 안 바뀌어 웹훅 URL 재등록이 불필요하다.

## 4. 배포 (스테이징·운영)

**PortOne 비밀값은 GitHub Actions Secrets에 등록하면 팀원에게 로컬로 공유하지 않아도 된다.** 배포 워크플로가 배포 시점에만 값을 꺼내 서버 컨테이너에 주입하므로, 서버 `.env` 파일에 시크릿을 적어 돌려볼 필요도 없다.

- **어디에 넣나** — 리포지토리 Settings → Secrets and variables → Actions → *New repository secret*. 아래 이름 그대로 등록한다. (시크릿 값 입력은 GitHub UI에서 **사람이 직접** 한다.)
  - `PAYMENT_PORTONE_API_SECRET`, `PAYMENT_PORTONE_STORE_ID`, `PAYMENT_PORTONE_CHANNEL_KEY`, `PAYMENT_PORTONE_WEBHOOK_SECRET`
- **어떻게 전달되나** — `deploy.yml`의 SSH 스텝이 이 Secrets를 EC2 세션의 셸 환경으로 주입하고(`envs:`), `docker-compose.yml`의 `app.environment`가 그 값을 `${...}`로 보간해 컨테이너에 넣는다. `environment` 블록은 `env_file(.env)`보다 우선하므로 **서버 `.env`에 값을 남기지 않아도** 컨테이너가 값을 받는다. Spring은 완화 바인딩으로 매핑한다(`PAYMENT_PORTONE_API_SECRET` → `payment.portone.api-secret` 등).
- **비파괴** — Secrets 미등록이거나 서버 `.env`의 `PAYMENT_GATEWAY=fake`이면 빈 값으로 무시되어 현재 배포를 깨지 않는다. 실연동 전환은 서버 `.env`에서 `PAYMENT_GATEWAY=portone`·`SPRING_PROFILES_ACTIVE=prod`로 **별도로 결정**한다(그 전까지 시크릿을 등록해두기만 해도 안전하다).
- **enc-key·JWT** — `PAYMENT_BILLING_KEY_ENC_KEY`·`JWT_SECRET`도 같은 방식(Actions Secrets 또는 서버 `.env`)으로 주입한다. enc-key는 환경 안에서 하나로 고정한다(§5).
- 웹훅은 개인 ngrok 대신 **스테이징 고정 도메인**을 콘솔에 등록해 팀 공용으로 쓴다.

## 5. 주의 — enc-key 일관성

빌링키는 `payment.billing-key.enc-key`로 암호화해 저장한다. **A 키로 저장한 빌링키는 B 키로 복호화되지 않는다.** 따라서 한 환경(예: 운영 서버)에서는 **enc-key를 하나로 고정**해야 청구 시 복호화가 된다. 로컬은 각자 DB라 각자 키여도 무방하다.

## 6. 보안 체크리스트

- [ ] `application-local.yml`·`.env`가 `.gitignore`에 있는지 확인(커밋 금지).
- [ ] 시크릿을 PR 본문·이슈·로그·메신저에 붙이지 않았는지.
- [ ] 시크릿이 노출됐다면 콘솔에서 즉시 재발급.
- [ ] 표시·로그는 카드 `brand`·`last4`만(원본 카드번호·빌링키 금지).
