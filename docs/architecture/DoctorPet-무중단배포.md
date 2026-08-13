# DoctorPet 무중단 배포 설계 (nginx blue/green)

| 항목 | 내용 |
| --- | --- |
| 문서 버전 | v1.0 |
| 작성 기준일 | 2026-08-10 |
| 상태 | 설계 확정, 구현 진행 중 |
| 전제(동결 baseline) | `docker-compose.yml`, `.github/workflows/deploy.yml` (2026-08-10 시점) |
| 관련 문서 | `docs/architecture/DoctorPet-SA.md`(제품 도메인 설계, 이 문서와 별개 — 배포 인프라는 SA 범위 밖) |

이 문서는 배포 인프라 설계 전용 문서다. 제품 도메인(회원·예약·결제 등) SA와 델타 관계가 아니라, 처음부터 별도 레이어라 SA 부록A `[결정 필요]`에 넣지 않고 독립 문서로 둔다.

---

## 1. 문제 정의

현재 배포(`deploy.yml`)는 EC2에서 `docker compose pull app` → `docker compose up -d --remove-orphans`로 **단일 `app` 컨테이너를 그 자리에서 교체**한다. 새 컨테이너가 healthy가 될 때까지 기존 컨테이너는 이미 내려간 뒤이므로, 교체 구간 동안 8080 포트로 들어오는 요청이 실패한다(연결 거부 또는 짧은 502).

문제의 범위는 **딱 이것 하나**다 — "배포할 때 몇 초~1분 끊긴다." 그 외 목표(EC2 인스턴스 자체 장애 대비, DB 이중화 등)는 별개 문제로 아래 3절에서 명시적으로 범위 밖에 둔다.

## 2. 설계 목표

- 코드 배포 시 8080 포트로 들어오는 요청이 끊기지 않아야 한다.
- 배포 실패(새 버전이 unhealthy) 시 운영 중인 서비스에 영향이 없어야 한다 — 현재는 "새 이미지 실패 → 이전 SHA로 재배포"라는 능동적 롤백이 필요하지만, 이 설계에서는 "새 색이 실패하면 기존 색을 건드리지 않고 그대로 버린다"로 더 단순·안전해진다.
- 기존에 이미 단단하게 다져둔 안전장치(healthy 폴링, SHA 고정, 배포 SHA 역전 방지, 러너 IP 임시 허용/회수, 동시 배포 직렬화)는 그대로 재사용한다 — 이번 변경은 "무엇을 교체 대상으로 삼는가"만 바꾼다.

## 3. 범위 결정 (완료된 논의 요약)

여러 대안을 검토하고 아래처럼 확정했다. 각 결정은 "지금 실제로 겪는 문제만 정확히 풀고, 그 이상은 나중 결정으로 미룬다"는 같은 원칙을 따른다.

### 3-1. nginx 범위 — 포트 스위치만 (도메인·HTTPS 제외)

nginx는 8080 포트 뒤에서 app-blue/app-green 중 활성 쪽으로 리버스 프록시하는 역할만 한다. 외부에서 보이는 접속 방식(EC2 IP:8080)은 바꾸지 않는다. 도메인 연결·TLS 종료(Let's Encrypt/certbot)는 완전히 별개 결정으로 미룬다 — 지금 도메인도 인증서도 없는 상태에서 같이 묶으면 무중단 배포라는 원래 목표가 늦어진다. nginx를 진입점으로 미리 만들어두면, 나중에 도메인·인증서를 붙일 때 `server_name`과 `listen 443 ssl`만 추가하면 되므로 이번 작업이 다음 단계를 오히려 쉽게 만든다.

### 3-2. MySQL/Redis — 이번 범위에서 제외

`deploy.yml`은 `docker compose pull app` + `up -d`로 **app 서비스만** 갱신한다. mysql·redis는 이미지·설정이 안 바뀌는 한 이 과정에서 재시작되지 않는다 — 즉 지금도 정상적인 코드 배포에서 mysql·redis 다운타임은 발생하지 않는다. DB 계층 자체가 끊기는 경우는 DB 버전 업그레이드처럼 별도 이벤트이며, 이를 무중단으로 만들려면 RDS Multi-AZ·ElastiCache 같은 관리형 이중화가 필요하다(3-4절 참고, 이번 범위 아님).

### 3-3. EC2 인스턴스 2대 분리 (앱/DB) — 지금은 보류

당초 검토안이었으나, 다음 순서로 재검토하기로 했다:

1. **1단계(이번 작업)**: 같은 EC2 안에서 nginx + blue/green. 배포 중 발생할 수 있는 자원 경합(blue/green 컨테이너가 짧게 동시 구동되며 mysql·redis와 CPU/메모리를 다투는 것)은 배포 순간에만 나타나는 일시적 리스크이고, 이 프로젝트의 트래픽 목표(PRD 성공 기준 P95 300ms, 100 RPS)를 감안하면 인스턴스에 적당한 여유만 있으면 체감될 가능성이 낮다.
2. **관찰**: 1단계 배포 후 배포 중 CPU·메모리 사용률, mysql 커넥션 풀 대기시간 같은 지표를 관찰한다.
3. **필요 시에만 2단계**: 실제로 경합이 관측되면(응답 지연 증가, 커넥션 에러 등) 그때 앱/DB EC2 분리를 검토한다. 분리 시 mysql·redis 호스트 포트를 열어야 하지만, `0.0.0.0` 전체 공개가 아니라 보안그룹을 앱 인스턴스의 SG ID로만 스코프하면 지금의 "호스트 포트 비공개" 원칙(리뷰 지적 P2)을 훼손하지 않는다.

측정 없이 미리 인스턴스를 늘리는 건 확정되지 않은 리스크에 비용을 미리 지불하는 셈이라 채택하지 않는다.

### 3-4. RDS/ElastiCache 이전 — 미래 카드로 보류

DB 운영 부담(패치·백업·장애 복구)을 직접 지는 게 부담스러워지는 시점(서비스 확대, 팀 리소스 부족 등)에 재검토할 카드로 남긴다. 지금 단계에서 "무중단 배포" 하나를 위해 시작하기엔 비용·마이그레이션 부담이 목표 대비 과하다.

### 3-5. 두 번째 EC2로 앱을 완전 이중화(ALB 등) / ECS Fargate — 제외

인스턴스 자체 장애(하드웨어·AZ 장애) 대비는 이번 목표("배포 중 다운타임 제거")와 다른, 더 큰 목표라 이번 범위에서 같이 결정하지 않는다. ECS Fargate + ALB + CodeDeploy blue/green은 정석에 가깝지만 지금 프로젝트 규모 대비 비용·학습곡선이 과해 제외한다.

## 4. 아키텍처

### 4-1. 현재

```
인터넷 ── :8080 ── [app 컨테이너] ── mysql/redis (호스트 포트 비공개, compose 네트워크 내부)
```

`docker compose up -d`가 app 컨테이너를 교체하는 동안 8080이 비어 있는 구간이 생긴다.

### 4-2. 변경 후

```
인터넷 ── :8080 ── [nginx] ──(활성 색만)── [app-blue] ┐
                                        [app-green] ┘── mysql/redis
```

- `nginx`가 호스트 포트 8080을 갖는다(기존 `app`이 갖던 자리를 대체).
- `app-blue`/`app-green`은 mysql/redis와 동일한 원칙으로 호스트 포트를 게시하지 않는다 — nginx만 내부 compose 네트워크로 접근한다.
- 정상 운영 중에는 **활성 색 하나만 컨테이너가 떠 있다**(비활성 색은 정지 상태) — 두 색을 상시 동시에 띄우는 구조가 아니다. 배포하는 짧은 구간에만 두 색이 동시에 뜬다(신규 색 헬스체크 통과까지).

### 4-3. 컷오버 메커니즘

nginx가 프록시할 대상은 별도 include 파일(`nginx/conf.d/upstream-active.conf`)의 `upstream` 블록 하나로 결정한다.

```nginx
# nginx/conf.d/upstream-active.conf (배포 스크립트가 매 배포마다 덮어쓴다 — git 비추적)
upstream app_upstream {
    server app-blue:8080;   # 또는 app-green:8080
}
```

컷오버 절차:

1. 비활성 색(target)에 새 이미지로 컨테이너를 띄운다(`--no-deps`로 mysql/redis는 건드리지 않는다).
2. target이 healthy가 될 때까지 폴링한다(기존 로직 재사용).
3. healthy면 `upstream-active.conf`를 target 색으로 다시 쓰고 `nginx -s reload`한다. reload는 그레이스풀해서 이미 연결된 요청은 기존 업스트림(active였던 색)으로 마저 처리되고, 새 연결부터 target으로 붙는다 — 이 지점이 실제 "무중단 전환"이다.
4. **reload 성공 직후** `.active_color`, `.last_deployed_sha`를 갱신한다(리뷰 지적, PR #136 — 예전엔 이전 색 정지 다음에 갱신했는데, 정지가 실패해 스크립트가 일찍 끝나면 실제 활성 색과 상태 파일이 어긋난 채 남는 문제가 있었다). 백업 파일 삭제·옛 단일 app 컨테이너 최종 제거처럼 실패해도 무방한 후처리는 상태 기록 *뒤*로 미룬다.
5. 짧은 드레인 대기(5초) 후 이전 색(old) 컨테이너를 정지한다(`stop`, `rm`이 아님 — 다음 배포 때 재사용). 이 5초 자체는 안전장치가 아니다 — 실제 드레인 보장은 애플리케이션의 `server.shutdown: graceful`(`spring.lifecycle.timeout-per-shutdown-phase` 30초)과 `docker-compose.yml`의 `stop_grace_period`(35초)가 맡는다(리뷰 지적 P1, PR #136 — 예전에는 이 설정이 없어 결제·AI 상담처럼 5초를 넘기는 요청이 컷오버 도중 강제 종료될 수 있었다).
6. target이 애초에 healthy가 되지 못하면 **nginx도 old 색도 건드리지 않은 채** target 컨테이너만 정리하고 실패 종료한다 — 운영 중인 서비스는 배포 시도 자체를 몰라도 된다. 기존의 "이전 SHA로 능동 재배포" 롤백보다 훨씬 단순하고 안전하다.

## 5. 상태 파일 (git 비추적, 서버 로컬)

기존 `.last_deployed_sha`와 같은 원칙 — 배포 스크립트가 관리하는 런타임 상태는 git으로 추적하지 않는다(추적하면 `git checkout --detach`가 매 배포마다 값을 되돌려버린다).

| 파일 | 의미 | 없을 때(최초 배포) |
| --- | --- | --- |
| `.active_color` | 현재 nginx가 가리키는 색(`blue`/`green`) | 없으면 최초 배포로 간주, blue를 직접 배포(컷오버 절차 없이 바로 활성화) |
| `nginx/conf.d/upstream-active.conf` | nginx가 실제로 읽는 활성 업스트림 | 없으면 blue를 가리키는 기본값으로 부트스트랩 |
| `.last_deployed_sha` | 현재 활성 색이 돌리는 SHA(기존과 동일 의미 유지) | 없으면 배포 SHA 역전 검사를 건너뜀(기존 로직과 동일) |

## 6. 변경 대상 파일

- `docker-compose.yml` — `app` 서비스를 `app-blue`/`app-green`으로 분리하고 호스트 포트를 제거, `nginx` 서비스를 신설해 8080을 게시.
- `nginx/nginx.conf` — 정적 설정(git 추적).
- `nginx/conf.d/` — `upstream-active.conf`를 담는 디렉터리(파일 자체는 git 비추적, `.gitignore`에 추가).
- `.github/workflows/deploy.yml` — 대상 색 판단 → target 배포 → 헬스체크 → nginx reload → old 색 정지 순서로 재작성.

## 7. 주의사항 (구현 시 반드시 지킬 것)

- 서버에서 대상 서비스를 지정하지 않은 `docker compose up -d`(전체 서비스 대상)를 직접 실행하지 않는다 — 항상 배포 스크립트가 `--no-deps app-<color>`로 대상 색만 지정한다. 지정 없이 실행하면 비활성 색까지 함께 떠서 blue/green의 "평소엔 하나만 구동" 전제가 깨진다.
- `APP_BLUE_IMAGE_TAG`/`APP_GREEN_IMAGE_TAG`는 기존 `APP_IMAGE_TAG`와 동일한 패턴으로 배포 스크립트가 그때그때 export한다 — `.env`에 고정값으로 넣지 않는다(안 그러면 로컬 개발 시 `docker compose up`이 이상한 태그로 두 색을 동시에 빌드/pull 시도할 수 있다).
- `nginx -s reload` 전에 `nginx -t`로 문법 검증을 먼저 한다 — 검증 실패한 설정으로 reload를 시도하면 nginx가 기존 워커를 유지한 채 실패해 조용히 컷오버가 안 되는데, 스크립트가 이걸 놓치면 "성공했다고 착각하고 넘어가는" 문제가 생긴다.
- 커넥션 풀 크기(HikariCP) × 2(blue+green 동시 구동 순간) 합이 mysql `max_connections`를 넘지 않는지 구현 후 확인한다(성능 저하 논의에서 지적된 부분).
- **1회성 마이그레이션(중요, AWS 콘솔 작업 아님)**: 이 변경 이전에는 `app` 컨테이너가 호스트 포트 8080을 직접 게시하고 있었다. 이 브랜치를 develop에 머지해 처음 배포할 때, 옛 `app` 컨테이너가 여전히 8080을 물고 있으면 nginx가 그 포트를 못 가져가 충돌한다. `deploy.yml`이 `docker ps --filter publish=8080 --filter name=app`으로 옛 컨테이너를 찾아 자동으로 정지·제거하도록 이미 반영해뒀다 — 사람이 EC2에 수동으로 들어가서 지울 필요는 없다. app-blue/app-green은 애초에 호스트 포트를 게시하지 않아 이 필터에 걸리지 않고, 두 번째 배포부터는 옛 컨테이너 자체가 없어서 이 블록은 항상 아무 일도 하지 않는다. 이 정리는 대상 앱이 healthy로 확인된 뒤·nginx 기동 직전에 수행한다(리뷰 지적) — app-blue/green이 8080을 게시하지 않아 옛 컨테이너와 공존 가능하므로, 헬스체크보다 먼저 옛 컨테이너를 내리면 "옛 앱 정지 ~ 새 앱 pull·기동·헬스체크 통과"까지 불필요하게 다운타임이 늘어난다.
- **컷오버 순간의 SSE 연결은 예외적으로 끊긴다(알려진 한계, 리뷰 지적)**: `/api/notifications/subscribe`는 최대 3600s 열려 있는 연결인데, 컷오버는 `nginx -s reload` 후 5초 드레인만 주고 이전 색을 `stop`한다. 일반 요청은 5초 안에 끝나 문제가 없지만, 드레인 시작 이전부터 열려 있던 SSE 연결은 이전 색이 정지되며 강제로 끊긴다. `EventSource`가 명세상 자동 재연결하므로 기능적으로는 새 색에 다시 붙어 복구되지만(알림 폴링과 달리 진짜 무중단은 아니다), 배포 순간에 한해 재연결 한 번이 발생한다는 점은 SSE를 쓰는 다른 기능을 추가할 때도 같이 감안한다.
- **로컬 개발 시 `nginx/conf.d/upstream-active.conf`를 직접 만들어야 한다(실제로 겪은 문제)**: 이 파일은 git 비추적이라 저장소를 새로 클론하거나 `nginx/conf.d/upstream-active.conf.example`을 복사하지 않은 채 `docker compose up`을 실행하면, nginx가 `upstream app_upstream`을 찾지 못해 "host not found in upstream" 오류로 기동 즉시 재시작을 반복한다(`restart: unless-stopped`라 계속 재시작 루프에 빠지고, 브라우저에서는 그 사이 타이밍에 연결 거부로 보인다). EC2에서는 `deploy.yml`이 최초 배포 시 이 파일을 자동 생성해주지만, 로컬은 그 부트스트랩 로직이 없으므로 `cp nginx/conf.d/upstream-active.conf.example nginx/conf.d/upstream-active.conf`를 먼저 실행해야 한다.

## 8. 구현 체크리스트

- [x] `nginx/nginx.conf`, `nginx/conf.d/` 작성 및 `.gitignore`에 `nginx/conf.d/upstream-active.conf` 추가
- [x] `docker-compose.yml` blue/green 구조로 변경
- [x] `.github/workflows/deploy.yml` 컷오버 로직으로 재작성 — YAML·bash 문법 확인(`python3 -c yaml.safe_load`, `bash -n`) PASS. 아래 로컬 Docker 실기동은 `deploy.yml`을 그대로 실행한 것이 아니라, 컷오버가 의존하는 개별 동작(이미지 기동·healthy 대기·nginx reload·이전 색 정지)을 수동 명령으로 재현해 확인한 것이다 — SHA checkout, 이미지 pull·태그 주입, 최초 배포 분기, 옛 단일 `app` 마이그레이션, 실패 시 복구 경로 등 워크플로 자체의 실행은 포함하지 않는다. `deploy.yml` 전체 실행은 EC2에서 별도로 검증해야 한다(아래 항목 참고).
- [x] 로컬(`docker compose up -d`)에서 blue만으로 기존과 동일하게 뜨는지 확인 — 2026-08-11, WSL Docker에서 확인. `nginx/conf.d/upstream-active.conf`를 `.example`에서 복사해 생성한 뒤 mysql/redis/app-blue는 healthcheck 통과로 healthy 판정까지 확인됨. **nginx는 `docker-compose.yml`에 healthcheck가 정의돼 있지 않아 `docker compose ps`가 애초에 healthy로 판정할 수 없는 서비스다** — 이 단계에서 확인한 건 nginx 컨테이너가 정상 기동(running)했다는 것뿐이고, nginx 경유 요청 처리 준비 상태(예: `/healthz` curl)는 이 단계에서 별도로 확인하지 않았다(컷오버 단계에서는 아래처럼 curl 루프로 확인함). 로컬 개발 시 이 파일을 사람이 직접 만들어야 한다는 점은 `.example` 파일 안내에 있었지만, 처음 시도에서 이 단계를 건너뛰어 nginx가 "host not found in upstream"으로 재시작 루프에 빠지는 걸 실제로 겪었다 — 로컬 신규 셋업 가이드에 이 단계를 더 눈에 띄게 남길 필요가 있다.
- [ ] 실제 EC2에서 1회 배포로 부트스트랩(blue 최초 기동) 확인 — 로컬과 별개로 여전히 미실행.
- [x] 2회차 배포로 blue→green 컷오버, 컷오버 중 무중단 확인(연속 요청 스크립트로 검증) — 2026-08-11, 로컬 Docker에서 확인. `COMPOSE_PROFILES=green` 상태로 `app-green`을 healthy까지 띄운 뒤 `upstream-active.conf`를 green으로 바꿔 `nginx -t && nginx -s reload`, 이어서 `docker compose stop app-blue`까지 실행하는 동안 별도 터미널에서 `while true; do curl .../healthz; sleep 0.2; done` 루프가 컷오버 전 구간 내내 200만 반환(끊김 없음).
- [x] `docker compose ps -q app-green`이 배포 스크립트와 동일한 조건(`COMPOSE_PROFILES=green` 활성화 상태)에서 실제 컨테이너를 정상 조회하는지 확인(리뷰 지적) — 2026-08-11, 위와 같은 세션(즉 `COMPOSE_PROFILES=green`이 켜진 채)에서 `docker compose ps -q app-green` 실행 결과 64자리 컨테이너 ID가 정상 반환됨. 배포 스크립트는 시작 시 항상 `export COMPOSE_PROFILES=green`을 수행하므로 배포 기능상으로는 이 확인으로 충분하다. 다만 **`COMPOSE_PROFILES`를 unset한 상태에서도 동일하게 동작하는지는 검증하지 않았다** — 우려했던 "프로파일 때문에 빈 값 반환" 문제가 프로파일 비활성 조건에서도 재현되지 않는지는 별도로 확인이 필요하며, 이번 결과를 그 조건까지 일반화할 수는 없다. `stop app-green`/`rm app-green`까지는 이번엔 별도로 확인하지 않았다(현재 활성 색이라 정지시키지 않음) — 다음 컷오버(green→blue) 때 자연스럽게 확인 가능.
- EC2의 실제 네트워크/리소스 조건에서의 컷오버는 이 로컬 확인과 별개로 여전히 미검증.
- [ ] 컷오버 중 SSE(`/api/notifications/subscribe`) 구독 클라이언트가 연결 종료 후 자동 재연결로 정상 복구되는지 확인(리뷰 지적, 알려진 한계 재확인)
- [ ] 의도적으로 healthcheck 실패하는 이미지로 배포해 "target만 정리되고 기존 색은 안 건드려지는지" 확인
- [ ] `server.shutdown: graceful` + `stop_grace_period: 35s`가 실제로 진행 중인 요청을 지켜주는지 확인(리뷰 지적 P1, PR #136) — 예: 인위적으로 5초 이상 걸리는 요청(또는 테스트용 지연 엔드포인트)을 이전 색에 걸어둔 채 컷오버를 실행해, `docker compose stop`이 그 요청을 끊지 않고 응답까지 받는지 확인. 정적 설정 검토만으로는 실제 드레인 여부를 보장할 수 없다.
- [ ] SA 문서 또는 별도 인프라 섹션에 최종 반영 여부 결정(이 문서를 정본으로 유지할지, SA에 흡수할지)
