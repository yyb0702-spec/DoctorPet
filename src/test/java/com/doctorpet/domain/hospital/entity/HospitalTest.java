package com.doctorpet.domain.hospital.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class HospitalTest {

    @Test
    void 공공데이터_병원은_비제휴_상태로_생성된다() {
        // OpenAPI에서 선택 정보가 누락된 경우를 반영해 null 값으로 생성합니다.
        Hospital hospital = Hospital.createFromPublicData(
                "313000001020260004",
                "3130000",
                "시그널 동물의료센터",
                null,
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

        // 외부 데이터만으로 제휴 상태가 결정되지 않는지 확인합니다.
        assertThat(hospital.getPartnershipStatus())
                .isEqualTo(PartnershipStatus.NON_PARTNER);
        assertThat(hospital.getPhone()).isNull();
        assertThat(hospital.getCoordX()).isNull();
        assertThat(hospital.getCoordY()).isNull();
    }

    @Test
    void 제휴_데이터와_매칭된_병원은_제휴_상태로_전환된다() {
        Hospital hospital = Hospital.createFromPublicData(
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

        // 제휴 데이터가 확인된 이후에만 명시적으로 제휴 상태를 변경합니다.
        hospital.markAsPartner();

        assertThat(hospital.getPartnershipStatus())
                .isEqualTo(PartnershipStatus.PARTNER);
    }

    @Test
    void 공공데이터를_갱신해도_식별키와_제휴상태는_유지된다() {
        Hospital hospital = Hospital.createFromPublicData(
                "313000001020260004",
                "3130000",
                "기존 병원명",
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
        hospital.markAsPartner();

        // 공공데이터에서 변경된 기본정보만 기존 병원에 반영합니다.
        hospital.updateFromPublicData(
                "변경된 병원명",
                "02-2088-0922",
                "서울특별시 마포구 성산동",
                "서울특별시 마포구 모래내로1길 9",
                "03938",
                new BigDecimal("191409.240701388"),
                new BigDecimal("451377.18304308"),
                LocalDate.of(2026, 7, 24),
                BusinessStatus.CLOSED_TEMP,
                null,
                new BigDecimal("144.6"),
                LocalDateTime.of(2026, 7, 25, 22, 5, 5)
        );

        // 공공데이터 필드는 갱신되고 서버 소유 값은 보존되는지 확인합니다.
        assertThat(hospital.getName()).isEqualTo("변경된 병원명");
        assertThat(hospital.getPhone()).isEqualTo("02-2088-0922");
        assertThat(hospital.getBusinessStatus())
                .isEqualTo(BusinessStatus.CLOSED_TEMP);
        assertThat(hospital.getMgmtNo()).isEqualTo("313000001020260004");
        assertThat(hospital.getLocalGovCode()).isEqualTo("3130000");
        assertThat(hospital.getPartnershipStatus())
                .isEqualTo(PartnershipStatus.PARTNER);
    }
}
