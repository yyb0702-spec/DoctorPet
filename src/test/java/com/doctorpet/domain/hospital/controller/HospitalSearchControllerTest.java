package com.doctorpet.domain.hospital.controller;

import com.doctorpet.domain.hospital.dto.response.HospitalSearchPageResponse;
import com.doctorpet.domain.hospital.dto.response.HospitalSearchResponse;
import com.doctorpet.domain.hospital.dto.response.HospitalDetailResponse;
import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.PartnershipStatus;
import com.doctorpet.domain.hospital.service.HospitalService;
import com.doctorpet.domain.hospital.service.HospitalDetailApplicationService;
import com.doctorpet.domain.hospital.service.HospitalFavoriteService;
import com.doctorpet.domain.hospital.service.HospitalSlotApplicationService;
import com.doctorpet.global.security.JwtTokenProvider;
import com.doctorpet.global.security.MemberBlacklistPort;
import com.doctorpet.global.security.AccessTokenBlacklistPort;
import com.doctorpet.global.security.MemberPrincipal;
import com.doctorpet.global.config.SecurityConfig;
import com.doctorpet.global.security.JwtAccessDeniedHandler;
import com.doctorpet.global.security.JwtAuthenticationEntryPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = HospitalController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
class HospitalSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HospitalService hospitalService;

    @MockitoBean
    private HospitalDetailApplicationService hospitalDetailApplicationService;

    @MockitoBean
    private HospitalFavoriteService hospitalFavoriteService;

    @MockitoBean
    private HospitalSlotApplicationService hospitalSlotApplicationService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    // @WebMvcTest는 Filter 타입 빈(JwtAuthenticationFilter)을 addFilters=false여도 컨텍스트에
    // 생성하므로, 그 생성자 의존성인 JwtTokenProvider·MemberBlacklistPort가 없으면 컨텍스트 로딩
    // 자체가 실패한다(MemberBlacklistPort는 탈퇴 회원 Access Token 블랙리스트 체크용 — 리뷰 지적 P1 대응).
    @MockitoBean
    private MemberBlacklistPort memberBlacklistPort;

    @MockitoBean
    private AccessTokenBlacklistPort accessTokenBlacklistPort; // #124 - JwtAuthenticationFilter 생성자 의존성

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 검색_조건이_없으면_기본_페이지_조건으로_조회한다()
            throws Exception {
        HospitalSearchResponse hospital =
                new HospitalSearchResponse(
                        1L,
                        "닥터펫 동물병원",
                        "서울특별시 중구 세종대로 110",
                        null,
                        BusinessStatus.OPEN,
                        PartnershipStatus.PARTNER,
                        true,
                        null,
                        true,
                        false
                );
        HospitalSearchPageResponse page =
                HospitalSearchPageResponse.of(
                        List.of(hospital),
                        1,
                        20,
                        1,
                        1
                );
        given(hospitalService.hospitalSearch(
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                anyBoolean(),
                anyBoolean(),
                anyInt(),
                anyInt(),
                anyString()
        )).willReturn(page);

        mockMvc.perform(get("/api/hospitals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content[0].hospitalId")
                        .value(1))
                .andExpect(jsonPath("$.data.content[0].partnershipStatus")
                        .value("PARTNER"))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(1));

        verify(hospitalService).hospitalSearch(
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                eq(false),
                eq(false),
                eq(1),
                eq(20),
                eq("name")
        );
    }

    @Test
    void 제휴_병원_상세에_예약_응답_지표를_반환한다() throws Exception {
        given(hospitalDetailApplicationService.getHospitalDetail(1L, null))
                .willReturn(new HospitalDetailResponse(
                        1L,
                        "닥터펫 동물병원",
                        "서울특별시 중구 세종대로 110",
                        "02-1234-5678",
                        BusinessStatus.OPEN,
                        PartnershipStatus.PARTNER,
                        null,
                        true,
                        true,
                        true,
                        false,
                        false,
                        List.of(),
                        List.of(),
                        null,
                        0L,
                        false,
                        80,
                        15
                ));

        mockMvc.perform(get("/api/hospitals/{hospitalId}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.reservationResponseRate").value(80))
                .andExpect(jsonPath("$.data.averageApprovalMinutes").value(15));

        verify(hospitalDetailApplicationService).getHospitalDetail(1L, null);
    }

    @Test
    void 비제휴_병원_상세의_예약_응답_지표는_null이다() throws Exception {
        given(hospitalDetailApplicationService.getHospitalDetail(2L, null))
                .willReturn(new HospitalDetailResponse(
                        2L,
                        "비제휴 동물병원",
                        "서울특별시 중구",
                        "02-9876-5432",
                        BusinessStatus.OPEN,
                        PartnershipStatus.NON_PARTNER,
                        "제휴 전 병원입니다.",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        0L,
                        false,
                        null,
                        null
                ));

        mockMvc.perform(get("/api/hospitals/{hospitalId}", 2L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reservationResponseRate").value(nullValue()))
                .andExpect(jsonPath("$.data.averageApprovalMinutes").value(nullValue()));

        verify(hospitalDetailApplicationService).getHospitalDetail(2L, null);
    }

    @Test
    void 보호자는_별_버튼으로_병원을_찜하고_해제할_수_있다()
            throws Exception {
        MemberPrincipal principal = new MemberPrincipal(
                1L,
                "guardian@example.com",
                "GUARDIAN"
        );
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        List.of()
                );
        SecurityContextHolder.getContext().setAuthentication(authentication);

        mockMvc.perform(put("/api/hospitals/10/favorite"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        mockMvc.perform(delete("/api/hospitals/10/favorite"))
                .andExpect(status().isNoContent());

        verify(hospitalFavoriteService).addFavorite(1L, 10L);
        verify(hospitalFavoriteService).removeFavorite(1L, 10L);
    }

    @Test
    void 잘못된_페이지_크기는_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/hospitals")
                        .param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    void 페이지_번호가_0이면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/hospitals")
                        .param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    void 숫자가_아닌_위도는_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/hospitals")
                        .param("latitude", "not-a-number")
                        .param("longitude", "126.9780"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }
}
