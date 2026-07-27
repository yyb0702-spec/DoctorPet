package com.doctorpet.global.exception;

import com.doctorpet.global.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
            HttpMessageNotReadableException.class
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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception exception) {
        // 예상하지 못한 예외는 내부 서버 오류로 응답한다.
        log.error("처리되지 않은 예외", exception);
        return ResponseEntity.status(CommonErrorCode.INTERNAL_SERVER_ERROR.getHttpStatus())
                .body(ApiResponse.error(CommonErrorCode.INTERNAL_SERVER_ERROR));
    }

    private ErrorCode resolveDataIntegrityErrorCode(DataIntegrityViolationException exception) {
        String message = exception.getMostSpecificCause().getMessage();
        String lowerCaseMessage = message == null ? "" : message.toLowerCase(Locale.ROOT);

        boolean isUniqueViolation = lowerCaseMessage.contains("nickname") || lowerCaseMessage.contains("email");
        if (isUniqueViolation) {
            return CommonErrorCode.DUPLICATE_RESOURCE;
        }

        return CommonErrorCode.INTERNAL_SERVER_ERROR;
    }
}
