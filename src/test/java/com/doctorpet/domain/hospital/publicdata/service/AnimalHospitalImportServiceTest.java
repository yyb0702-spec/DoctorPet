package com.doctorpet.domain.hospital.publicdata.service;

import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.entity.PartnershipStatus;
import com.doctorpet.domain.hospital.publicdata.config.AnimalHospitalApiProperties;
import com.doctorpet.domain.hospital.publicdata.dto.response.AnimalHospitalItem;
import com.doctorpet.domain.hospital.publicdata.mapper.AnimalHospitalCoordinateConverter;
import com.doctorpet.domain.hospital.publicdata.mapper.AnimalHospitalDataNormalizer;
import com.doctorpet.domain.hospital.publicdata.mapper.AnimalHospitalEntityMapper;
import com.doctorpet.domain.hospital.repository.HospitalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class AnimalHospitalImportServiceTest {

    @Mock
    private HospitalRepository hospitalRepository;

    private final AnimalHospitalDataNormalizer normalizer =
            new AnimalHospitalDataNormalizer(
                    new AnimalHospitalCoordinateConverter(properties())
            );

    private final AnimalHospitalEntityMapper entityMapper =
            new AnimalHospitalEntityMapper();

    private AnimalHospitalImportService importService;

    @BeforeEach
    void setUp() {
        // Mock Repository와 실제 변환 객체를 사용해 테스트 대상 서비스를 구성합니다.
        importService = new AnimalHospitalImportService(
                hospitalRepository,
                normalizer,
                entityMapper
        );
    }

    @Test
    void 기존_병원이_없으면_비제휴_병원으로_저장한다() {
        AnimalHospitalItem item = createItem("새 병원", "02-1234-5678");
        given(hospitalRepository.findByLocalGovCodeAndMgmtNo(
                "3130000",
                "313000001020260004"
        )).willReturn(Optional.empty());
        given(hospitalRepository.save(any(Hospital.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        Hospital result = importService.importHospital(item);

        // 신규 공공데이터 병원이 비제휴 상태로 저장되는지 확인합니다.
        assertThat(result.getName()).isEqualTo("새 병원");
        assertThat(result.getPartnershipStatus())
                .isEqualTo(PartnershipStatus.NON_PARTNER);
        then(hospitalRepository).should().save(any(Hospital.class));
    }

    @Test
    void 기존_병원이_있으면_기본정보만_갱신한다() {
        Hospital existingHospital = Hospital.createFromPublicData(
                "313000001020260004",
                "3130000",
                "기존 병원",
                null,
                null,
                null,
                null,
                null,
                null,
                LocalDate.of(2026, 7, 20),
                BusinessStatus.OPEN,
                null,
                null,
                null
        );
        existingHospital.markAsPartner();
        given(hospitalRepository.findByLocalGovCodeAndMgmtNo(
                "3130000",
                "313000001020260004"
        )).willReturn(Optional.of(existingHospital));

        Hospital result = importService.importHospital(
                createItem("변경된 병원", "02-2088-0922")
        );

        // 기존 엔티티의 공공데이터 필드만 갱신되고 제휴 상태는 유지되는지 확인합니다.
        assertThat(result).isSameAs(existingHospital);
        assertThat(result.getName()).isEqualTo("변경된 병원");
        assertThat(result.getPhone()).isEqualTo("02-2088-0922");
        assertThat(result.getPartnershipStatus())
                .isEqualTo(PartnershipStatus.PARTNER);
        then(hospitalRepository).should(never()).save(any(Hospital.class));
    }

    private AnimalHospitalItem createItem(String name, String telephoneNumber) {
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
                "서울특별시 마포구 성산동",
                "313000001020260004",
                "3130000",
                "000",
                "서울특별시 마포구 모래내로1길 9",
                "03938",
                "",
                "01",
                "영업/정상",
                "",
                "",
                telephoneNumber
        );
    }

    private static AnimalHospitalApiProperties properties() {
        return new AnimalHospitalApiProperties(
                null,
                null,
                100,
                List.of("3130000"),
                Duration.ofSeconds(3),
                Duration.ofSeconds(10),
                "EPSG:5174"
        );
    }
}
