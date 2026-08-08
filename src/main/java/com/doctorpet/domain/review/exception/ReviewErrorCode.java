package com.doctorpet.domain.review.exception;

import com.doctorpet.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ReviewErrorCode implements ErrorCode {

    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "REVIEW_001", "예약 정보를 찾을 수 없습니다."),
    NOT_RESERVATION_OWNER(HttpStatus.FORBIDDEN, "REVIEW_002", "본인의 예약에만 리뷰를 작성할 수 있습니다."),
    PAYMENT_NOT_COMPLETED(HttpStatus.CONFLICT, "REVIEW_003", "결제가 완료된 예약만 리뷰를 작성할 수 있습니다."),
    ALREADY_REVIEWED(HttpStatus.CONFLICT, "REVIEW_004", "이미 리뷰 작성 기회를 사용한 예약입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
