package com.doctorpet.domain.hospital.partnership.mapper;

import com.doctorpet.domain.hospital.model.DailyOperatingHours;
import com.doctorpet.domain.hospital.partnership.dto.PartnerHospitalDetailSeedData;
import com.doctorpet.domain.hospital.partnership.dto.PartnerHospitalOperatingHoursSeedData;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PartnerHospitalSeedMapperTest {

    @Test
    void 운영시간_문자열을_도메인_운영시간으로_변환한다() {
        PartnerHospitalDetailSeedData detail =
                new PartnerHospitalDetailSeedData(
                        Map.of(
                                DayOfWeek.MONDAY,
                                new PartnerHospitalOperatingHoursSeedData(
                                        "09:00",
                                        "20:00"
                                )
                        ),
                        true,
                        true,
                        false,
                        false
                );

        Map<DayOfWeek, DailyOperatingHours> result =
                new PartnerHospitalSeedMapper().toOpenHours(detail);

        // JSON 문자열 시간이 LocalTime을 사용하는 도메인 값으로 변환되는지 확인합니다.
        assertThat(result.get(DayOfWeek.MONDAY))
                .isEqualTo(new DailyOperatingHours(
                        LocalTime.of(9, 0),
                        LocalTime.of(20, 0)
                ));
    }
}
