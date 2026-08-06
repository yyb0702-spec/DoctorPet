package com.doctorpet.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Level 3 — 이슈 #105 2차 리뷰 P1 대응. {@link SwaggerLocalProfileIntegrationTest}의 반대쪽:
 * local이 아닌 프로파일(docker/prod 배포와 동일 조건)에서는 springdoc이 꺼져 {@code /v3/api-docs}
 * 경로 자체가 없어야 한다는 걸 실제로 검증한다 — "지금 병원 스태프 운영 API를 포함한 전체 API
 * 스펙이 인터넷에 노출되지 않는다"는 이슈 #105의 핵심 보안 전제를 자동 테스트로 고정한다.
 *
 * "docker"는 예시 프로파일명일 뿐이다 — application.yaml에 local 전용 활성화 블록만 있고 다른
 * 프로파일 전용 오버라이드는 없으므로, local이 아닌 어떤 이름을 써도 동일하게 기본값(꺼짐)이
 * 적용된다. {@link ProductionSafetyGuard}는 "prod" 프로파일에서만 발동하므로 이 프로파일명과는
 * 무관하다.
 *
 * 전체 컨텍스트(MySQL·Redis·env)가 필요하다 — 없으면 BLOCKED.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("docker")
class SwaggerNonLocalProfileIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("local이 아닌 프로파일에서는 /v3/api-docs가 실제로 404를 반환한다(API 스펙 비노출 확인, 이슈 #105 2차 리뷰 P1)")
    void apiDocs_nonLocalProfile_returns404() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isNotFound());
    }
}
