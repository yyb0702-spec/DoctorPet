package com.doctorpet.domain.hospital.entity;

import com.doctorpet.domain.hospital.model.DailyOperatingHours;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HospitalDetailTest {

    @Test
    void 제휴_병원의_운영시간과_시설정보를_생성한다() {
        Map<DayOfWeek, DailyOperatingHours> openHours =
                createOpenHours();
        Hospital hospital = createHospital();

        HospitalDetail detail = HospitalDetail.create(
                hospital,
                openHours,
                true,
                true,
                false,
                false
        );

        // 운영시간과 시설 가능 여부가 전달한 값으로 생성되는지 확인합니다.
        assertThat(detail.getHospital()).isSameAs(hospital);
        assertThat(detail.getOpenHours().get(DayOfWeek.MONDAY))
                .isEqualTo(new DailyOperatingHours(
                        LocalTime.of(9, 0),
                        LocalTime.of(18, 0)
                ));
        assertThat(detail.isSurgeryAvailable()).isTrue();
        assertThat(detail.isHospitalizationAvailable()).isTrue();
        assertThat(detail.isNightCare()).isFalse();
        assertThat(detail.isEmergency()).isFalse();
    }

    @Test
    void 병원이_없으면_상세정보를_생성하지_않는다() {
        assertThatThrownBy(() -> HospitalDetail.create(
                null,
                createOpenHours(),
                false,
                false,
                false,
                false
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("병원이 필요합니다.");
    }

    private Map<DayOfWeek, DailyOperatingHours> createOpenHours() {
        EnumMap<DayOfWeek, DailyOperatingHours> openHours =
                new EnumMap<>(DayOfWeek.class);
        openHours.put(
                DayOfWeek.MONDAY,
                new DailyOperatingHours(
                        LocalTime.of(9, 0),
                        LocalTime.of(18, 0)
                )
        );
        openHours.put(DayOfWeek.SUNDAY, null);
        return openHours;
    }

    private Hospital createHospital() {
        return Hospital.createFromPublicData(
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
    }
}
