# DoctorPet 코드 컨벤션

| 항목 | 내용 |
| --- | --- |
| 제품명 | DoctorPet |
| 문서 버전 | v1.0 |
| 작성 기준일 | 2026-07-22 |
| 기준 문서 | PRD, SA (버전은 각 문서 헤더 참조) |
| 적용 범위 | 백엔드 전체 |

---

## 패키지 구조

- 패키지는 도메인 기준으로 분리합니다.
- 공통 설정/응답/예외는 `global` 패키지에 둡니다.
- 도메인 내부는 역할별 패키지로 분리합니다.

---

## 패키지 구조 예시

```
global/
 ├─ config/
 ├─ exception/
 ├─ response/
 ├─ security/
 └─ gateway/        (외부 연동 추상화: PaymentGateway, AiGateway, PublicDataGateway)
domain/
 ├─ reservation/
 │   ├─ controller/
 │   ├─ service/
 │   ├─ repository/
 │   ├─ entity/
 │   ├─ dto/
 │   │   ├─ request/
 │   │   └─ response/
 │   └─ exception/
 │
 ├─ member/
 ├─ pet/
 ├─ hospital/
 ├─ search/
 ├─ payment/
 ├─ ai/
 ├─ notification/
 └─ publicdata/
```

---

## 클래스 네이밍 규칙

| 클래스 | 규칙 |
| --- | --- |
| Controller | `XxxController` |
| Service | `XxxService` |
| ApplicationService(여러 도메인 조합) | `XxxApplicationService` |
| Repository | `XxxRepository` |
| Entity | `Xxx` |
| Request DTO | `XxxRequest` |
| Response DTO | `XxxResponse` |
| ErrorCode | `XxxErrorCode` |
| Exception | `XxxException` |

---

## Controller 규칙

- Controller는 요청/응답만 담당합니다.
- 비즈니스 로직은 Service에 위임합니다.
- Entity를 직접 반환하지 않습니다.
- Request / Response DTO를 사용합니다.
- API 응답은 공통 Response 형식을 사용합니다.
- 사용자 식별은 요청 body가 아니라 `@AuthenticationPrincipal`로만 합니다. (요청의 `memberId`·`hospitalId`는 신뢰하지 않습니다.)
- 생성은 `201`, 조회·수정은 `200`, 본문 없는 응답은 `204`.

---

## Controller 예시

```java
@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ReservationResponse> createReservation(
            @AuthenticationPrincipal MemberPrincipal principal,
            @Valid @RequestBody ReservationCreateRequest request
    ) {
        return ApiResponse.success(
                reservationService.request(principal.memberId(), request)
        );
    }
}
```

---

## Service 규칙

- Service는 비즈니스 로직을 담당합니다.
- 여러 도메인의 흐름 조합은 별도 ApplicationService(CommandService/Facade)로 분리합니다.
- 트랜잭션은 필요한 범위에서만 사용합니다. 조회 전용은 `@Transactional(readOnly = true)`.
- 외부 시스템 호출(PortOne·LLM·공공데이터)은 트랜잭션 안에 두지 않습니다.
- Entity 상태 변경은 setter보다 도메인 메서드를 사용합니다.

---

## Service 예시

```java
reservation.confirm();
payment.markPaid();
```

---

## Entity 규칙

- Entity에는 `@Setter` 사용을 지양합니다.
- **Entity 필드에 `final`을 사용하지 않습니다.** (JPA 기본 생성자·더티체킹과 충돌)
- JPA 기본 생성자는 `protected`로 설정합니다.
- 객체 생성은 정적 팩토리 메서드를 사용하는 것을 권장합니다.
- 생성 의도가 명확한 경우 `create()`, `of()`, `from()`(또는 도메인 의미에 맞는 `request()` 등) 메서드를 사용합니다.
- 상태 변경은 의미 있는 메서드로 처리합니다.
- Entity 내부에서 비즈니스 규칙을 최대한 관리합니다.
- 불변성은 `final`이 아니라 **setter 미제공 + private 생성자**로 확보합니다.

---

## Lombok 사용 규칙

| 어노테이션 | 규칙 |
| --- | --- |
| `@Getter` | 사용 |
| `@Setter` | Entity 사용 지양 |
| `@NoArgsConstructor(access = AccessLevel.PROTECTED)` | Entity JPA 기본 생성자 |
| `@RequiredArgsConstructor` | Service·Controller의 생성자 주입에 사용 (Entity에는 사용하지 않음) |
| `@Builder` | 필요한 경우 사용 |
| `@Data` | 사용 지양 |
| `@AllArgsConstructor` | 사용 지양 |

> Entity 생성 제한은 **명시적 `private` 생성자 + 정적 팩토리**로 강제합니다. (`final` 필드를 두지 않으므로 setter 미제공으로 불변성을 확보합니다.)

---

## Entity 예시

```java
@Entity
@Table(name = "reservations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long memberId;
    private Long petId;
    private Long hospitalId;
    private Long slotId;
    private Long paymentMethodId;

    // 프로필 삭제(Soft Delete) 대비 이력 보존 스냅샷
    private String petNameSnapshot;
    private String petSpeciesSnapshot;

    @Enumerated(EnumType.STRING)
    private ReservationStatus status;

    private LocalDateTime requestedAt;
    private LocalDateTime confirmedAt;
    private LocalDateTime canceledAt;

    private Reservation(
            Long memberId, Long petId, Long hospitalId, Long slotId,
            Long paymentMethodId, String petNameSnapshot, String petSpeciesSnapshot
    ) {
        this.memberId = memberId;
        this.petId = petId;
        this.hospitalId = hospitalId;
        this.slotId = slotId;
        this.paymentMethodId = paymentMethodId;
        this.petNameSnapshot = petNameSnapshot;
        this.petSpeciesSnapshot = petSpeciesSnapshot;
        this.status = ReservationStatus.REQUESTED;
        this.requestedAt = LocalDateTime.now();
    }

    /** 예약 요청 생성 (프로필·빌링키·리드타임 검증은 Service에서 선행) */
    public static Reservation request(
            Long memberId, Long petId, Long hospitalId, Long slotId,
            Long paymentMethodId, String petNameSnapshot, String petSpeciesSnapshot
    ) {
        return new Reservation(
                memberId, petId, hospitalId, slotId,
                paymentMethodId, petNameSnapshot, petSpeciesSnapshot
        );
    }

    /** 병원 승인 */
    public void confirm() {
        if (status != ReservationStatus.REQUESTED) {
            throw new ServiceException(ReservationErrorCode.INVALID_STATUS);
        }
        this.status = ReservationStatus.CONFIRMED;
        this.confirmedAt = LocalDateTime.now();
    }

    /** 사용자 취소 (예약 2시간 전까지, 시각 검증은 Service에서 수행) */
    public void cancel(LocalDateTime now) {
        if (status != ReservationStatus.REQUESTED && status != ReservationStatus.CONFIRMED) {
            throw new ServiceException(ReservationErrorCode.INVALID_STATUS);
        }
        this.status = ReservationStatus.CANCELED;
        this.canceledAt = now;
    }

    public boolean isOwnedBy(Long memberId) {
        return this.memberId.equals(memberId);
    }
}
```

---

## DTO 규칙

- Request DTO와 Response DTO를 분리합니다.
- Entity를 직접 반환하지 않습니다.
- Entity에서 Response DTO로 변환 시 `from()`을 사용합니다.
- 필요시 Record 타입도 사용 가능합니다.
- Request DTO에는 `memberId`·`hospitalId`를 두지 않습니다(인증 주체에서 획득).

---

## DTO 예시

```java
public record ReservationResponse(
        Long reservationId,
        Long hospitalId,
        ReservationStatus status
) {
    public static ReservationResponse from(Reservation reservation) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getHospitalId(),
                reservation.getStatus()
        );
    }
}
```

---

## Exception 규칙

- 공통 예외는 `global.exception` 패키지에서 관리합니다.
- 도메인 예외는 각 도메인 `exception` 패키지에서 관리합니다.
- ErrorCode 기반 예외 처리를 사용합니다.
- 예외는 `ServiceException(ErrorCode)`로 던지고 `GlobalExceptionHandler`에서 일괄 처리합니다.

---

## Exception 예시

```java
throw new ServiceException(ReservationErrorCode.RESERVATION_NOT_FOUND);
```

---

## ErrorCode 규칙

- 공통 예외는 `global.exception` 패키지에서 관리합니다.
- 도메인 예외는 각 도메인 `exception` 패키지에서 관리합니다.
- 모든 ErrorCode enum은 공통 `ErrorCode` 인터페이스를 구현합니다.
- ErrorCode는 `HttpStatus`, `code`, `message` 값을 가집니다.
- 에러 코드는 `{DOMAIN}_{3자리 숫자}` 형식으로 작성합니다.
- 도메인별 에러 코드는 각 도메인에서만 관리합니다.
- 하나의 글로벌 enum에 모든 도메인 예외를 모으지 않습니다.

---

## ErrorCode 패키지 구조

```
global/
 └─ exception/
      ├─ ErrorCode.java            (인터페이스)
      ├─ CommonErrorCode.java      (공통·기술적 예외 enum)
      ├─ ServiceException.java
      └─ GlobalExceptionHandler.java
domain/
 ├─ reservation/
 │    └─ exception/
 │         └─ ReservationErrorCode.java
 │
 ├─ payment/
 │    └─ exception/
 │         └─ PaymentErrorCode.java
 │
 └─ hospital/
      └─ exception/
           └─ HospitalErrorCode.java
```

---

## ErrorCode 인터페이스 예시

```java
package com.doctorpet.global.exception;

import org.springframework.http.HttpStatus;

public interface ErrorCode {
    HttpStatus getHttpStatus();
    String getCode();
    String getMessage();

    /** 프론트 파싱용 정수 상태값 (예: 200, 400, 404) */
    default int getStatus() {
        return getHttpStatus().value();
    }
}
```

---

## CommonErrorCode 예시 (공통·기술적 예외)

```java
package com.doctorpet.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "COMMON_001", "입력값이 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "COMMON_002", "로그인이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "COMMON_003", "접근 권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON_004", "요청한 리소스를 찾을 수 없습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_005", "서버 내부 오류가 발생했습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
```

---

## DomainErrorCode 예시

```java
package com.doctorpet.domain.payment.exception;

import com.doctorpet.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum PaymentErrorCode implements ErrorCode {

    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "PAYMENT_001", "결제 정보를 찾을 수 없습니다."),
    ALREADY_PAID(HttpStatus.CONFLICT, "PAYMENT_002", "이미 결제된 건입니다."),
    DUPLICATE_PAYMENT(HttpStatus.CONFLICT, "PAYMENT_003", "중복 결제 요청입니다."),
    INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "PAYMENT_004", "청구 금액이 올바르지 않습니다."),
    OFFLINE_PRECONDITION_FAILED(HttpStatus.CONFLICT, "PAYMENT_005", "오프라인 정산 대상 상태가 아닙니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
```

> 필드명은 인터페이스 게터(`getHttpStatus`)에 맞춰 세 enum 모두 `httpStatus` / `code` / `message`로 통일합니다.

---

## ServiceException 예시

```java
package com.doctorpet.global.exception;

import lombok.Getter;

@Getter
public class ServiceException extends RuntimeException {

    private final ErrorCode errorCode;

    public ServiceException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
```

---

## GlobalExceptionHandler 예시

```java
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<ApiResponse<Void>> handleServiceException(ServiceException e) {
        ErrorCode errorCode = e.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponse.error(errorCode));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        return ResponseEntity.status(CommonErrorCode.VALIDATION_FAILED.getHttpStatus())
                .body(ApiResponse.error(CommonErrorCode.VALIDATION_FAILED));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("처리되지 않은 예외", e);
        return ResponseEntity.status(CommonErrorCode.INTERNAL_SERVER_ERROR.getHttpStatus())
                .body(ApiResponse.error(CommonErrorCode.INTERNAL_SERVER_ERROR));
    }
}
```

---

## 에러 코드 작성 기준

| 구분 | 위치 | 예시 |
| --- | --- | --- |
| 공통/기술적 예외 | `CommonErrorCode` | Validation 실패, 인증 실패, 서버 오류 |
| 도메인/비즈니스 예외 | `{Domain}ErrorCode` | 예약 없음, 결제 상태 오류, 슬롯 점유됨 |

---

## 사용 예시

```java
throw new ServiceException(PaymentErrorCode.PAYMENT_NOT_FOUND);
```

```java
throw new ServiceException(CommonErrorCode.INTERNAL_SERVER_ERROR);
```

---

## 작성 규칙

- `ErrorCode` enum 이름은 `{Domain}ErrorCode` 형식으로 작성합니다.
- `code` 값은 `{DOMAIN}_{3자리 숫자}` 형식을 권장합니다.
- 예외 메시지는 클라이언트에게 전달 가능한 문장으로 작성합니다.
- 새로운 도메인 예외는 해당 도메인의 ErrorCode enum에 추가합니다.
- 공통 예외와 비즈니스 예외를 분리해서 관리합니다.

---

## Validation 규칙

- Request DTO에서 Validation을 처리합니다.
- `@Valid`를 사용합니다.
- Validation 실패 응답은 공통 예외 처리에서 관리합니다.
- 금액·권한·상태 전이처럼 비즈니스 판단이 필요한 검증은 Service·Entity에서 합니다.

---

## Validation 예시

```java
public record ReservationCreateRequest(
        @NotNull(message = "반려동물을 선택해주세요.")
        Long petId,

        @NotNull(message = "예약 슬롯을 선택해주세요.")
        Long slotId,

        @NotNull(message = "결제수단을 선택해주세요.")
        Long paymentMethodId
) { }
```

---

## Response 규칙

- 공통 응답 포맷을 사용합니다.
- 성공/실패 응답 구조를 통일합니다. 실패 시 `data`는 `null`입니다.

---

## Response 예시

```java
package com.doctorpet.global.response;

import com.doctorpet.global.exception.ErrorCode;

public record ApiResponse<T>(
        String code,
        String message,
        T data
) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("SUCCESS", "요청이 처리되었습니다.", data);
    }

    public static ApiResponse<Void> success() {
        return new ApiResponse<>("SUCCESS", "요청이 처리되었습니다.", null);
    }

    public static ApiResponse<Void> error(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.getCode(), errorCode.getMessage(), null);
    }
}
```

```json
{
  "code": "SUCCESS",
  "message": "요청이 처리되었습니다.",
  "data": {}
}
```

```json
{
  "code": "SLOT_002",
  "message": "이미 예약된 시간대입니다.",
  "data": null
}
```

---

## API 규칙

- RESTful API 스타일을 지향합니다.
- URL은 복수형을 사용합니다.
- 상태 변경은 HTTP Method 의미에 맞게 사용합니다.
- 일반 사용자 API는 `/api/**`, 병원 스태프 운영 API는 `/api/hospital/**`로 둡니다. (PRD 준수)
- 권한은 URL만으로 신뢰하지 않고, 인증 주체·소속 병원 일치를 서버에서 재검증합니다.

---

## API 예시

```
GET   /api/hospitals
GET   /api/reservations
POST  /api/reservations
PATCH /api/reservations/{reservationId}/cancel
PATCH /api/hospital/reservations/{reservationId}/approve
POST  /api/hospital/reservations/{reservationId}/payments
```

---

## 테스트 규칙

- 핵심 비즈니스 로직은 테스트 작성을 권장합니다.
- 테스트 코드는 given / when / then 패턴을 사용합니다.
- 테스트 이름은 동작을 서술합니다.
- **동시성(필수 과제)**은 다중 스레드 시나리오(`ExecutorService` + `CountDownLatch`)로 "동시 요청 시 1건만 성립"을 검증합니다.

---

## 테스트 예시

```java
@Test
void 동일_슬롯에_동시_예약하면_1건만_성립한다() throws InterruptedException {
    // given
    int threadCount = 100;
    ExecutorService executor = Executors.newFixedThreadPool(32);
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger success = new AtomicInteger();
    AtomicInteger failure = new AtomicInteger();

    // when
    for (int i = 0; i < threadCount; i++) {
        long memberId = i + 1L;
        executor.submit(() -> {
            try {
                reservationService.request(memberId, request);
                success.incrementAndGet();
            } catch (ServiceException e) {
                failure.incrementAndGet();
            } finally {
                latch.countDown();
            }
        });
    }
    latch.await();
    executor.shutdown();

    // then
    assertThat(success.get()).isEqualTo(1);
    assertThat(failure.get()).isEqualTo(threadCount - 1);
}
```
