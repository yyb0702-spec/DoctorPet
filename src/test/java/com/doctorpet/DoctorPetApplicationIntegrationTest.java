package com.doctorpet;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// 클래스명이 IntegrationTest로 끝나야 ci.yml이 Level 1/2(빠른, 인프라 불필요)와 Level 3(느린,
// MySQL·Redis 필요) 잡을 이름 패턴으로 나눌 수 있다(CI 최적화) — 전체 컨텍스트 로딩 테스트라
// 실제로 DB·Redis가 필요하므로 Level 3 잡으로 분류되도록 이름을 맞췄다.
@SpringBootTest
class DoctorPetApplicationIntegrationTest {

    @Test
    void contextLoads() {
    }

}
