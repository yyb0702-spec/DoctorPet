# PR #74 병원 예약 운영 API 리뷰 반영 결과

> 작성일: 2026년 7월 31일
> 대상 브랜치: `feature/reservation-list-detail`
> 기준 커밋: `b315402`

## 1. 작업 결과 요약

필수 리뷰 네 영역인 시간 제한, 거절 사유, 인가 테스트, 상태 전이 동시성 Level 3 검증을 반영했다.
후순위 개선으로 예약 목록 N+1 제거, 상태 필터 추가, 상태 전이 규칙의 단일화를 함께 처리했다.

## 2. 필수 리뷰 반영

### 2-1. 승인·체크인 시간 제한

- 승인 마감은 `min(요청 시각 + 1시간, 예약 시각 - 2시간)`으로 계산한다.
- 승인 API가 마감 이후 호출되면 `RESERVATION_011`을 반환한다.
- 체크인은 예약 시각 +10분까지만 허용한다.
- 체크인 API가 마감 이후 호출되면 `RESERVATION_012`를 반환한다.
- 스케줄러 실행 전의 시간 공백에도 API가 정책 밖 전이를 허용하지 않도록 서비스에서 직접 검증한다.

### 2-2. 거절 사유 화이트리스트

`ReservationRejectReason` enum으로 다음 네 값만 허용한다.

- 직원 부족
- 슬롯 등록 오류
- 진료 불가
- 기타

DTO Validation과 서비스 enum 타입을 함께 적용해 Controller를 거치지 않는 서비스 직접 호출에서도 임의 문자열을 저장할 수 없게 했다.
PRD와 SA의 `슬롯 등록 오류`/`슬롯 오류` 표현 충돌은 상위 정본인 PRD를 따라 `슬롯 등록 오류`로 통일했다.

### 2-3. 병원 예약 인가 테스트

- `@WebMvcTest`가 실제 `HospitalReservationController`를 로드하도록 수정했다.
- Mock 대상을 `HospitalReservationApplicationService`로 교체했다.
- 비인증 사용자 401, 보호자 403, 병원 스태프 200을 실제 Security 필터 체인으로 검증한다.
- 병원 스태프 성공 요청이 인증 principal의 `memberId`를 서비스에 전달하는지 검증한다.
- 정책에 없는 거절 사유가 400으로 거부되는 MVC 테스트를 추가했다.

### 2-4. 상태 전이 동시성 Level 3

실제 MySQL에서 두 스레드를 동시에 출발시키는 통합 테스트를 추가했다.

- 승인과 거절 경쟁: 정확히 한 요청만 성공한다.
- 승인 승리 시 예약 `CONFIRMED`, 슬롯 `RESERVED`를 확인한다.
- 거절 승리 시 예약 `REJECTED`, 슬롯 `OPEN`을 확인한다.
- 사용자 취소와 병원 거절 경쟁: 정확히 한 요청만 성공하고 슬롯은 `OPEN`이 된다.

승인과 취소는 `CONFIRMED → CANCELED`가 허용되므로 무조건 한 요청만 성공해야 하는 경쟁으로 잘못 규정하지 않았다.

## 3. 후순위 리뷰 반영

### 3-1. 예약 목록 N+1 제거

기존에는 예약 한 건마다 슬롯 1회와 이력 집계 4회를 실행했다.

개선 후에는:

1. 페이지의 슬롯 ID를 모아 `findAllById`로 한 번에 조회한다.
2. 회원 ID를 중복 제거한다.
3. `GROUP BY memberId` 집계 쿼리 한 번으로 전체·완료·취소·노쇼 건수를 가져온다.
4. Map으로 변환해 응답을 조립한다.

사용되지 않는 행별 `countByMemberId`, `countByMemberIdAndStatus` 메서드는 제거했다.

### 3-2. 병원 예약 목록 상태 필터

- `GET /api/hospital/reservations?status=REQUESTED` 형식으로 상태를 받는다.
- 상태를 생략하면 `REQUESTED`를 기본값으로 사용한다.
- 체크인·진료 대상은 `CONFIRMED`, `CHECKED_IN`, `IN_TREATMENT` 등을 명시해 조회할 수 있다.
- 잘못된 상태 문자열은 `INVALID_FILTER_STATUS`로 거부한다.
- SA API 설명도 같은 계약으로 갱신했다.

### 3-3. 상태 전이 규칙 단일화

운영 코드에서 사용되지 않던 `Reservation.confirm`, `reject`, `checkIn`, `startTreatment`, `completeTreatment`를 제거했다.
병원 운영 상태 전이는 SA §5의 정본대로 `WHERE status = 기대 상태` 조건부 UPDATE만 사용한다.
노쇼 전용 `markNoShow`, `restoreNoShow`는 별도 노쇼 흐름을 위해 유지했다.

## 4. 검증 결과

| 검증 | 결과 | 내용 |
| --- | --- | --- |
| 필수 서비스·Controller·인가 테스트 | PASS | 시간 제한, 거절 사유, MVC 인가 |
| 상태 전이 Level 3 테스트 | PASS | 실제 MySQL 승인↔거절, 취소↔거절 경쟁 |
| 예약 도메인 전체 테스트 | PASS | 최신 리뷰 반영 후 86개 테스트 |
| 문서 하네스 | PASS | 문서 링크·경로·섹션·정본 참조 |
| `git diff --check` | PASS | 공백 오류 없음, CRLF 변환 경고만 존재 |
| 전체 `clean build` | PARTIAL | 336개 중 6개 실패, 1개 skipped |

전체 빌드 실패 6건은 이번 예약 변경이 아니라 기존 테스트 환경 문제다.

- `DoctorPetApplicationTests`: 테스트용 `PaymentGateway` 빈 설정 누락
- Redis 통합·성능 테스트: 로컬 Redis 미기동
- `AuthServiceConcurrencyTest`: 같은 컨텍스트 실패의 영향

전체 소스와 테스트 컴파일, Jar 조립은 성공했고 최신 리뷰 반영 후 예약 도메인 86개 테스트는 모두 통과했다.
CI에서는 MySQL·Redis 서비스와 `PAYMENT_GATEWAY=fake` 설정을 제공한 뒤 최종 전체 PASS를 확인해야 한다.

## 5. develop 병합 충돌 해결

`ReservationRepository` 충돌은 우리 브랜치의 병원 예약 목록·이력 집계·조건부 상태 전이 메서드와
develop의 회원 탈퇴 활성 예약 확인 메서드를 모두 보존하는 방식으로 해결했다.

- 병원 운영: `findByHospitalIdAndStatus`, `findHistoryAggregates`, 상태별 조건부 UPDATE 유지
- 회원 탈퇴: `existsByMemberIdAndStatusIn` 유지
- 중복된 import 충돌 마커 제거
- develop에서 추가된 `MemberBlacklistPort`와 fake mail 테스트 설정을 예약 테스트에 반영
- 병합 후 예약 도메인 전체 테스트 PASS

## 6. 최신 리뷰 추가 반영

### 6-1. 서울 시간대 기준 통일

- 승인·거절·체크인·진료 시작·진료 완료 시각을 모두 `TimePolicy.SEOUL_ZONE_ID` 기준으로 생성한다.
- JVM 기본 시간대가 UTC여도 서울 기준 승인 마감이 유지되는 테스트를 추가했다.

### 6-2. 승인 시 예약·슬롯 정합성 재검증

- 슬롯의 `hospitalId`가 스태프 소속 병원과 같은지 확인한다.
- 슬롯이 계속 `RESERVED` 상태인지 확인한 뒤에만 예약을 `CONFIRMED`로 변경한다.
- 타 병원 슬롯과 `OPEN` 슬롯의 승인 거부 테스트를 추가했다.

### 6-3. 조건부 UPDATE 이후 1차 캐시 정리

병원 운영 전이 5개 쿼리에 `clearAutomatically = true`를 적용했다.
벌크 UPDATE 후 같은 트랜잭션에서 오래된 예약 상태를 읽을 가능성을 없앴다.
취소 쿼리는 UPDATE 전에 조회한 슬롯을 이후 변경하므로 기존 동작을 유지했다.

### 6-4. 검증 기록 정정

- 문서의 trailing whitespace를 제거했다.
- `.\gradlew.bat test --no-daemon --tests 'com.doctorpet.domain.reservation.*'`: PASS, 86건
- `git diff --check origin/develop`: PASS

### 6-5. CI 동시성 테스트 경쟁 결과 보정

거절이 먼저 완료되면 슬롯이 `OPEN`으로 반환되므로, 뒤늦은 승인 요청은
`ReservationErrorCode.INVALID_STATUS`뿐 아니라 `SlotErrorCode.INVALID_STATUS`로도 정상 탈락할 수 있다.
동시성 테스트가 두 정상 패자 결과를 모두 허용하도록 수정했다.

- 동시성 통합 테스트 반복 실행: PASS
- 예약 도메인 전체 86건 재실행: PASS

## 7. 이번 작업에서 제외한 항목

알림 저장은 `notifications` 테이블·엔티티·조회 API를 포함하는 별도 도메인 작업이므로 임의로 추가하지 않았다.
PR #74가 이슈 #27을 자동 종료하지 않도록 `Closes #27`을 `Related to #27`로 변경하고,
저장형 알림과 예약 상태 이벤트 연동은 후속 이슈 #39로 명시한다.

`HospitalErrorCode.NOT_OWN_HOSPITAL`은 병원 소속·소유권 오류를 표현하므로 현재 구조를 유지했다.
