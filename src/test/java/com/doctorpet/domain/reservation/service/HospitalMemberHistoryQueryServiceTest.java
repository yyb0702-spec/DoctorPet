package com.doctorpet.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.doctorpet.domain.hospital.exception.HospitalErrorCode;
import com.doctorpet.domain.member.dto.response.MemberResponse;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.domain.reservation.dto.response.HospitalMemberHistoryItemResponse;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.exception.ReservationErrorCode;
import com.doctorpet.domain.reservation.repository.HospitalMemberHistoryQueryRepository;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.global.exception.ServiceException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class HospitalMemberHistoryQueryServiceTest {

    private static final Long STAFF_ID = 10L;
    private static final Long HOSPITAL_ID = 5L;
    private static final Long RESERVATION_ID = 500L;
    private static final Long TARGET_MEMBER_ID = 31L;

    @Mock
    private MemberService memberService;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private HospitalMemberHistoryQueryRepository hospitalMemberHistoryQueryRepository;

    @Mock
    private Reservation reservation;

    @InjectMocks
    private HospitalMemberHistoryQueryService service;

    @Test
    @DisplayName("자병원 예약이면 예약의 회원 이력을 자병원 범위로 위임한다")
    void getMemberHistory_ownReservation_delegatesWithReservationMember() {
        Pageable pageable = PageRequest.of(0, 20);
        given(memberService.getMyInfo(STAFF_ID)).willReturn(staff(HOSPITAL_ID));
        given(reservationRepository.findByIdAndHospitalId(RESERVATION_ID, HOSPITAL_ID))
                .willReturn(Optional.of(reservation));
        given(reservation.getMemberId()).willReturn(TARGET_MEMBER_ID);
        Page<HospitalMemberHistoryItemResponse> page = new PageImpl<>(List.of(), pageable, 0);
        given(hospitalMemberHistoryQueryRepository.findMemberHistory(HOSPITAL_ID, TARGET_MEMBER_ID, pageable))
                .willReturn(page);

        assertThat(service.getMemberHistory(STAFF_ID, RESERVATION_ID, pageable)).isSameAs(page);
    }

    @Test
    @DisplayName("예약이 자병원 것이 아니면(또는 없으면) 404로 끝내고 이력을 조회하지 않는다")
    void getMemberHistory_notOwnReservation_notFound() {
        given(memberService.getMyInfo(STAFF_ID)).willReturn(staff(HOSPITAL_ID));
        given(reservationRepository.findByIdAndHospitalId(RESERVATION_ID, HOSPITAL_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMemberHistory(STAFF_ID, RESERVATION_ID, PageRequest.of(0, 20)))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(ReservationErrorCode.RESERVATION_NOT_FOUND);

        verifyNoInteractions(hospitalMemberHistoryQueryRepository);
    }

    @Test
    @DisplayName("스태프가 아니면 NOT_OWN_HOSPITAL로 거절하고 예약 조회도 하지 않는다")
    void getMemberHistory_notStaff_rejects() {
        given(memberService.getMyInfo(STAFF_ID)).willReturn(guardian());

        assertThatThrownBy(() -> service.getMemberHistory(STAFF_ID, RESERVATION_ID, PageRequest.of(0, 20)))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(HospitalErrorCode.NOT_OWN_HOSPITAL);

        verifyNoInteractions(reservationRepository);
        verifyNoInteractions(hospitalMemberHistoryQueryRepository);
    }

    private MemberResponse staff(Long hospitalId) {
        return new MemberResponse(
                STAFF_ID, "staff@example.com", "staff", "010-0000-0000",
                MemberRole.HOSPITAL_STAFF, hospitalId
        );
    }

    private MemberResponse guardian() {
        return new MemberResponse(
                STAFF_ID, "guardian@example.com", "guardian", "010-1111-2222",
                MemberRole.GUARDIAN, null
        );
    }
}
