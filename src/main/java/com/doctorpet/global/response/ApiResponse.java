package com.doctorpet.global.response;

import com.doctorpet.global.exception.ErrorCode;

public record ApiResponse<T>(
        String code,
        String message,
        T data
) {

    private static final String DEFAULT_SUCCESS_MESSAGE = "요청이 성공했습니다.";

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("SUCCESS", DEFAULT_SUCCESS_MESSAGE, data);
    }

    public static ApiResponse<Void> success() {
        return new ApiResponse<>("SUCCESS", DEFAULT_SUCCESS_MESSAGE, null);
    }

    public static ApiResponse<Void> error(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.getCode(), errorCode.getMessage(), null);
    }
}
