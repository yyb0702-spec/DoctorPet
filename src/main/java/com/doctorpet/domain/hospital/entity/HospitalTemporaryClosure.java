package com.doctorpet.domain.hospital.entity;

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
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "hospital_temporary_closures",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_hospital_temporary_closures_hospital_business_date",
                columnNames = {"hospital_id", "business_date"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HospitalTemporaryClosure extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false)
    private Hospital hospital;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    private HospitalTemporaryClosure(Hospital hospital, LocalDate businessDate) {
        if (hospital == null) {
            throw new IllegalArgumentException("병원이 필요합니다.");
        }
        if (businessDate == null) {
            throw new IllegalArgumentException("임시 휴무 영업일이 필요합니다.");
        }
        this.hospital = hospital;
        this.businessDate = businessDate;
    }

    public static HospitalTemporaryClosure create(
            Hospital hospital,
            LocalDate businessDate
    ) {
        return new HospitalTemporaryClosure(hospital, businessDate);
    }
}
