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
 * Level 3 — 이슈 #105 2차 리뷰 P1 대응. {@link ObservabilityAndDocsSecurityTest}는 슬라이스
 * 테스트({@code @WebMvcTest})라 springdoc 자동구성을 로드하지 않으므로, {@code /v3/api-docs}가
 * 실제로는 404를 반환해도 "401/403이 아니다"라는 조건만으로 통과했다. 이 클래스는 실제 애플리케이션
 * 컨텍스트를 그대로 띄워 springdoc이 local 프로파일에서 실제로 등록되고 200을 반환하는지 검증한다.
 *
 * {@code webEnvironment=RANDOM_PORT}는 쓰지 않는다 — {@code management.server.port}가 설정된
 * 상태에서 RANDOM_PORT와 결합하면 알려진 플레이키 이슈(spring-boot#48653)가 있어, 기본 MOCK
 * 환경(실제 소켓 포트를 열지 않음)으로 충분한 이 검증에는 그 위험을 감수할 이유가 없다. springdoc은
 * 관리 포트 분리와 무관하게 항상 메인 애플리케이션 컨텍스트에서 동작하므로 MOCK 환경에서도 실제
 * 동작을 그대로 검증할 수 있다.
 *
 * 전체 컨텍스트(MySQL·Redis·env)가 필요하다 — 없으면 BLOCKED.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class SwaggerLocalProfileIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("local 프로파일에서는 /v3/api-docs가 실제로 200을 반환한다(이슈 #105 2차 리뷰 P1)")
    void apiDocs_localProfile_returns200() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
    }
}
