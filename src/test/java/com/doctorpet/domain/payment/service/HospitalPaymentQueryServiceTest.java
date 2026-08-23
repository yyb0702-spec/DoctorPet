package com.doctorpet.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.doctorpet.domain.hospital.exception.HospitalErrorCode;
import com.doctorpet.domain.member.dto.response.MemberResponse;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.domain.payment.dto.response.HospitalPaymentListItemResponse;
import com.doctorpet.domain.payment.repository.HospitalPaymentQueryRepository;
import com.doctorpet.global.exception.ServiceException;
import java.util.List;
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
class HospitalPaymentQueryServiceTest {

    private static final Long MEMBER_ID = 10L;
    private static final Long HOSPITAL_ID = 5L;

    @Mock
    private MemberService memberService;

    @Mock
    private HospitalPaymentQueryRepository hospitalPaymentQueryRepository;

    @InjectMocks
    private HospitalPaymentQueryService service;

    @Test
    @DisplayName("병원 스태프면 인증 principal의 자병원으로 조회를 위임한다")
    void getHospitalPayments_staff_delegatesWithOwnHospital() {
        Pageable pageable = PageRequest.of(0, 20);
        given(memberService.getMyInfo(MEMBER_ID)).willReturn(staff(HOSPITAL_ID));
        Page<HospitalPaymentListItemResponse> page = new PageImpl<>(List.of(), pageable, 0);
        given(hospitalPaymentQueryRepository.findActivePaymentsByHospitalId(HOSPITAL_ID, pageable))
                .willReturn(page);

        assertThat(service.getHospitalPayments(MEMBER_ID, pageable)).isSameAs(page);
    }

    @Test
    @DisplayName("스태프가 아니거나 소속 병원이 없으면 NOT_OWN_HOSPITAL로 거절하고 조회하지 않는다")
    void getHospitalPayments_notStaff_rejects() {
        given(memberService.getMyInfo(MEMBER_ID)).willReturn(guardian());

        assertThatThrownBy(() -> service.getHospitalPayments(MEMBER_ID, PageRequest.of(0, 20)))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(HospitalErrorCode.NOT_OWN_HOSPITAL);

        verifyNoInteractions(hospitalPaymentQueryRepository);
    }

    private MemberResponse staff(Long hospitalId) {
        return new MemberResponse(
                MEMBER_ID, "staff@example.com", "staff", "010-0000-0000",
                MemberRole.HOSPITAL_STAFF, hospitalId
        );
    }

    private MemberResponse guardian() {
        return new MemberResponse(
                MEMBER_ID, "guardian@example.com", "guardian", "010-1111-2222",
                MemberRole.GUARDIAN, null
        );
    }
}
