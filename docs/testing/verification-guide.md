# 검증 가이드 — "됐어요"를 증명하는 법

테스트 하나가 통과했다고 모든 것이 확인된 것이 아니다. 무엇을 실제로 확인했는지 Level로 적고, 실행한 명령과 결과를 남긴다.

## 검증 Level (DoctorPet)

| Level | 확인 대상 | 실행 예 |
| --- | --- | --- |
| 1 | 빌드·단위 테스트 | `./gradlew test` |
| 2 | API 계약 — 상태코드·`ApiResponse` 포맷·Validation | MockMvc / `@WebMvcTest` |
| 3 | DB·트랜잭션·동시성 | `@SpringBootTest` + `ExecutorService`·`CountDownLatch` |
| 5 | 로컬 앱 기동 | `./gradlew bootRun` (MySQL·Redis 필요) |
| 6 | 실제 HTTP 요청 | http 파일·curl로 실제 응답 확인 |

- Level 4(외부 메시징 통합)는 없다 — Kafka 미사용. Level 7(k6 부하)은 도전 과제 착수 시 추가한다.
- **Mock PASS는 실제 MySQL·Redis·PortOne·LLM이 동작한다는 증거가 아니다.** 반대로 서버를 한 번 기동했다고 예외 상황이 검증된 것도 아니다.
- **Level 3·5·6이 실패하면 코드보다 환경을 먼저 의심한다** — MySQL·Redis 컨테이너가 켜져 있는지(`docker compose ps` 등) 확인한 뒤 코드를 본다.
- PortOne·LLM은 로컬에서 Mock/스텁(`PaymentGateway`·`AiGateway` 구현체)으로 검증하고, 실제 연동 검증은 별도 Level 6로 구분해 적는다.
- AGENTS STRICT 신호의 "동시성·통합 테스트"에서 **통합 테스트 = Level 3(필수)**이다. Level 5·6은 아래 사전 결정 규칙을 따른다.
- CI는 GitHub Actions `services:` 컨테이너로 MySQL 8(localhost:3306, DB `doctorpet`, root/root)·Redis 7(localhost:6379)을 제공한다. 테스트 프로필 연결 정보는 이 주소를 기준으로 한다.

## 판정 값

결과 열에는 아래 4개 값만 쓴다. **완료 근거로는 PASS만 사용한다.**

| 판정 | 기준 |
| --- | --- |
| PASS | 필요한 검증이 완료되고 관찰 가능한 근거(명령 출력·응답·로그)가 있으며 예상하지 못한 500·미처리 예외가 없다 |
| FAIL | 검증 실패, 예상하지 못한 500, 처리되지 않은 예외가 있다 |
| PARTIAL | 일부만 검증했다. 미실행 Level·이유·남은 위험을 기록하며 완료 근거로 쓰지 않는다 |
| BLOCKED | 필요한 DB·인프라·환경이 없어 실행할 수 없고 대체 검증으로 결론 낼 수 없다 |

## Level 5/6 사전 결정

구현을 시작하기 전에(Issue 작성 시) Level 5·6 필요 여부를 **YES/NO로 결정하고 이유를 남긴다.**
API 동작·런타임 설정·인프라 연결이 바뀌면 기본값은 YES다. 문서·저장소 운영만 바뀌어 실제 API 경로가 없으면 NO로 할 수 있다.

## 기록 양식

작업(이슈) 단위로 아래 표를 PR 본문에 남긴다.

| Level | 실행한 명령 | 결과 | 확인한 것 | 아직 모르는 것 |
| --- | --- | --- | --- | --- |
| 3 | `./gradlew test --tests SlotConcurrencyTest` | PASS | 동시 100요청 중 1건만 성립 | 실제 다중 인스턴스 환경 |

- 나쁜 기록: "테스트 완료했습니다."
- 좋은 기록: Level·명령·확인한 것·남은 위험까지. 실행하지 않은 것은 **미검증**으로 정직하게 적는다.

## SA 부록 B 필수 테스트 → Level 매핑

| 대상 | Level |
| --- | --- |
| 동일 슬롯 동시 예약 → 1건만 성립 (낙관적 락 + 조건부 UPDATE 대조) | 3 |
| 결제 멱등키 중복 요청 차단 + 타임아웃 시 단건조회 우선 | 3 |
| 중복 청구 차단 (`reservation_id`·`merchant_payment_id` UNIQUE) | 3 |
| 결제 실패 원인별 분기·오프라인 정산 후 재시도 중단 | 3 |
| 진료 완료와 청구 분리 (외부 호출 트랜잭션 밖) | 1·3 |
| 노쇼 자동 판정·수동 우선·정정, 정정 건 noShowCount 제외 | 3 |
| 예약 요청 타임아웃 자동 거절 | 3 |
| AI 장애 시 검색·예약·결제 정상 동작(Fallback) + 지표 기록 | 2·3 |
| 리드타임·취소 시한·프로필/빌링키 전제 검증 | 1·2 |
| 진료비 금액 검증 (0 이하·상한 초과 거부) | 1·2 |
| 반려동물 Soft Delete 후 예약 상세(스냅샷) 정상 조회 | 3 |
| 탈퇴 후 동일 이메일 재가입 (이메일 익명화) | 3 |
| 결제수단 삭제 허용 + 청구 시 재확인 후 OFFLINE_REQUIRED | 3 |
| 실시간 채널 장애 시 예약·결제 정상 + 알림 저장 | 3 |
