# DoctorPet ALB + 수평 확장 설계 (미래 카드 — 설계만, 미구현)

| 항목 | 내용 |
| --- | --- |
| 문서 버전 | v1.1 (리뷰 반영 — SSE·STOMP fan-out 선행 구현 명시, AZ 분산 배치, deregistration 절차, idle timeout 수치 정정) |
| 작성 기준일 | 2026-08-18 |
| 상태 | **설계만 완료, 실제 인프라 미구현** — 아래 2절 트리거 조건 충족 전까지 운영 환경에는 반영하지 않는다 |
| 전제 | RDS(이슈 #163)·ElastiCache(이슈 #169)로 DB·Redis가 이미 EC2 밖으로 분리되어 있다는 걸 전제로 한다 |
| 관련 문서 | `docs/architecture/DoctorPet-무중단배포.md`(nginx blue/green, 이 문서가 다루는 단일 인스턴스 배포 무중단화. 3-5절에서 이 문서로 연결됨), `docs/architecture/DoctorPet-SA.md` |

이 문서는 "EC2 인스턴스 자체 장애(하드웨어·AZ 장애) 대비"와 "트래픽 증가에 따른 수평 확장"을 위한 ALB + 다중 EC2 설계다. `DoctorPet-무중단배포.md`가 푸는 문제("배포할 때 몇 초 끊긴다")와는 다른, 더 큰 목표라 별도 문서로 분리했다.

## 1. 왜 지금 안 하는가

실사용자 트래픽이 없는 포트폴리오/개발 단계에서 ALB + 2번째 EC2는 매달 확정적으로 나가는 비용(4절 참고)에 비해 얻는 이득(가용성 향상)이 당장 필요하지 않다고 판단했다. RDS Single-AZ·ElastiCache 단일 노드를 선택한 것과 같은 비용 우선 판단이다 — "몰라서 안 하는 것"이 아니라 "알고 있고 지금은 트리거 조건이 안 됐다"는 걸 이 문서로 남긴다.

## 2. 트리거 조건 (실제 구현에 들어가는 기준)

측정 없이 미리 확장하는 건 확정되지 않은 리스크에 비용을 미리 지불하는 셈이라 채택하지 않는다(`DoctorPet-무중단배포.md` 3-3절과 같은 원칙). 아래 중 하나라도 실측되면 그때 구현한다.

- 실제 트래픽이 PRD 성공 기준(P95 300ms, 100 RPS)에 근접하거나 초과하는 게 관측된다.
- 단일 EC2 장애(인스턴스 재기동, AZ 장애 등)로 실제 다운타임이 발생한 이력이 생긴다.
- 가용성 SLA를 요구하는 실사용자·계약이 생긴다.

## 3. 아키텍처 설계

### 3-1. 목표 구성도

```
인터넷 ── :443(TLS, ACM) ── [ALB] ──(Target Group, 헬스체크 GET /healthz)──┐
                                                                          ├── EC2 #1 [nginx :8080] ── app-blue/app-green
                                                                          └── EC2 #2 [nginx :8080] ── app-blue/app-green
                                                                                    │
                                                        ┌───────────────────────────┴───────────────────────────┐
                                                        RDS MySQL (Multi-AZ 검토, 현재 Single-AZ)      ElastiCache Valkey (현재 단일 노드)
```

핵심 결정: **ALB가 nginx를 대체하지 않는다.** 각 EC2 인스턴스는 지금 구축된 nginx blue/green을 그대로 유지하고, ALB는 그 앞에서 인스턴스 단위로만 라우팅한다. 이렇게 하면:

- 이미 검증된 컴포넌트(nginx blue/green, 배포 스크립트의 healthy 폴링·SHA 고정 등)를 재사용한다 — `DoctorPet-무중단배포.md` 2절의 "기존에 다져둔 안전장치는 그대로 재사용한다" 원칙과 같다.
- 인스턴스 하나가 배포 중이거나(내부적으로 blue/green 컷오버 중) 장애가 나도, ALB가 그 인스턴스를 헬스체크로 걸러내고 나머지 인스턴스로만 트래픽을 보낸다 — 무중단 배포가 인스턴스 이중화 덕분에 한 겹 더 안전해진다.

### 3-2. AWS 리소스 사양

- **ALB**: internet-facing Application Load Balancer. 리스너 443(HTTPS, ACM 인증서) + 80(443으로 리다이렉트). `DoctorPet-무중단배포.md` 3-1절이 "도메인·TLS는 범위 밖"으로 미뤄둔 부분을 이 설계가 다시 끌어온다 — ALB에 HTTPS 리스너를 달려면 도메인과 ACM 인증서가 선행 조건이다.
- **Target Group**: 프로토콜 HTTP, 포트 8080(각 인스턴스의 nginx), 헬스체크 경로 `/healthz`(무중단배포 문서가 이미 쓰는 readiness 헬스 그룹 — Dockerfile HEALTHCHECK와 동일 엔드포인트를 재사용).
- **EC2 2번째 인스턴스**: 기존과 동일 스펙(t3.micro 또는 t3.small)·동일 AMI 구성(docker-compose.yml, nginx blue/green 그대로 복제). 별도 인스턴스 타입 실험은 이 설계 범위 밖. **서로 다른 가용 영역(AZ)에 하나씩 배치한다(리뷰 지적 P1)** — 1절이 내세운 목표 중 하나가 "AZ 장애 대비"인데, 두 인스턴스를 같은 AZ에 두면 그 AZ 하나가 죽을 때 둘 다 같이 내려가 목표 자체가 성립하지 않는다. ALB 자체도 서로 다른 AZ의 서브넷을 최소 2개 요구하므로([AWS 문서](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/application-load-balancers.html)), "ALB용 AZ 2개 이상의 서브넷 + 각 AZ에 EC2 한 대씩"을 리소스 사양에 명시한다.
- **보안그룹 변경**: 지금 EC2가 8080을 인터넷에 직접 공개하고 있다면, ALB 도입 후에는 EC2 보안그룹의 8080 인바운드를 ALB 보안그룹에서만 허용하도록 좁힌다 — RDS `doctorpet-rds-sg`/ElastiCache `doctorpet-redis-sg`가 EC2 SG에서만 허용하는 것과 같은 원칙.
- **RDS·ElastiCache**: 이미 EC2 밖으로 분리돼 있어(이슈 #163, #169) 여러 인스턴스가 공유해도 추가 코드 변경이 필요 없다 — 단, 이건 DB·캐시 상태에 한정된 얘기다. **실시간 푸시(SSE·STOMP 채팅)는 이 범위 밖이고 별도 코드 작업이 필요하다 — 3-5절 참고(리뷰 지적 P1).** 다만 인스턴스를 이중화하면서 DB·캐시가 여전히 단일 장애점(Single-AZ, 복제본 0)으로 남는 비대칭이 생긴다 — 5절 잔존 위험 참고.

### 3-3. 배포 파이프라인 변경

지금 `deploy.yml`은 SSH 대상 EC2가 하나다. 인스턴스가 여럿이 되면 롤링 배포가 필요하다.

- **권장안**: GitHub Actions에서 인스턴스 목록을 순차 반복(matrix 또는 for 루프)해 한 번에 한 인스턴스씩 배포한다 — 기존 SSH·healthy 폴링·nginx blue/green 로직을 인스턴스당 그대로 재사용할 수 있어 코드 재작성이 최소화된다.
- **Target Group에 deregistration delay를 설정하는 것만으로는 draining이 되지 않는다(리뷰 지적 P2)** — 그건 "등록 해제됐을 때 얼마나 기다렸다가 완전히 뺄지"를 정하는 값일 뿐, 등록 해제 자체를 자동으로 트리거해주지 않는다. 배포 스크립트가 대상 인스턴스를 배포하기 **전에** 해당 인스턴스를 [`DeregisterTargets`](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/target-group-register-targets.html)로 명시적으로 등록 해제하고, drain이 끝날 때까지(또는 deregistration delay만큼) 기다린 뒤 배포를 진행하고, 배포·헬스체크가 끝나면 `RegisterTargets`로 다시 등록하는 3단계를 배포 파이프라인에 직접 넣어야 한다 — 이 순서 없이 deregistration delay 값만 설정하면 배포 중에도 새 요청이 그 인스턴스로 계속 들어간다.
- CodeDeploy/SSM Run Command로 전환하는 안도 검토했으나, 지금 프로젝트 규모 대비 학습곡선·설정 비용이 과해 제외한다(SA `DoctorPet-무중단배포.md` 3-5절이 ECS Fargate를 제외한 것과 같은 이유).

### 3-4. 세션·상태 공유, SSE·STOMP 연결

- **RDS·ElastiCache로 DB·캐시 상태는 이미 외부화돼 있어 어느 인스턴스가 요청을 받아도 같은 DB·Redis를 본다 — 여기까지는 sticky session이 필요 없다.** 다만 이건 요청-응답형 API에만 해당한다. **실시간 푸시(SSE 알림, STOMP 채팅)는 상태가 외부화돼 있지 않고, ALB 기본 라운드로빈에서 정상 사용 중에도 메시지 유실이 생긴다(리뷰 지적 P1) — 3-5절 참고.** sticky session을 켜도 이 문제는 해결되지 않는다(두 사용자가 서로 다른 인스턴스에 붙어 있는 채팅방이면 sticky session 자체가 성립하지 않는다).
- **SSE 연결(`/api/notifications/subscribe`) idle timeout 주의(리뷰 지적으로 표현 정정, 2차 지적으로 권장값 재정정)**: 실제 `SseEmitter` 타임아웃은 `SseEmitterRegistry.EMITTER_TIMEOUT_MS`로 30분(1800초)이고(이 문서 이전 버전이 "최대 3600초"라고 적었던 건 nginx `proxy_read_timeout 3600s`와 혼동한 오기였다 — 정정), 앱은 이미 15초 간격으로 heartbeat(`SseEmitterRegistry.HEARTBEAT_INTERVAL_SEC = 15`, `comment("ping")`)를 보낸다. ALB idle timeout(기본 60초)은 "연결 총수명"이 아니라 "마지막 통신 이후 아무 데이터도 안 오가면 끊는다"는 기준이므로, 권장값도 **연결 총수명(1800초)이 아니라 heartbeat 주기(15초)를 기준으로 잡아야 한다** — 60~120초처럼 heartbeat 주기보다 충분히 큰 값이면 정상 heartbeat는 여유 있게 통과시키면서도, heartbeat가 끊긴 죽은 연결을 오래 안 붙잡는다. emitter 타임아웃(1800초)에 맞춰 idle timeout을 그만큼 크게 잡으면 heartbeat가 멈춘 죽은 연결도 최대 30분간 살아 있는 것처럼 유지될 수 있어 오히려 부정확하다. 정확한 값은 실제 연결 유지 테스트(heartbeat가 정상 통과되는지, 의도적으로 heartbeat를 끊었을 때 예상한 시간 안에 연결이 정리되는지)로 검증한다.
- 컷오버 순간의 SSE 재연결(무중단배포 문서 7절의 "알려진 한계")은 인스턴스 이중화 이후에도 동일하게 남는다 — ALB는 이 문제를 해결해주지 않는다.

### 3-5. 실시간 푸시(SSE·STOMP 채팅) 다중 인스턴스 fan-out — 이 설계의 선행 구현 항목(리뷰 지적 P1)

**이 절이 다루는 문제가 해결되기 전에는 ALB 라운드로빈으로 실제 트래픽을 흘려서는 안 된다.** DB·Redis 상태 외부화(3-4절)와 별개로, 이 앱의 실시간 푸시 두 채널은 모두 인스턴스 로컬 메모리에만 상태를 들고 있다:

- **SSE 알림**: `SseEmitterRegistry`(`emittersByMember`)가 `ConcurrentHashMap`으로 각 인스턴스의 JVM 메모리 안에서만 연결을 들고 있다. 알림 생성은 `NotificationService`가 Spring `ApplicationEventPublisher`로 이벤트를 발행하고 `NotificationPushListener`가 같은 JVM 안에서만 구독하는 구조라, EC2 #2가 처리한 요청으로 생긴 알림은 EC2 #1에 붙어 있는 수신자의 SSE 연결로 절대 전달되지 않는다. 코드에도 이미 "단일 인스턴스 기준이며, 다중 인스턴스 팬아웃(Redis pub/sub)은 수평 확장 시 후속으로 둔다"는 주석이 있다(`SseEmitterRegistry` 클래스 주석, `SseNotificationPusher` 알려진 한계 주석).
- **STOMP 채팅**: `ChatWebSocketConfig`가 `registry.enableSimpleBroker("/topic", "/queue")`로 Spring `SimpleBroker`를 쓴다 — 구독 정보를 인스턴스 로컬 메모리로만 관리하고 외부 브로커로 relay하지 않는다(`configureClientInboundChannel`의 코드 주석도 "단일 인스턴스 SimpleBroker 채팅 범위"라고 명시). 채팅방의 두 사용자가 ALB 라운드로빈으로 서로 다른 인스턴스에 붙으면, 한쪽이 보낸 메시지가 다른 쪽에게 전달되지 않는다 — sticky session으로도 해결 안 된다(둘이 같은 인스턴스에 붙게 강제할 방법이 없다).

**필요한 작업(트리거 조건 충족 후, ALB를 실제로 트래픽에 붙이기 전에 먼저 구현·검증)**:

- SSE: Redis pub/sub(또는 Streams)로 각 인스턴스가 발행되는 알림 이벤트를 구독해, 자기 인스턴스에 로컬로 붙어 있는 수신자에게만 relay하도록 `SseNotificationPusher`/`NotificationPushListener` 경로를 확장한다.
- STOMP 채팅: `enableSimpleBroker` 대신 외부 브로커(RabbitMQ 등)로 relay하는 `enableStompBrokerRelay`로 전환하거나, SSE와 동일하게 Redis pub/sub으로 각 인스턴스의 로컬 `SimpleBroker`에 메시지를 재발행하는 방식 중 하나를 택한다.
- 두 작업 모두 로컬에서 2개 앱 인스턴스(`COMPOSE_PROFILES=blue,green` 등으로 동시 기동)를 띄워, 한쪽에서 발생한 이벤트가 다른 쪽에 붙은 클라이언트에게 정상 전달되는지 직접 확인한다 — 이 검증 없이는 "정상 사용 중에도 조용히 메시지가 씹히는" 문제가 프로덕션에서만 드러난다.

## 4. 비용 추정 (ap-northeast-2/Seoul, 2026-08 기준 — 실제 구현 시 반드시 재확인)

| 항목 | 추정 월 비용 |
| --- | --- |
| EC2 2번째 인스턴스 (t3.micro) | 약 $9.5 |
| EC2 2번째 인스턴스 (t3.small, 대안) | 약 $19.0 |
| ALB 기본 시간 요금 | 약 $18~20 (us-east-1 기준 $16.4, Seoul이 대체로 10~20% 높음) |
| ALB LCU(트래픽 비례) | 낮은 트래픽에서는 미미 |
| 도메인 등록 (연 단위, 예: Route 53 .com) | 약 $12~15/년 (ACM 인증서 자체는 무료) |
| **총 추가 비용(대략)** | **월 $30~40대** |

기존 단일 EC2(t3.micro, 약 $9.5/월)에 이만큼이 그대로 더해진다 — 2절의 트리거 조건이 실측되기 전까지 이 비용을 미리 낼 이유가 없다는 게 1절 판단의 근거다.

## 5. 잔존 위험 / 이 설계로 해결되지 않는 것

- **SSE·STOMP 채팅의 다중 인스턴스 fan-out은 "잔존 위험"이 아니라 선행 구현 항목이다(리뷰 지적 P1, 3-5절)** — 나머지 항목들과 달리 "일단 켜두고 나중에 보완"이 불가능하다. 구현 전에 ALB를 라운드로빈으로 트래픽에 붙이면 정상 사용 중에도 조용히 메시지가 유실된다.
- **RDS Single-AZ, ElastiCache 단일 노드는 이 설계로 해결되지 않는다.** EC2 인스턴스 이중화와 DB·캐시 이중화는 별개 문제다 — 인스턴스를 2대로 늘려도 RDS·ElastiCache가 여전히 단일 장애점이면 "이중화"의 의미가 반감된다. Multi-AZ RDS·ElastiCache 복제본 추가는 각각 비용이 배로 들어(RDS PR·ElastiCache PR에서 이미 같은 이유로 보류한 결정과 동일) 이 문서에서는 언급만 하고 별도 트리거로 남긴다.
- 도메인·TLS 준비가 선행 조건이다 — 지금 프로젝트에 도메인이 없다.
- 배포 파이프라인 복잡도가 늘어난다(단일 SSH → 롤링 배포) — 구현 시 실제로 한 인스턴스씩 순서대로 배포되는지, 배포 중 요청이 끊기지 않는지 별도 검증이 필요하다.

## 6. 구현 체크리스트 (트리거 조건 충족 시)

- [ ] **(선행, 다른 항목보다 먼저) SSE Redis pub/sub fan-out 구현 — 3-5절.** 2개 인스턴스 동시 기동해 인스턴스 간 알림 전달 직접 검증.
- [ ] **(선행, 다른 항목보다 먼저) STOMP 채팅 fan-out(외부 브로커 relay 또는 Redis pub/sub) 구현 — 3-5절.** 2개 인스턴스 동시 기동해 인스턴스 간 채팅 메시지 전달 직접 검증.
- [ ] 도메인 확보 + ACM 인증서 발급 (ALB HTTPS 리스너 선행 조건)
- [ ] ALB + Target Group 생성(서로 다른 AZ의 서브넷 2개 이상), 헬스체크를 `/healthz`로 설정
- [ ] 2번째 EC2 인스턴스를 1번째와 다른 AZ에 프로비저닝 (기존과 동일 구성 복제, 3-2절)
- [ ] EC2 보안그룹을 ALB 보안그룹에서만 8080 인바운드를 허용하도록 변경 (직접 인터넷 노출 제거)
- [ ] `deploy.yml`을 다중 인스턴스 롤링 배포로 확장 — 배포 전 `DeregisterTargets` 호출·drain 대기, 배포·헬스체크 후 `RegisterTargets`로 재등록하는 순서를 스크립트에 직접 구현(3-3절, deregistration delay 설정만으로는 부족)
- [ ] ALB idle timeout을 heartbeat(15초) 기준 60~120초대로 설정하고, 값은 실제 연결 유지 테스트로 검증 (3-4절 — 연결 총수명이 아니라 heartbeat 주기 기준, emitter 타임아웃 1800초에 맞추지 않는다)
- [ ] (별도 결정) RDS Multi-AZ, ElastiCache 복제본 추가 여부
