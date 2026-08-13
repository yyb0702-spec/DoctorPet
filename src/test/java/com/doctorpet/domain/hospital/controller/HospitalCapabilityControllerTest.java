package com.doctorpet.domain.hospital.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doctorpet.domain.hospital.dto.response.HospitalCapabilitiesResponse;
import com.doctorpet.domain.hospital.dto.request.HospitalCapabilitiesUpdateRequest;
import com.doctorpet.domain.hospital.entity.CapabilityValue;
import com.doctorpet.domain.hospital.service.HospitalCapabilityApplicationService;
import com.doctorpet.global.config.SecurityConfig;
import com.doctorpet.global.security.AccessTokenBlacklistPort;
import com.doctorpet.global.security.JwtAccessDeniedHandler;
import com.doctorpet.global.security.JwtAuthenticationEntryPoint;
import com.doctorpet.global.security.JwtTokenProvider;
import com.doctorpet.global.security.MemberBlacklistPort;
import com.doctorpet.global.security.MemberPrincipal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = HospitalCapabilityController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class
})
class HospitalCapabilityControllerTest {

    private static final Long MEMBER_ID = 10L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HospitalCapabilityApplicationService service;
    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;
    @MockitoBean
    private MemberBlacklistPort memberBlacklistPort;
    @MockitoBean
    private AccessTokenBlacklistPort accessTokenBlacklistPort;

    @Test
    void hospitalStaffGetsOwnHospitalCapabilities() throws Exception {
        given(service.getCapabilities(MEMBER_ID)).willReturn(
                new HospitalCapabilitiesResponse(List.of(
                        CapabilityValue.DOG,
                        CapabilityValue.XRAY
                ))
        );

        mockMvc.perform(get("/api/hospital/capabilities")
                        .with(authentication(createAuthentication("HOSPITAL_STAFF"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.capabilities[0]").value("DOG"))
                .andExpect(jsonPath("$.data.capabilities[1]").value("XRAY"));

        verify(service).getCapabilities(MEMBER_ID);
    }

    @Test
    void guardianCannotGetHospitalCapabilities() throws Exception {
        mockMvc.perform(get("/api/hospital/capabilities")
                        .with(authentication(createAuthentication("GUARDIAN"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void hospitalStaffUpdatesOwnHospitalCapabilities() throws Exception {
        given(service.updateCapabilities(
                org.mockito.ArgumentMatchers.eq(MEMBER_ID),
                org.mockito.ArgumentMatchers.any(HospitalCapabilitiesUpdateRequest.class)
        )).willReturn(new HospitalCapabilitiesResponse(List.of(
                CapabilityValue.DOG,
                CapabilityValue.XRAY
        )));

        mockMvc.perform(put("/api/hospital/capabilities")
                        .with(authentication(createAuthentication("HOSPITAL_STAFF")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"capabilities":["XRAY","DOG"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.capabilities[0]").value("DOG"))
                .andExpect(jsonPath("$.data.capabilities[1]").value("XRAY"));
    }

    @Test
    void updateCapabilitiesRejectsNullList() throws Exception {
        mockMvc.perform(put("/api/hospital/capabilities")
                        .with(authentication(createAuthentication("HOSPITAL_STAFF")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"capabilities":null}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void updateCapabilitiesRejectsNullElement() throws Exception {
        mockMvc.perform(put("/api/hospital/capabilities")
                        .with(authentication(createAuthentication("HOSPITAL_STAFF")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"capabilities":["DOG",null]}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void updateCapabilitiesRejectsUnknownValue() throws Exception {
        mockMvc.perform(put("/api/hospital/capabilities")
                        .with(authentication(createAuthentication("HOSPITAL_STAFF")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"capabilities":["UNKNOWN"]}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
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
