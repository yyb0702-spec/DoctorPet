package com.doctorpet.domain.hospital.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 병원이 보유한 진료 대상·검사·전문진료·장비 역량을 저장합니다.
 */
@Getter
@Entity
@Table(
        name = "hospital_capabilities",
        indexes = @Index(
                name = "idx_hospital_capabilities_type_value_hospital",
                columnList = "capability_type, capability_value, hospital_id"
        ),
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_hospital_capabilities_hospital_type_value",
                        columnNames = {
                                "hospital_id",
                                "capability_type",
                                "capability_value"
                        }
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HospitalCapability {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 병원 하나가 여러 역량을 가질 수 있으므로 역량에서 병원으로 N:1 매핑합니다.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "hospital_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_hospital_capabilities_hospital"
            )
    )
    private Hospital hospital;

    @Enumerated(EnumType.STRING)
    @Column(name = "capability_type", nullable = false)
    private CapabilityType capabilityType;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "capability_value",
            nullable = false,
            columnDefinition = "varchar(32)"
    )
    private CapabilityValue capabilityValue;

    private HospitalCapability(
            Hospital hospital,
            CapabilityValue capabilityValue
    ) {
        this.hospital = requireHospital(hospital);
        this.capabilityValue = requireCapabilityValue(capabilityValue);
        // 값에 지정된 분류를 사용해 서로 맞지 않는 타입과 값의 조합을 방지합니다.
        this.capabilityType = capabilityValue.getType();
    }

    /**
     * 병원이 보유한 화이트리스트 역량을 생성합니다.
     */
    public static HospitalCapability create(
            Hospital hospital,
            CapabilityValue capabilityValue
    ) {
        return new HospitalCapability(hospital, capabilityValue);
    }

    private Hospital requireHospital(Hospital hospital) {
        if (hospital == null) {
            throw new IllegalArgumentException("병원이 필요합니다.");
        }
        return hospital;
    }

    private CapabilityValue requireCapabilityValue(
            CapabilityValue capabilityValue
    ) {
        if (capabilityValue == null) {
            throw new IllegalArgumentException("진료 역량이 필요합니다.");
        }
        return capabilityValue;
    }
}
