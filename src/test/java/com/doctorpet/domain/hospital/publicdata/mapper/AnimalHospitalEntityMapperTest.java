package com.doctorpet.domain.hospital.publicdata.mapper;

import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.entity.PartnershipStatus;
import com.doctorpet.domain.hospital.publicdata.model.NormalizedAnimalHospitalData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnimalHospitalEntityMapperTest {

    private final AnimalHospitalEntityMapper mapper =
            new AnimalHospitalEntityMapper();

    @Test
    void 정규화된_데이터를_비제휴_병원으로_변환한다() {
        NormalizedAnimalHospitalData data = createData(
                "313000001020260004",
                "3130000",
                "시그널 동물의료센터",
                BusinessStatus.OPEN
        );

        Hospital hospital = mapper.toEntity(data);

        // 정규화된 값과 공공데이터 병원의 기본 비제휴 상태를 확인합니다.
        assertThat(hospital.getMgmtNo()).isEqualTo("313000001020260004");
        assertThat(hospital.getLocalGovCode()).isEqualTo("3130000");
        assertThat(hospital.getName()).isEqualTo("시그널 동물의료센터");
        assertThat(hospital.getCoordX()).isEqualByComparingTo("191409.240701388");
        assertThat(hospital.getBusinessStatus()).isEqualTo(BusinessStatus.OPEN);
        assertThat(hospital.getPartnershipStatus())
                .isEqualTo(PartnershipStatus.NON_PARTNER);
    }

    @Test
    void 선택값이_null이어도_병원으로_변환한다() {
        NormalizedAnimalHospitalData data =
                new NormalizedAnimalHospitalData(
                        "538000001020260002",
                        "5380000",
                        "진동물병원",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        LocalDate.of(2026, 7, 22),
                        BusinessStatus.OPEN,
                        null,
                        null,
                        null
                );

        Hospital hospital = mapper.toEntity(data);

        // OpenAPI에서 누락될 수 있는 선택값 때문에 엔티티 생성이 실패하지 않는지 확인합니다.
        assertThat(hospital.getPhone()).isNull();
        assertThat(hospital.getAddressRoad()).isNull();
        assertThat(hospital.getCoordX()).isNull();
        assertThat(hospital.getCoordY()).isNull();
    }

    @Test
    void 관리번호가_없으면_병원으로_변환하지_않는다() {
        NormalizedAnimalHospitalData data = createData(
                null,
                "3130000",
                "시그널 동물의료센터",
                BusinessStatus.OPEN
        );

        // 복합 식별 키가 없는 공공데이터 행은 DB 저장 대상으로 만들지 않습니다.
        assertThatThrownBy(() -> mapper.toEntity(data))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("병원 관리번호가 필요합니다.");
    }

    private NormalizedAnimalHospitalData createData(
            String managementNumber,
            String localGovernmentCode,
            String name,
            BusinessStatus businessStatus
    ) {
        return new NormalizedAnimalHospitalData(
                managementNumber,
                localGovernmentCode,
                name,
                "02-2088-0922",
                "서울특별시 마포구 성산동",
                "서울특별시 마포구 모래내로1길 9",
                "03938",
                new BigDecimal("191409.240701388"),
                new BigDecimal("451377.18304308"),
                LocalDate.of(2026, 7, 24),
                businessStatus,
                null,
                new BigDecimal("144.6"),
                LocalDateTime.of(2026, 7, 25, 22, 5, 5)
        );
    }
}
