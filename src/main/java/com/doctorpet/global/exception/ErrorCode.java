package com.doctorpet.global.exception;

import org.springframework.http.HttpStatus;

public interface ErrorCode {

    HttpStatus getHttpStatus();

    String getCode();

    String getMessage();

    default int getStatus() {
        return getHttpStatus().value();
    }
}
