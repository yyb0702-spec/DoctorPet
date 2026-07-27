package com.doctorpet.domain.hospital.entity;

import com.doctorpet.domain.hospital.model.DailyOperatingHours;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.DayOfWeek;
import java.util.EnumMap;
import java.util.Map;

/**
 * 공공데이터에 없는 제휴 병원의 운영·시설 정보를 저장합니다.
 */
@Getter
@Entity
@Table(
        name = "hospital_details",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_hospital_details_hospital_id",
                        columnNames = "hospital_id"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HospitalDetail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 병원 하나당 상세정보가 하나만 존재하므로 1:1 관계로 매핑합니다.
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "hospital_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_hospital_details_hospital"
            )
    )
    private Hospital hospital;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "open_hours", nullable = false, columnDefinition = "json")
    private Map<DayOfWeek, DailyOperatingHours> openHours;

    @Column(name = "surgery_available", nullable = false)
    private boolean surgeryAvailable;

    @Column(name = "hospitalization_available", nullable = false)
    private boolean hospitalizationAvailable;

    @Column(name = "night_care", nullable = false)
    private boolean nightCare;

    @Column(nullable = false)
    private boolean emergency;

    private HospitalDetail(
            Hospital hospital,
            Map<DayOfWeek, DailyOperatingHours> openHours,
            boolean surgeryAvailable,
            boolean hospitalizationAvailable,
            boolean nightCare,
            boolean emergency
    ) {
        this.hospital = requireHospital(hospital);
        this.openHours = copyOpenHours(openHours);
        this.surgeryAvailable = surgeryAvailable;
        this.hospitalizationAvailable = hospitalizationAvailable;
        this.nightCare = nightCare;
        this.emergency = emergency;
    }

    /**
     * 제휴 병원의 요일별 운영시간과 시설 정보를 생성합니다.
     */
    public static HospitalDetail create(
            Hospital hospital,
            Map<DayOfWeek, DailyOperatingHours> openHours,
            boolean surgeryAvailable,
            boolean hospitalizationAvailable,
            boolean nightCare,
            boolean emergency
    ) {
        return new HospitalDetail(
                hospital,
                openHours,
                surgeryAvailable,
                hospitalizationAvailable,
                nightCare,
                emergency
        );
    }

    private Hospital requireHospital(Hospital hospital) {
        if (hospital == null) {
            throw new IllegalArgumentException("병원이 필요합니다.");
        }
        return hospital;
    }

    private Map<DayOfWeek, DailyOperatingHours> copyOpenHours(
            Map<DayOfWeek, DailyOperatingHours> openHours
    ) {
        if (openHours == null || openHours.isEmpty()) {
            throw new IllegalArgumentException("요일별 운영시간이 필요합니다.");
        }

        // 외부 Map 변경이 엔티티 상태에 영향을 주지 않도록 새 EnumMap으로 복사합니다.
        EnumMap<DayOfWeek, DailyOperatingHours> copied =
                new EnumMap<>(DayOfWeek.class);
        copied.putAll(openHours);
        return copied;
    }
}
