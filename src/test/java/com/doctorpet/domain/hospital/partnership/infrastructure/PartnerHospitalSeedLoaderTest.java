package com.doctorpet.domain.hospital.partnership.infrastructure;

import com.doctorpet.domain.hospital.partnership.dto.PartnerHospitalSeedData;
import com.doctorpet.domain.hospital.entity.CapabilityValue;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PartnerHospitalSeedLoaderTest {

    @Test
    void 제휴_병원_JSON을_목록으로_읽는다() {
        PartnerHospitalSeedLoader loader =
                new PartnerHospitalSeedLoader(new ObjectMapper());

        List<PartnerHospitalSeedData> result = loader.load();

        assertThat(result).hasSize(100);
        assertThat(result)
                .extracting(seed -> seed.localGovernmentCode()
                        + "|" + seed.managementNumber())
                .doesNotHaveDuplicates();
        assertThat(result)
                .extracting(PartnerHospitalSeedData::localGovernmentCode)
                .doesNotContainNull();
        assertThat(result)
                .extracting(PartnerHospitalSeedData::managementNumber)
                .doesNotContainNull();
        assertThat(result)
                .allSatisfy(seed -> {
                    assertThat(seed.detail()).isNotNull();
                    assertThat(seed.detail().openHours()).isNotEmpty();
                    assertThat(seed.capabilities())
                            .contains(
                                    CapabilityValue.DOG,
                                    CapabilityValue.CAT,
                                    CapabilityValue.BLOOD_TEST,
                                    CapabilityValue.XRAY
                            );
                });
        assertThat(result)
                .extracting(PartnerHospitalSeedData::managementNumber)
                .contains(
                        "313000001020260004",
                        "313000001020160002",
                        "313000001020010004",
                        "313000001020130006",
                        "313000001020140007"
                );
    }
}
