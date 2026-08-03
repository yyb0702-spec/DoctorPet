package com.doctorpet.domain.ai.exception;

public class AiRateLimitStorageException extends RuntimeException {

    public AiRateLimitStorageException(String message) {
        super(message);
    }

    public AiRateLimitStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
