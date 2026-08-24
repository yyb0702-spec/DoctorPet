package com.doctorpet.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 노쇼 자동판정·승인 타임아웃(reservation), 결제 정산(payment), 공공데이터 배치(publicdata)
 * 스케줄러가 이 설정을 전제로 @Scheduled를 사용한다.
 */
@Configuration
@EnableScheduling
public class SchedulerConfig {
}
