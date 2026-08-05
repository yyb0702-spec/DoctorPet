package com.doctorpet.domain.ai.service;

import com.doctorpet.global.gateway.ai.dto.AiAnalysisResult;

final class AiToolSearchExecutionException extends RuntimeException {

    private final AiAnalysisResult analysis;
    private final boolean locationRecommended;

    AiToolSearchExecutionException(
            AiAnalysisResult analysis,
            boolean locationRecommended,
            Throwable cause
    ) {
        super(cause);
        this.analysis = analysis;
        this.locationRecommended = locationRecommended;
    }

    AiAnalysisResult analysis() {
        return analysis;
    }

    boolean locationRecommended() {
        return locationRecommended;
    }
}
