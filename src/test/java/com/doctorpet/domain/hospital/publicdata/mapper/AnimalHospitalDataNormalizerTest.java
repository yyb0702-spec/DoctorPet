package com.doctorpet.domain.hospital.publicdata.mapper;

import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.publicdata.dto.response.AnimalHospitalItem;
import com.doctorpet.domain.hospital.publicdata.model.NormalizedAnimalHospitalData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AnimalHospitalDataNormalizerTest {

    private final AnimalHospitalDataNormalizer normalizer =
            new AnimalHospitalDataNormalizer();

    @Test
    void 원본_문자열을_애플리케이션_타입으로_변환한다() {
        // 실제 API 응답 형식과 동일한 날짜·좌표·면적 문자열을 준비합니다.
        AnimalHospitalItem item = createItem(
                "시그널 동물의료센터",
                "191409.240701388",
                "451377.18304308",
                "2026-07-25 22:05:05",
                "2026-07-24",
                "144.6",
                "01",
                "02-2088-0922"
        );

        NormalizedAnimalHospitalData result = normalizer.normalize(item);

        // 문자열이 목적에 맞는 Java 타입과 영업상태로 변환됐는지 확인합니다.
        assertThat(result.coordinateX()).isEqualByComparingTo("191409.240701388");
        assertThat(result.coordinateY()).isEqualByComparingTo("451377.18304308");
        assertThat(result.area()).isEqualByComparingTo("144.6");
        assertThat(result.licenseDate()).isEqualTo(LocalDate.of(2026, 7, 24));
        assertThat(result.sourceModifiedAt())
                .isEqualTo(LocalDateTime.of(2026, 7, 25, 22, 5, 5));
        assertThat(result.businessStatus()).isEqualTo(BusinessStatus.OPEN);
    }

    @Test
    void 빈_문자열은_null로_변환한다() {
        AnimalHospitalItem item = createItem(
                "진동물병원",
                "",
                " ",
                "",
                "2026-07-22",
                "",
                "02",
                ""
        );

        NormalizedAnimalHospitalData result = normalizer.normalize(item);

        // 선택 정보가 없어도 변환이 실패하지 않고 null로 정규화되는지 확인합니다.
        assertThat(result.phone()).isNull();
        assertThat(result.coordinateX()).isNull();
        assertThat(result.coordinateY()).isNull();
        assertThat(result.area()).isNull();
        assertThat(result.sourceModifiedAt()).isNull();
        assertThat(result.businessStatus()).isEqualTo(BusinessStatus.CLOSED_TEMP);
    }

    private AnimalHospitalItem createItem(
            String name,
            String coordinateX,
            String coordinateY,
            String dataUpdatedAt,
            String licenseDate,
            String area,
            String businessStatusCode,
            String telephoneNumber
    ) {
        return new AnimalHospitalItem(
                name,
                "",
                coordinateX,
                coordinateY,
                dataUpdatedAt,
                "I",
                "0000",
                "정상",
                "2026-07-24 15:52:02",
                "",
                licenseDate,
                area,
                "",
                "서울특별시 마포구 성산동",
                "313000001020260004",
                "3130000",
                "000",
                "서울특별시 마포구 모래내로1길 9",
                "03938",
                "",
                businessStatusCode,
                "영업/정상",
                "",
                "",
                telephoneNumber
        );
    }
}
