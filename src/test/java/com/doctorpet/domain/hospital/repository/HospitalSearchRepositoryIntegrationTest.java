package com.doctorpet.domain.hospital.repository;

import com.doctorpet.domain.hospital.dto.query.HospitalSearchCandidate;
import com.doctorpet.domain.hospital.dto.query.HospitalSearchCondition;
import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.CapabilityValue;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.entity.HospitalCapability;
import com.doctorpet.global.config.QuerydslConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@Import(QuerydslConfig.class)
class HospitalSearchRepositoryIntegrationTest {

    @Autowired
    private HospitalRepository hospitalRepository;

    @Autowired
    private HospitalCapabilityRepository hospitalCapabilityRepository;

    @Test
    void 폐업을_제외하고_요청한_역량을_모두_가진_병원만_조회한다() {
        Hospital matched = saveHospital(
                "MATCHED",
                "닥터펫 동물병원",
                BusinessStatus.OPEN,
                true
        );
        Hospital missingCapability = saveHospital(
                "MISSING",
                "닥터펫 한가지병원",
                BusinessStatus.OPEN,
                true
        );
        Hospital closed = saveHospital(
                "CLOSED",
                "닥터펫 폐업병원",
                BusinessStatus.CLOSED,
                true
        );

        hospitalCapabilityRepository.saveAll(List.of(
                HospitalCapability.create(
                        matched,
                        CapabilityValue.DOG
                ),
                HospitalCapability.create(
                        matched,
                        CapabilityValue.XRAY
                ),
                HospitalCapability.create(
                        missingCapability,
                        CapabilityValue.DOG
                ),
                HospitalCapability.create(
                        closed,
                        CapabilityValue.DOG
                ),
                HospitalCapability.create(
                        closed,
                        CapabilityValue.XRAY
                )
        ));
        hospitalCapabilityRepository.flush();

        HospitalSearchCondition condition =
                new HospitalSearchCondition(
                        "닥터펫",
                        null,
                        null,
                        null,
                        List.of(
                                CapabilityValue.DOG,
                                CapabilityValue.XRAY
                        ),
                        true
                );

        List<HospitalSearchCandidate> result =
                hospitalRepository.search(condition);

        assertThat(result)
                .extracting(candidate ->
                        candidate.hospital().getId())
                .containsExactly(matched.getId());
    }

    @Test
    void 좌표_반경_사각박스_밖의_병원은_후보에서_제외한다() {
        Hospital nearby = saveHospital(
                "NEARBY",
                "가까운 병원",
                BusinessStatus.OPEN,
                false
        );
        Hospital farAway = Hospital.createFromPublicData(
                "FAR",
                "LOCAL-GOV",
                "먼 병원",
                "02-1234-5678",
                "서울특별시",
                "서울특별시",
                "01234",
                new BigDecimal("127.5000"),
                new BigDecimal("38.0000"),
                null,
                BusinessStatus.OPEN,
                null,
                null,
                null
        );
        hospitalRepository.save(farAway);
        hospitalRepository.flush();

        HospitalSearchCondition condition =
                new HospitalSearchCondition(
                        null,
                        new BigDecimal("37.5665"),
                        new BigDecimal("126.9780"),
                        new BigDecimal("5"),
                        List.of(),
                        false
                );

        List<HospitalSearchCandidate> result =
                hospitalRepository.search(condition);

        assertThat(result)
                .extracting(candidate ->
                        candidate.hospital().getId())
                .contains(nearby.getId())
                .doesNotContain(farAway.getId());
    }

    private Hospital saveHospital(
            String managementNumber,
            String name,
            BusinessStatus businessStatus,
            boolean partner
    ) {
        Hospital hospital = Hospital.createFromPublicData(
                managementNumber,
                "LOCAL-GOV",
                name,
                "02-1234-5678",
                "서울특별시 중구 지번주소",
                "서울특별시 중구 도로명주소",
                "01234",
                new BigDecimal("126.9780"),
                new BigDecimal("37.5665"),
                null,
                businessStatus,
                null,
                null,
                null
        );
        if (partner) {
            hospital.markAsPartner();
        }
        return hospitalRepository.save(hospital);
    }
}
