package com.doctorpet.domain.hospital.publicdata.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.publicdata.config.AnimalHospitalApiProperties;
import com.doctorpet.domain.hospital.publicdata.dto.response.AnimalHospitalItem;
import com.doctorpet.domain.hospital.publicdata.mapper.AnimalHospitalCoordinateConverter;
import com.doctorpet.domain.hospital.publicdata.mapper.AnimalHospitalDataNormalizer;
import com.doctorpet.domain.hospital.publicdata.mapper.AnimalHospitalEntityMapper;
import com.doctorpet.domain.hospital.repository.HospitalRepository;
import com.doctorpet.domain.reservation.service.HospitalReservationApplicationService;
import com.doctorpet.global.config.QuerydslConfig;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import static org.mockito.Mockito.mock;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(QuerydslConfig.class)
class AnimalHospitalIdentityIntegrationTest {

    private static final String SHARED_MANAGEMENT_NUMBER =
            "IDENTITY-INTEGRATION-SHARED-MGMT";

    @Autowired
    private HospitalRepository hospitalRepository;

    private AnimalHospitalImportService importService;

    @BeforeEach
    void setUp() {
        AnimalHospitalApiProperties properties =
                new AnimalHospitalApiProperties(
                        null,
                        null,
                        100,
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(10),
                        "EPSG:5174",
                        3,
                        Duration.ofMinutes(5)
                );
        importService = new AnimalHospitalImportService(
                hospitalRepository,
                new AnimalHospitalDataNormalizer(
                        new AnimalHospitalCoordinateConverter(properties)
                ),
                new AnimalHospitalEntityMapper(),
                mock(HospitalReservationApplicationService.class)
        );
    }

    @Test
    void 같은_관리번호라도_지자체_코드가_다르면_별도_병원으로_저장한다() {
        Hospital first = importService.importHospital(createItem(
                "IDENTITY-LOCAL-A",
                SHARED_MANAGEMENT_NUMBER,
                "복합키 검증 병원 A"
        ));
        Hospital second = importService.importHospital(createItem(
                "IDENTITY-LOCAL-B",
                SHARED_MANAGEMENT_NUMBER,
                "복합키 검증 병원 B"
        ));
        hospitalRepository.flush();

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(hospitalRepository.findByLocalGovCodeAndMgmtNo(
                "IDENTITY-LOCAL-A",
                SHARED_MANAGEMENT_NUMBER
        )).contains(first);
        assertThat(hospitalRepository.findByLocalGovCodeAndMgmtNo(
                "IDENTITY-LOCAL-B",
                SHARED_MANAGEMENT_NUMBER
        )).contains(second);
    }

    @Test
    void 동일한_지자체_코드와_관리번호를_다시_수집하면_기존_병원을_갱신한다() {
        Hospital original = importService.importHospital(createItem(
                "IDENTITY-LOCAL-C",
                SHARED_MANAGEMENT_NUMBER,
                "갱신 전 병원"
        ));
        hospitalRepository.flush();

        Hospital updated = importService.importHospital(createItem(
                "IDENTITY-LOCAL-C",
                SHARED_MANAGEMENT_NUMBER,
                "갱신 후 병원"
        ));
        hospitalRepository.flush();

        assertThat(updated.getId()).isEqualTo(original.getId());
        assertThat(updated.getName()).isEqualTo("갱신 후 병원");
    }

    private AnimalHospitalItem createItem(
            String localGovernmentCode,
            String managementNumber,
            String name
    ) {
        return new AnimalHospitalItem(
                name,
                "",
                "191409.240701388",
                "451377.18304308",
                "2026-07-25 22:05:05",
                "I",
                "0000",
                "정상",
                "2026-07-24 15:52:02",
                "",
                "2026-07-24",
                "144.6",
                "",
                "서울특별시 테스트구 지번주소",
                managementNumber,
                localGovernmentCode,
                "000",
                "서울특별시 테스트구 도로명주소",
                "00000",
                "",
                "01",
                "영업/정상",
                "",
                "",
                "02-0000-0000"
        );
    }
}
