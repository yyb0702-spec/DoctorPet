package com.doctorpet.domain.hospital.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HospitalCapabilityTest {

    @Test
    void 병원에_검사_역량을_등록한다() {
        Hospital hospital = createHospital();

        HospitalCapability capability = HospitalCapability.create(
                hospital,
                CapabilityValue.XRAY
        );

        // 역량 값에 연결된 분류가 자동으로 설정되는지 확인합니다.
        assertThat(capability.getHospital()).isSameAs(hospital);
        assertThat(capability.getCapabilityType())
                .isEqualTo(CapabilityType.EXAM);
        assertThat(capability.getCapabilityValue())
                .isEqualTo(CapabilityValue.XRAY);
    }

    @Test
    void 병원이_없으면_역량을_생성하지_않는다() {
        assertThatThrownBy(() -> HospitalCapability.create(
                null,
                CapabilityValue.XRAY
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("병원이 필요합니다.");
    }

    @Test
    void 역량_값이_없으면_역량을_생성하지_않는다() {
        assertThatThrownBy(() -> HospitalCapability.create(
                createHospital(),
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("진료 역량이 필요합니다.");
    }

    private Hospital createHospital() {
        return Hospital.createFromPublicData(
                "313000001020260004",
                "3130000",
                "시그널 동물의료센터",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                BusinessStatus.OPEN,
                null,
                null,
                null
        );
    }
}
