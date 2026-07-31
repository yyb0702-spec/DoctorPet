package com.doctorpet.domain.reservation.controller;

import com.doctorpet.domain.reservation.dto.request.ReservationRejectRequest;
import com.doctorpet.domain.reservation.service.HospitalReservationService;
import com.doctorpet.global.response.ApiResponse;
import com.doctorpet.global.security.MemberPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class HospitalReservationControllerTest {

    @Mock
    private HospitalReservationService hospitalReservationService;

    @InjectMocks
    private HospitalReservationController hospitalReservationController;

    private final MemberPrincipal principal = new MemberPrincipal(50L, "staff@hospital.com", "HOSPITAL_STAFF");

    @Test
    void approve_delegatesAuthenticatedStaffAndReservationId() {
        ApiResponse<Void> response = hospitalReservationController.approve(principal, 10L);

        verify(hospitalReservationService).approve(50L, 10L);
        assertThat(response.code()).isEqualTo("SUCCESS");
    }

    @Test
    void reject_delegatesReasonAndReservationId() {
        ReservationRejectRequest request = new ReservationRejectRequest("진료 슬롯 부족");

        ApiResponse<Void> response = hospitalReservationController.reject(principal, 10L, request);

        verify(hospitalReservationService).reject(50L, 10L, "진료 슬롯 부족");
        assertThat(response.code()).isEqualTo("SUCCESS");
    }

    @Test
    void checkIn_delegatesAuthenticatedStaffAndReservationId() {
        ApiResponse<Void> response = hospitalReservationController.checkIn(principal, 10L);

        verify(hospitalReservationService).checkIn(50L, 10L);
        assertThat(response.code()).isEqualTo("SUCCESS");
    }

    @Test
    void startTreatment_delegatesAuthenticatedStaffAndReservationId() {
        ApiResponse<Void> response = hospitalReservationController.startTreatment(principal, 10L);

        verify(hospitalReservationService).startTreatment(50L, 10L);
        assertThat(response.code()).isEqualTo("SUCCESS");
    }
}
