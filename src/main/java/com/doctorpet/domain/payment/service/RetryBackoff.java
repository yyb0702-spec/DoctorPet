package com.doctorpet.domain.payment.service;

/**
 * 재시도 사이 대기 전략(SA §9-4 지수 백오프). 인터페이스로 분리해 테스트에서 no-op으로 대체하고
 * 실제 대기 없이 재시도 분기를 검증할 수 있게 한다.
 */
public interface RetryBackoff {

    /**
     * {@code attempt}번째 재시도 직전 대기한다. attempt는 1부터 시작한다.
     *
     * <p>대기 시간이 {@code maxWaitMs}를 넘으면 {@code maxWaitMs}까지만 대기한다(#85 데드라인 캡). 이는 지수
     * 백오프가 HTTP 요청 스레드를 과도하게 동기 점유해 스레드풀이 고갈되는 것을 막기 위한 상한이다. 호출부는
     * 반환된 실제 대기 시간을 누적해 남은 예산을 다음 호출의 {@code maxWaitMs}로 넘긴다.
     *
     * @param attempt   1부터 시작하는 재시도 회차
     * @param maxWaitMs 이번 대기의 상한(ms). 0 이하면 대기하지 않는다.
     * @return 실제로 대기한 시간(ms)
     */
    long pause(int attempt, long maxWaitMs);
}
