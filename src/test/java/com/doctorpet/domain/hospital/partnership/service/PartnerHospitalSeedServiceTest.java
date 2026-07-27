package com.doctorpet.domain.hospital.partnership.service;

import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.entity.PartnershipStatus;
import com.doctorpet.domain.hospital.partnership.dto.PartnerHospitalSeedData;
import com.doctorpet.domain.hospital.repository.HospitalRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class PartnerHospitalSeedServiceTest {

    @Mock
    private HospitalRepository hospitalRepository;

    @Test
    void 복합_키로_매칭한_병원을_제휴_상태로_전환한다() {
        Hospital hospital = createHospital();
        given(hospitalRepository.findByLocalGovCodeAndMgmtNo(
                "3130000",
                "313000001020260004"
        )).willReturn(Optional.of(hospital));
        PartnerHospitalSeedService service =
                new PartnerHospitalSeedService(hospitalRepository);
        PartnerHospitalSeedData seedData = new PartnerHospitalSeedData(
                "3130000",
                "313000001020260004",
                "시그널 동물의료센터"
        );

        int appliedCount = service.applyPartnerships(List.of(seedData));

        // 병원명이 아니라 지자체 코드·관리번호로 찾은 병원이 제휴 상태가 되는지 확인합니다.
        assertThat(appliedCount).isEqualTo(1);
        assertThat(hospital.getPartnershipStatus())
                .isEqualTo(PartnershipStatus.PARTNER);
    }

    private Hospital createHospital() {
        return Hospital.createFromPublicData(
                "313000001020260004",
                "3130000",
                "시그널 동물의료센터",
                "02-2088-0922",
                "서울특별시 마포구 성산동",
                "서울특별시 마포구 모래내로1길 9",
                "03938",
                null,
                null,
                LocalDate.of(2026, 7, 24),
                BusinessStatus.OPEN,
                null,
                null,
                null
        );
    }
}
