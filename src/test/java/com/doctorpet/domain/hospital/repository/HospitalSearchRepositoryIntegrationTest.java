package com.doctorpet.domain.hospital.repository;

import com.doctorpet.domain.hospital.dto.query.HospitalSearchCandidate;
import com.doctorpet.domain.hospital.dto.query.HospitalSearchCondition;
import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.CapabilityValue;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.entity.HospitalCapability;
import com.doctorpet.domain.hospital.entity.HospitalDetail;
import com.doctorpet.domain.hospital.model.CapabilityMatchMode;
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
                hospitalRepository.searchPage(condition, 1L, 1);

        assertThat(totalElements).isEqualTo(3);
        assertThat(content)
                .extracting(HospitalSearchCandidate::hospitalId)
                .containsExactly(second.getId());
    }

    @Test
    void 최초_진입_목록은_제휴_병원을_먼저_채우고_비제휴_병원으로_보충한다() {
        Hospital partnerB = saveHospital(
                "INITIAL-PARTNER-B",
                "최초진입 나제휴병원",
                BusinessStatus.OPEN,
                true
        );
        Hospital nonPartnerA = saveHospital(
                "INITIAL-NON-PARTNER-A",
                "최초진입 가비제휴병원",
                BusinessStatus.OPEN,
                false
        );
        Hospital partnerC = saveHospital(
                "INITIAL-PARTNER-C",
                "최초진입 다제휴병원",
                BusinessStatus.OPEN,
                true
        );
        hospitalRepository.flush();

        HospitalSearchCondition condition =
                conditionWithKeyword("최초진입");

        List<HospitalSearchCandidate> content =
                hospitalRepository.searchPartnerFirstPage(
                        condition,
                        0L,
                        3
                );

        assertThat(content)
                .extracting(HospitalSearchCandidate::hospitalId)
                .containsExactly(
                        partnerB.getId(),
                        partnerC.getId(),
                        nonPartnerA.getId()
                );
    }

    @Test
    void 지역_축종_필수역량_시설조건을_모두_만족하는_병원만_조회한다() {
        Hospital matched = saveHospital(
                "FILTER-MATCHED",
                "필터격리검증 조건 일치 병원",
                BusinessStatus.OPEN,
                true
        );
        Hospital noSurgery = saveHospital(
                "FILTER-NO-SURGERY",
                "필터격리검증 수술 불가 병원",
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
                "필터격리검증",
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
                hospitalRepository.searchAll(condition);
        long totalElements = hospitalRepository.count(condition);

        assertThat(result)
                .extracting(HospitalSearchCandidate::hospitalId)
                .containsExactly(matched.getId());
        assertThat(totalElements).isEqualTo(1);
    }

    @Test
    void 복수_시설조건에서도_검색_결과와_개수가_같다() {
        Hospital matched = saveHospital(
                "MULTI-FACILITY-MATCHED",
                "복수시설격리 조건 일치 병원",
                BusinessStatus.OPEN,
                true
        );
        Hospital missingNightCare = saveHospital(
                "MULTI-FACILITY-MISSING",
                "복수시설격리 야간진료 불가 병원",
                BusinessStatus.OPEN,
                true
        );

        hospitalDetailRepository.saveAll(List.of(
                HospitalDetail.create(
                        matched,
                        OPEN_HOURS,
                        true,
                        true,
                        true,
                        true
                ),
                HospitalDetail.create(
                        missingNightCare,
                        OPEN_HOURS,
                        true,
                        true,
                        false,
                        true
                )
        ));
        hospitalDetailRepository.flush();

        HospitalSearchCondition condition = new HospitalSearchCondition(
                "복수시설격리",
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                true,
                true,
                true,
                true,
                false
        );

        List<HospitalSearchCandidate> result =
                hospitalRepository.searchAll(condition);
        long totalElements = hospitalRepository.count(condition);

        assertThat(result)
                .extracting(HospitalSearchCandidate::hospitalId)
                .containsExactly(matched.getId());
        assertThat(totalElements).isEqualTo(result.size());
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
                hospitalRepository.searchAll(condition);

        assertThat(result)
                .extracting(HospitalSearchCandidate::hospitalId)
                .containsExactly(matched.getId());
    }

    @Test
    void ANY_검색은_요청한_진료역량을_하나라도_가진_병원을_조회한다() {
        Hospital allMatched = saveHospital(
                "ANY-ALL",
                "역량OR검색 전체일치병원",
                BusinessStatus.OPEN,
                true
        );
        Hospital partiallyMatched = saveHospital(
                "ANY-PARTIAL",
                "역량OR검색 부분일치병원",
                BusinessStatus.OPEN,
                true
        );
        Hospital notMatched = saveHospital(
                "ANY-NONE",
                "역량OR검색 불일치병원",
                BusinessStatus.OPEN,
                true
        );
        hospitalCapabilityRepository.saveAll(List.of(
                HospitalCapability.create(allMatched, CapabilityValue.XRAY),
                HospitalCapability.create(allMatched, CapabilityValue.ULTRASOUND),
                HospitalCapability.create(partiallyMatched, CapabilityValue.XRAY),
                HospitalCapability.create(notMatched, CapabilityValue.BLOOD_TEST)
        ));
        hospitalCapabilityRepository.flush();

        HospitalSearchCondition condition = new HospitalSearchCondition(
                "역량OR검색",
                null,
                null,
                null,
                null,
                List.of(CapabilityValue.XRAY, CapabilityValue.ULTRASOUND),
                List.of(),
                null,
                null,
                null,
                null,
                false
        );

        List<HospitalSearchCandidate> result = hospitalRepository.searchAll(
                condition,
                CapabilityMatchMode.ANY
        );

        assertThat(result)
                .extracting(HospitalSearchCandidate::hospitalId)
                .containsExactlyInAnyOrder(allMatched.getId(), partiallyMatched.getId());
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
                hospitalRepository.searchAll(condition);

        assertThat(result)
                .extracting(HospitalSearchCandidate::hospitalId)
                .contains(nearby.getId())
                .doesNotContain(farAway.getId());
    }

    @Test
    void 거리순_페이지는_가까운_병원부터_중복_없이_조회한다() {
        Hospital nearest = saveHospitalAt(
                "DISTANCE-NEAREST",
                "SCOPE69 가까운 병원",
                "126.9780",
                "37.5665"
        );
        Hospital middle = saveHospitalAt(
                "DISTANCE-MIDDLE",
                "SCOPE69 중간 병원",
                "127.0280",
                "37.5665"
        );
        Hospital farthest = saveHospitalAt(
                "DISTANCE-FARTHEST",
                "SCOPE69 먼 병원",
                "127.1280",
                "37.5665"
        );
        hospitalRepository.flush();

        HospitalSearchCondition condition = conditionWithKeyword("SCOPE69");

        List<HospitalSearchCandidate> firstPage =
                hospitalRepository.searchDistancePage(
                        condition,
                        new BigDecimal("37.5665"),
                        new BigDecimal("126.9780"),
                        0L,
                        2
                );
        List<HospitalSearchCandidate> secondPage =
                hospitalRepository.searchDistancePage(
                        condition,
                        new BigDecimal("37.5665"),
                        new BigDecimal("126.9780"),
                        2L,
                        2
                );

        assertThat(firstPage)
                .extracting(HospitalSearchCandidate::hospitalId)
                .containsExactly(nearest.getId(), middle.getId());
        assertThat(secondPage)
                .extracting(HospitalSearchCandidate::hospitalId)
                .containsExactly(farthest.getId());
    }

    @Test
    void 거리순_페이지는_좌표_없는_병원을_마지막에_배치한다() {
        Hospital withCoordinates = saveHospitalAt(
                "DISTANCE-WITH-COORDINATES",
                "NULL-SCOPE 좌표 병원",
                "126.9780",
                "37.5665"
        );
        Hospital withoutCoordinates = saveHospitalWithoutCoordinates(
                "DISTANCE-WITHOUT-COORDINATES",
                "NULL-SCOPE 무좌표 병원"
        );
        hospitalRepository.flush();

        List<HospitalSearchCandidate> result =
                hospitalRepository.searchDistancePage(
                        conditionWithKeyword("NULL-SCOPE"),
                        new BigDecimal("37.5665"),
                        new BigDecimal("126.9780"),
                        0L,
                        10
                );

        assertThat(result)
                .extracting(HospitalSearchCandidate::hospitalId)
                .containsExactly(
                        withCoordinates.getId(),
                        withoutCoordinates.getId()
                );
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

    private Hospital saveHospitalAt(
            String managementNumber,
            String name,
            String longitude,
            String latitude
    ) {
        Hospital hospital = Hospital.createFromPublicData(
                managementNumber,
                "LOCAL-GOV",
                name,
                "02-1234-5678",
                "서울특별시 중구 지번주소",
                "서울특별시 중구 도로명주소",
                "01234",
                new BigDecimal(longitude),
                new BigDecimal(latitude),
                null,
                BusinessStatus.OPEN,
                null,
                null,
                null
        );
        return hospitalRepository.save(hospital);
    }

    private Hospital saveHospitalWithoutCoordinates(
            String managementNumber,
            String name
    ) {
        Hospital hospital = Hospital.createFromPublicData(
                managementNumber,
                "LOCAL-GOV",
                name,
                "02-1234-5678",
                "서울특별시 중구 지번주소",
                "서울특별시 중구 도로명주소",
                "01234",
                null,
                null,
                null,
                BusinessStatus.OPEN,
                null,
                null,
                null
        );
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
