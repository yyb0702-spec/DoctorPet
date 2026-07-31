package com.doctorpet.domain.hospital.controller;

import com.doctorpet.domain.hospital.dto.response.HospitalSearchPageResponse;
import com.doctorpet.domain.hospital.dto.response.HospitalSearchResponse;
import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.PartnershipStatus;
import com.doctorpet.domain.hospital.service.HospitalService;
import com.doctorpet.domain.hospital.service.HospitalSlotApplicationService;
import com.doctorpet.global.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = HospitalController.class)
@AutoConfigureMockMvc(addFilters = false)
class HospitalSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HospitalService hospitalService;

    @MockitoBean
    private HospitalSlotApplicationService hospitalSlotApplicationService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

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
                        true
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
                eq(false),
                eq(false),
                eq(1),
                eq(20),
                eq("name")
        );
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
