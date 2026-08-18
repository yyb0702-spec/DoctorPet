package com.doctorpet.domain.hospital.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doctorpet.domain.hospital.service.HospitalOperatingHoursApplicationService;
import com.doctorpet.global.config.SecurityConfig;
import com.doctorpet.global.security.AccessTokenBlacklistPort;
import com.doctorpet.global.security.PasswordChangeInvalidationPort;
import com.doctorpet.global.security.JwtAccessDeniedHandler;
import com.doctorpet.global.security.JwtAuthenticationEntryPoint;
import com.doctorpet.global.security.JwtTokenProvider;
import com.doctorpet.global.security.MemberBlacklistPort;
import com.doctorpet.global.security.MemberPrincipal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = HospitalOperatingHoursController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class
})
class HospitalOperatingHoursControllerTest {

    private static final Long MEMBER_ID = 10L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HospitalOperatingHoursApplicationService service;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private MemberBlacklistPort memberBlacklistPort;

    @MockitoBean
    private AccessTokenBlacklistPort accessTokenBlacklistPort;

    @MockitoBean
    private PasswordChangeInvalidationPort passwordChangeInvalidationPort; // 기능 구멍 점검 대응(비밀번호 재설정 시 Access Token 무효화) - JwtAuthenticationFilter 생성자 의존성

    @Test
    void hospitalStaffCancelsTemporaryClosure() throws Exception {
        LocalDate businessDate = LocalDate.of(2026, 8, 15);

        mockMvc.perform(delete(
                        "/api/hospital/temporary-closures/{businessDate}",
                        businessDate
                ).with(authentication(hospitalStaffAuthentication())))
                .andExpect(status().isNoContent());

        verify(service).cancelTemporaryClosure(MEMBER_ID, businessDate);
    }

    @Test
    void guardianCannotCancelTemporaryClosure() throws Exception {
        LocalDate businessDate = LocalDate.of(2026, 8, 15);

        mockMvc.perform(delete(
                        "/api/hospital/temporary-closures/{businessDate}",
                        businessDate
                ).with(authentication(guardianAuthentication())))
                .andExpect(status().isForbidden());
    }

    @Test
    void operatingHoursDaysRejectNullElement() throws Exception {
        String requestBody = """
                {
                  "desiredEffectiveFrom": "2026-08-15",
                  "days": [
                    null,
                    {"dayOfWeek":"TUESDAY","periods":[]},
                    {"dayOfWeek":"WEDNESDAY","periods":[]},
                    {"dayOfWeek":"THURSDAY","periods":[]},
                    {"dayOfWeek":"FRIDAY","periods":[]},
                    {"dayOfWeek":"SATURDAY","periods":[]},
                    {"dayOfWeek":"SUNDAY","periods":[]}
                  ]
                }
                """;

        mockMvc.perform(put("/api/hospital/operating-hours")
                        .with(authentication(hospitalStaffAuthentication()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void operatingHoursPeriodsRejectNullElement() throws Exception {
        String requestBody = """
                {
                  "desiredEffectiveFrom": "2026-08-15",
                  "days": [
                    {"dayOfWeek":"MONDAY","periods":[null]},
                    {"dayOfWeek":"TUESDAY","periods":[]},
                    {"dayOfWeek":"WEDNESDAY","periods":[]},
                    {"dayOfWeek":"THURSDAY","periods":[]},
                    {"dayOfWeek":"FRIDAY","periods":[]},
                    {"dayOfWeek":"SATURDAY","periods":[]},
                    {"dayOfWeek":"SUNDAY","periods":[]}
                  ]
                }
                """;

        mockMvc.perform(put("/api/hospital/operating-hours")
                        .with(authentication(hospitalStaffAuthentication()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    private UsernamePasswordAuthenticationToken hospitalStaffAuthentication() {
        return createAuthentication("HOSPITAL_STAFF");
    }

    private UsernamePasswordAuthenticationToken guardianAuthentication() {
        return createAuthentication("GUARDIAN");
    }

    private UsernamePasswordAuthenticationToken createAuthentication(String role) {
        MemberPrincipal principal = new MemberPrincipal(
                MEMBER_ID,
                "staff@example.com",
                role
        );
        return new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
    }
}
