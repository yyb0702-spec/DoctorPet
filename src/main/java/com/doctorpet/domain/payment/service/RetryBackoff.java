package com.doctorpet.domain.payment.service;

/**
 * 재시도 사이 대기 전략(SA §9-4 지수 백오프). 인터페이스로 분리해 테스트에서 no-op으로 대체하고
 * 실제 대기 없이 재시도 분기를 검증할 수 있게 한다.
 */
public interface RetryBackoff {

    /**
     * {@code attempt}번째 재시도 직전 대기한다. attempt는 1부터 시작한다.
     */
    void pause(int attempt);
}
