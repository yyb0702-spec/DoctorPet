package com.doctorpet.domain.hospital.controller;

import com.doctorpet.domain.hospital.dto.response.HospitalDateAvailabilityResponse;
import com.doctorpet.domain.hospital.dto.response.HospitalSlotAvailabilityStatus;
import com.doctorpet.domain.hospital.dto.response.HospitalSlotLookupResponse;
import com.doctorpet.domain.hospital.dto.response.HospitalSlotResponse;
import com.doctorpet.domain.hospital.service.HospitalService;
import com.doctorpet.domain.hospital.service.HospitalSlotApplicationService;
import com.doctorpet.global.security.JwtTokenProvider;
import com.doctorpet.global.security.MemberBlacklistPort;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = HospitalController.class)
@AutoConfigureMockMvc(addFilters = false)
class HospitalSlotControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HospitalService hospitalService;

    @MockitoBean
    private HospitalSlotApplicationService hospitalSlotApplicationService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    // @WebMvcTest는 Filter 타입 빈(JwtAuthenticationFilter)을 addFilters=false여도 컨텍스트에
    // 생성하므로, 그 생성자 의존성인 JwtTokenProvider·MemberBlacklistPort가 없으면 컨텍스트 로딩
    // 자체가 실패한다(MemberBlacklistPort는 탈퇴 회원 Access Token 블랙리스트 체크용 — 리뷰 지적 P1 대응).
    @MockitoBean
    private MemberBlacklistPort memberBlacklistPort;

    @Test
    void 날짜를_지정하면_병원_예약_슬롯을_조회한다() throws Exception {
        LocalDate selectedDate = LocalDate.of(2026, 8, 1);
        HospitalSlotLookupResponse response =
                new HospitalSlotLookupResponse(
                        selectedDate,
                        List.of(new HospitalDateAvailabilityResponse(
                                selectedDate,
                                true
                        )),
                        List.of(new HospitalSlotResponse(
                                1L,
                                LocalDateTime.of(2026, 8, 1, 10, 0),
                                LocalDateTime.of(2026, 8, 1, 10, 30),
                                HospitalSlotAvailabilityStatus.AVAILABLE
                        ))
                );
        given(hospitalSlotApplicationService.getHospitalSlots(
                1L,
                selectedDate
        )).willReturn(response);

        mockMvc.perform(get("/api/hospitals/1/slots")
                        .param("date", "2026-08-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.selectedDate")
                        .value("2026-08-01"))
                .andExpect(jsonPath(
                        "$.data.dateAvailabilities[0].reservationAvailable"
                ).value(true))
                .andExpect(jsonPath("$.data.slots[0].slotId").value(1))
                .andExpect(jsonPath(
                        "$.data.slots[0].availabilityStatus"
                ).value("AVAILABLE"));

        verify(hospitalSlotApplicationService).getHospitalSlots(
                1L,
                selectedDate
        );
    }

    @Test
    void 날짜가_없으면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/hospitals/1/slots"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    void 날짜_형식이_잘못되면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/hospitals/1/slots")
                        .param("date", "2026-08-40"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }
}
