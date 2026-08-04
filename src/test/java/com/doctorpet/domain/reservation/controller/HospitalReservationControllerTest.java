package com.doctorpet.domain.reservation.controller;

import com.doctorpet.domain.reservation.dto.request.ReservationRejectRequest;
import com.doctorpet.domain.reservation.dto.request.ReservationNoShowRequest;
import com.doctorpet.domain.reservation.dto.request.ReservationNoShowRestoreRequest;
import com.doctorpet.domain.reservation.entity.status.ReservationRejectReason;
import com.doctorpet.domain.reservation.service.HospitalReservationApplicationService;
import com.doctorpet.global.response.ApiResponse;
import com.doctorpet.global.security.MemberPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HospitalReservationControllerTest {

    @Mock
    private HospitalReservationApplicationService hospitalReservationService;

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
        ReservationRejectRequest request = new ReservationRejectRequest("진료 불가");

        ApiResponse<Void> response = hospitalReservationController.reject(principal, 10L, request);

        verify(hospitalReservationService).reject(
                50L,
                10L,
                ReservationRejectReason.TREATMENT_UNAVAILABLE
        );
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

    @Test
    void completeTreatment_delegatesAuthenticatedStaffAndReservationId() {
        ApiResponse<Void> response = hospitalReservationController.completeTreatment(principal, 10L);

        verify(hospitalReservationService).completeTreatment(50L, 10L);
        assertThat(response.code()).isEqualTo("SUCCESS");
    }

    @Test
    void confirmNoShow_delegatesReasonAndReservationId() {
        ReservationNoShowRequest request = new ReservationNoShowRequest("미방문 확인");

        ApiResponse<Void> response = hospitalReservationController.confirmNoShow(
                principal,
                10L,
                request
        );

        verify(hospitalReservationService).confirmNoShow(
                50L,
                10L,
                "미방문 확인"
        );
        assertThat(response.code()).isEqualTo("SUCCESS");
    }

    @Test
    void restoreNoShow_delegatesReasonAndReservationId() {
        ReservationNoShowRestoreRequest request =
                new ReservationNoShowRestoreRequest("현장 접수 확인");

        ApiResponse<Void> response = hospitalReservationController.restoreNoShow(
                principal,
                10L,
                request
        );

        verify(hospitalReservationService).restoreNoShow(
                50L,
                10L,
                "현장 접수 확인"
        );
        assertThat(response.code()).isEqualTo("SUCCESS");
    }

    @Test
    void getReservations_delegatesAuthenticatedStaffAndPaging() {
        when(hospitalReservationService.findHospitalReservations(
                50L,
                "REQUESTED",
                0,
                20
        ))
                .thenReturn(Page.empty());
        ApiResponse<?> response = hospitalReservationController.getReservations(
                principal,
                "REQUESTED",
                0,
                20
        );

        verify(hospitalReservationService).findHospitalReservations(
                50L,
                "REQUESTED",
                0,
                20
        );
        assertThat(response.code()).isEqualTo("SUCCESS");
    }
}
