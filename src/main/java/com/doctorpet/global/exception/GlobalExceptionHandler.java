package com.doctorpet.global.exception;

import com.doctorpet.global.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import java.sql.SQLException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<ApiResponse<Void>> handleServiceException(ServiceException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponse.error(errorCode));
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class,
            HttpMessageNotReadableException.class,
            HandlerMethodValidationException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleInvalidRequestException(Exception exception) {
        // 요청 DTO 검증 실패는 VALIDATION_FAILED로 통일한다.
        return ResponseEntity.status(CommonErrorCode.VALIDATION_FAILED.getHttpStatus())
                .body(ApiResponse.error(CommonErrorCode.VALIDATION_FAILED));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolationException(
            DataIntegrityViolationException exception
    ) {
        // DB unique 제약 위반은 공통 DUPLICATE_RESOURCE로 응답한다.
        // 어떤 필드가 중복인지에 대한 구체적인 안내(예: MEMBER_001)는 서비스 계층의 사전 체크가 담당하고,
        // 여기는 그 체크를 우회한 경쟁 상태 등 예외적인 경우의 최종 방어선이다(global은 domain을 참조하지 않는다).
        ErrorCode errorCode = resolveDataIntegrityErrorCode(exception);
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponse.error(errorCode));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFoundException(NoResourceFoundException exception) {
        // 매핑된 핸들러도 정적 리소스도 없는 요청(예: springdoc이 꺼진 프로파일의 /v3/api-docs)은
        // 원래 Spring이 자동으로 404로 응답하는데, 아래 Exception.class catch-all이 먼저 잡아버려서
        // 이 저장소의 모든 "존재하지 않는 경로" 요청이 500으로 나가고 있었다(이슈 #105 2차 리뷰 P1,
        // docker compose 실기동 검증에서 발견 — /v3/api-docs가 기대한 404 대신 500을 반환).
        return ResponseEntity.status(CommonErrorCode.NOT_FOUND.getHttpStatus())
                .body(ApiResponse.error(CommonErrorCode.NOT_FOUND));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception exception) {
        // 예상하지 못한 예외는 내부 서버 오류로 응답한다.
        log.error("처리되지 않은 예외", exception);
        return ResponseEntity.status(CommonErrorCode.INTERNAL_SERVER_ERROR.getHttpStatus())
                .body(ApiResponse.error(CommonErrorCode.INTERNAL_SERVER_ERROR));
    }

    // MySQL이 UNIQUE 제약 위반(ER_DUP_ENTRY)에 실제로 내려주는 SQLState·벤더 오류 코드.
    // 메시지 문자열에 컬럼명이 아니라 Hibernate가 생성한 제약/인덱스 이름(예: UK_...)이 들어있는 경우가
    // 많아 컬럼명 기준 문자열 매칭은 신뢰할 수 없다 — SQLState/벤더 코드는 제약 이름과 무관하게 항상 같다.
    private static final String MYSQL_INTEGRITY_CONSTRAINT_VIOLATION_SQL_STATE = "23000";
    private static final int MYSQL_DUPLICATE_ENTRY_ERROR_CODE = 1062;

    private ErrorCode resolveDataIntegrityErrorCode(DataIntegrityViolationException exception) {
        Throwable cause = exception.getMostSpecificCause();

        if (cause instanceof SQLException sqlException && isDuplicateEntry(sqlException)) {
            return CommonErrorCode.DUPLICATE_RESOURCE;
        }

        return CommonErrorCode.INTERNAL_SERVER_ERROR;
    }

    private boolean isDuplicateEntry(SQLException sqlException) {
        return MYSQL_INTEGRITY_CONSTRAINT_VIOLATION_SQL_STATE.equals(sqlException.getSQLState())
                && sqlException.getErrorCode() == MYSQL_DUPLICATE_ENTRY_ERROR_CODE;
    }
}
