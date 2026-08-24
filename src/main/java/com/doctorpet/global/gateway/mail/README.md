# EmailGateway (이메일 발송 게이트웨이)

이메일 인증(회원가입)·비밀번호 재설정(백로그 P2)에서 쓰는 이메일 발송 추상화다.
`PaymentGateway`와 같은 구조 — 도메인 서비스(`EmailVerificationService`/`PasswordResetService`)는
발송 수단을 몰라야 하므로, 제목·본문만 받는 얇은 인터페이스 뒤에 실제 구현을 숨긴다.

## 구성

| 파일 | 역할 |
| --- | --- |
| `EmailGateway` | 계약(인터페이스). `send(to, subject, body)` |
| `fake/FakeEmailGateway` | 로컬/테스트용. 실제 발송 없이 로그로만 남긴다 |
| `smtp/SmtpEmailGateway` | 실연동 구현체(운영). `JavaMailSender`로 실제 SMTP 발송 |
| `smtp/MailSmtpProperties` | SMTP host/port/username/password 설정 |
| `MailConfig`(`global/config`) | `mail.provider=smtp`일 때만 `JavaMailSender` 빈 등록 |

## 환경별 설정 · Secret 주입

- 빈 선택은 `mail.provider`로 한다. **미설정이면 어떤 발송기도 등록되지 않는다(fail-safe)** —
  `payment.gateway`와 동일한 이유(설정 누락이 조용히 "발송된 것처럼" 넘어가지 않게).
  - `fake` → `FakeEmailGateway`. 로컬/테스트 전용, 콘솔 로그로 인증·재설정 링크 확인.
  - `smtp` → `SmtpEmailGateway`. `mail.smtp.host`·`username`·`password` 필요, 커밋 금지.
- `mail.from`·`mail.verification.base-url`·`mail.password-reset.base-url`은 provider와 무관하게
  항상 필요하다(발송 여부와 무관하게 도메인 서비스가 링크를 만들 때 참조).

## 미확정 · 프론트 확정 시 갱신 필요

- **`mail.verification.base-url`**은 현재 백엔드 `GET /api/auth/verify-email` 엔드포인트를
  직접 가리킨다(프론트 미정 — 임시). 프론트가 생기면 프론트의 인증 완료 페이지 URL로 바꾸고,
  그 페이지가 이 API를 호출하는 구조로 전환해야 한다.
- **`mail.password-reset.base-url`**은 프론트의 재설정 페이지(새 비밀번호 입력 폼)를 가리켜야
  하므로 처음부터 프론트 URL을 가정했다(`http://localhost:3000/reset-password` — 임시값,
  실제 프론트 라우트 확정 시 갱신).
- 실제 SMTP 계정(Gmail 등)은 아직 준비되지 않아 로컬은 `mail.provider: fake`로 개발한다.
