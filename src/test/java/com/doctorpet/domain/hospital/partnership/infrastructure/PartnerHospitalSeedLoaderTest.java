package com.doctorpet.domain.hospital.partnership.infrastructure;

import com.doctorpet.domain.hospital.partnership.dto.PartnerHospitalSeedData;
import com.doctorpet.domain.hospital.entity.CapabilityValue;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.DayOfWeek;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PartnerHospitalSeedLoaderTest {

    @Test
    void 제휴_병원_JSON을_목록으로_읽는다() {
        PartnerHospitalSeedLoader loader =
                new PartnerHospitalSeedLoader(new ObjectMapper());

        List<PartnerHospitalSeedData> result = loader.load();

        // 실제 공공데이터 병원과 매칭할 복합 키가 JSON에서 읽히는지 확인합니다.
        assertThat(result).hasSize(5);
        assertThat(result.get(0).localGovernmentCode())
                .isEqualTo("3130000");
        assertThat(result.get(0).managementNumber())
                .isEqualTo("313000001020260004");
        // 제휴 병원의 운영시간과 역량까지 JSON에서 변환되는지 확인합니다.
        assertThat(result.get(0).detail().openHours())
                .containsKey(DayOfWeek.MONDAY);
        assertThat(result.get(0).detail().openHours()
                .get(DayOfWeek.MONDAY).openTime())
                .isEqualTo("09:00");
        assertThat(result.get(0).detail().surgeryAvailable()).isTrue();
        assertThat(result.get(0).capabilities())
                .contains(
                        CapabilityValue.DOG,
                        CapabilityValue.XRAY,
                        CapabilityValue.CT
                );
    }
}
