package com.doctorpet.domain.hospital.repository;

import com.doctorpet.domain.hospital.dto.query.HospitalSearchCandidate;
import com.doctorpet.domain.hospital.dto.query.HospitalSearchCondition;
import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.CapabilityValue;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.entity.HospitalCapability;
import com.doctorpet.domain.hospital.entity.HospitalDetail;
import com.doctorpet.domain.hospital.model.DailyOperatingHours;
import com.doctorpet.global.config.QuerydslConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@Import(QuerydslConfig.class)
class HospitalSearchRepositoryIntegrationTest {

    private static final Map<DayOfWeek, DailyOperatingHours> OPEN_HOURS =
            Map.of(
                    DayOfWeek.MONDAY,
                    new DailyOperatingHours(
                            LocalTime.of(9, 0),
                            LocalTime.of(18, 0)
                    )
            );

    @Autowired
    private HospitalRepository hospitalRepository;

    @Autowired
    private HospitalCapabilityRepository hospitalCapabilityRepository;

    @Autowired
    private HospitalDetailRepository hospitalDetailRepository;

    @Test
    void 전체_개수와_현재_페이지를_분리해_조회한다() {
        saveHospital(
                "PAGE-C",
                "페이지테스트 다병원",
                BusinessStatus.OPEN,
                false
        );
        Hospital second =
                saveHospital(
                        "PAGE-B",
                        "페이지테스트 나병원",
                        BusinessStatus.OPEN,
                        false
                );
        saveHospital(
                "PAGE-A",
                "페이지테스트 가병원",
                BusinessStatus.OPEN,
                false
        );
        hospitalRepository.flush();

        HospitalSearchCondition condition =
                conditionWithKeyword("페이지테스트");

        long totalElements = hospitalRepository.count(condition);
        List<HospitalSearchCandidate> content =
                hospitalRepository.search(condition, 1L, 1);

        assertThat(totalElements).isEqualTo(3);
        assertThat(content)
                .extracting(HospitalSearchCandidate::hospitalId)
                .containsExactly(second.getId());
    }

    @Test
    void 지역_축종_필수역량_시설조건을_모두_만족하는_병원만_조회한다() {
        Hospital matched = saveHospital(
                "FILTER-MATCHED",
                "조건 일치 병원",
                BusinessStatus.OPEN,
                true
        );
        Hospital noSurgery = saveHospital(
                "FILTER-NO-SURGERY",
                "수술 불가 병원",
                BusinessStatus.OPEN,
                true
        );

        hospitalCapabilityRepository.saveAll(List.of(
                HospitalCapability.create(matched, CapabilityValue.CAT),
                HospitalCapability.create(matched, CapabilityValue.XRAY),
                HospitalCapability.create(noSurgery, CapabilityValue.CAT),
                HospitalCapability.create(noSurgery, CapabilityValue.XRAY)
        ));
        hospitalDetailRepository.saveAll(List.of(
                HospitalDetail.create(
                        matched,
                        OPEN_HOURS,
                        true,
                        false,
                        false,
                        false
                ),
                HospitalDetail.create(
                        noSurgery,
                        OPEN_HOURS,
                        false,
                        false,
                        false,
                        false
                )
        ));
        hospitalCapabilityRepository.flush();
        hospitalDetailRepository.flush();

        HospitalSearchCondition condition = new HospitalSearchCondition(
                null,
                "중구",
                null,
                null,
                null,
                List.of(CapabilityValue.XRAY),
                List.of(CapabilityValue.CAT),
                true,
                null,
                null,
                null,
                false
        );

        List<HospitalSearchCandidate> result =
                hospitalRepository.search(condition);

        assertThat(result)
                .extracting(HospitalSearchCandidate::hospitalId)
                .containsExactly(matched.getId());
    }

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
                        null,
                        List.of(
                                CapabilityValue.DOG,
                                CapabilityValue.XRAY
                        ),
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        true
                );

        List<HospitalSearchCandidate> result =
                hospitalRepository.search(condition);

        assertThat(result)
                .extracting(HospitalSearchCandidate::hospitalId)
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
                        null,
                        new BigDecimal("37.5665"),
                        new BigDecimal("126.9780"),
                        new BigDecimal("5"),
                        List.of(),
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        false
                );

        List<HospitalSearchCandidate> result =
                hospitalRepository.search(condition);

        assertThat(result)
                .extracting(HospitalSearchCandidate::hospitalId)
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

    private HospitalSearchCondition conditionWithKeyword(String keyword) {
        return new HospitalSearchCondition(
                keyword,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                false
        );
    }
}
