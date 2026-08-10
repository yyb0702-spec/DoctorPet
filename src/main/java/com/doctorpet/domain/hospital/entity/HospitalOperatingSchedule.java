package com.doctorpet.domain.hospital.entity;

import com.doctorpet.domain.hospital.model.DailyOperatingHours;
import com.doctorpet.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
        name = "hospital_operating_schedules",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_hospital_operating_schedules_hospital_effective_from",
                columnNames = {"hospital_id", "effective_from"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HospitalOperatingSchedule extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "hospital_id",
            nullable = false
    )
    private Hospital hospital;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "operating_hours", nullable = false, columnDefinition = "json")
    private Map<DayOfWeek, List<DailyOperatingHours>> operatingHours;

    private HospitalOperatingSchedule(
            Hospital hospital,
            LocalDate effectiveFrom,
            Map<DayOfWeek, List<DailyOperatingHours>> operatingHours
    ) {
        this.hospital = requireHospital(hospital);
        this.effectiveFrom = requireEffectiveFrom(effectiveFrom);
        this.operatingHours = copyOperatingHours(operatingHours);
    }

    public static HospitalOperatingSchedule create(
            Hospital hospital,
            LocalDate effectiveFrom,
            Map<DayOfWeek, List<DailyOperatingHours>> operatingHours
    ) {
        return new HospitalOperatingSchedule(hospital, effectiveFrom, operatingHours);
    }

    private Hospital requireHospital(Hospital hospital) {
        if (hospital == null) {
            throw new IllegalArgumentException("병원이 필요합니다.");
        }
        return hospital;
    }

    private LocalDate requireEffectiveFrom(LocalDate effectiveFrom) {
        if (effectiveFrom == null) {
            throw new IllegalArgumentException("적용 시작일이 필요합니다.");
        }
        return effectiveFrom;
    }

    private Map<DayOfWeek, List<DailyOperatingHours>> copyOperatingHours(
            Map<DayOfWeek, List<DailyOperatingHours>> operatingHours
    ) {
        if (operatingHours == null) {
            throw new IllegalArgumentException("요일별 진료시간이 필요합니다.");
        }

        operatingHours.forEach((day, periods) -> {
            if (day == null || periods == null || periods.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("요일과 진료 구간은 null일 수 없습니다.");
            }
        });

        EnumMap<DayOfWeek, List<DailyOperatingHours>> copied = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            copied.put(day, List.copyOf(operatingHours.getOrDefault(day, List.of())));
        }
        return copied;
    }
}
