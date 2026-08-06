package com.doctorpet.domain.hospital.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "hospitals",
        indexes = {
                @Index(
                        name = "idx_hospitals_name_id_business_status",
                        columnList = "name, id, business_status"
                ),
                @Index(
                        name = "idx_hospitals_coord_x_y",
                        columnList = "coord_x, coord_y"
                )
        },
        uniqueConstraints = {
        @UniqueConstraint(
                name = "uk_hospitals_local_gov_code_mgmt_no",
                columnNames = {"local_gov_code", "mgmt_no"}
        )
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Hospital {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "mgmt_no", nullable = false)
    private String mgmtNo;

    @Column(name = "local_gov_code", nullable = false)
    private String localGovCode;

    @Column(nullable = false)
    private String name;

    private String phone;

    @Column(name = "address_jibun")
    private String addressJibun;

    @Column(name = "address_road")
    private String addressRoad;

    private String zipcode;

    @Column(name = "coord_x")
    private BigDecimal coordX;

    @Column(name = "coord_y")
    private BigDecimal coordY;

    @Column(name = "license_date")
    private LocalDate licenseDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "business_status", nullable = false)
    private BusinessStatus businessStatus;

    @Column(name = "close_date")
    private LocalDate closeDate;

    private BigDecimal area;

    @Column(name = "source_modified_at")
    private LocalDateTime sourceModifiedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "partnership_status", nullable = false)
    private PartnershipStatus partnershipStatus;

    private Hospital(
            String mgmtNo,
            String localGovCode,
            String name,
            String phone,
            String addressJibun,
            String addressRoad,
            String zipcode,
            BigDecimal coordX,
            BigDecimal coordY,
            LocalDate licenseDate,
            BusinessStatus businessStatus,
            LocalDate closeDate,
            BigDecimal area,
            LocalDateTime sourceModifiedAt
    ) {
        this.mgmtNo = mgmtNo;
        this.localGovCode = localGovCode;
        this.name = name;
        this.phone = phone;
        this.addressJibun = addressJibun;
        this.addressRoad = addressRoad;
        this.zipcode = zipcode;
        this.coordX = coordX;
        this.coordY = coordY;
        this.licenseDate = licenseDate;
        this.businessStatus = businessStatus;
        this.closeDate = closeDate;
        this.area = area;
        this.sourceModifiedAt = sourceModifiedAt;
        // 공공데이터에서 처음 생성되는 병원은 제휴되지 않은 상태로 시작합니다.
        this.partnershipStatus = PartnershipStatus.NON_PARTNER;
    }

    /**
     * 정규화된 공공데이터로 비제휴 병원을 생성합니다.
     */
    public static Hospital createFromPublicData(
            String mgmtNo,
            String localGovCode,
            String name,
            String phone,
            String addressJibun,
            String addressRoad,
            String zipcode,
            BigDecimal coordX,
            BigDecimal coordY,
            LocalDate licenseDate,
            BusinessStatus businessStatus,
            LocalDate closeDate,
            BigDecimal area,
            LocalDateTime sourceModifiedAt
    ) {
        return new Hospital(
                mgmtNo,
                localGovCode,
                name,
                phone,
                addressJibun,
                addressRoad,
                zipcode,
                coordX,
                coordY,
                licenseDate,
                businessStatus,
                closeDate,
                area,
                sourceModifiedAt
        );
    }

    /**
     * 제휴 더미 데이터와 복합 키가 일치한 병원을 제휴 상태로 전환합니다.
     */
    public void markAsPartner() {
        this.partnershipStatus = PartnershipStatus.PARTNER;
    }

    /**
     * 기존 병원의 공공데이터 기본정보만 갱신합니다.
     * 병원 식별 키와 서버에서 관리하는 제휴 상태는 변경하지 않습니다.
     */
    public void updateFromPublicData(
            String name,
            String phone,
            String addressJibun,
            String addressRoad,
            String zipcode,
            BigDecimal coordX,
            BigDecimal coordY,
            LocalDate licenseDate,
            BusinessStatus businessStatus,
            LocalDate closeDate,
            BigDecimal area,
            LocalDateTime sourceModifiedAt
    ) {
        this.name = name;
        this.phone = phone;
        this.addressJibun = addressJibun;
        this.addressRoad = addressRoad;
        this.zipcode = zipcode;
        this.coordX = coordX;
        this.coordY = coordY;
        this.licenseDate = licenseDate;
        this.businessStatus = businessStatus;
        this.closeDate = closeDate;
        this.area = area;
        this.sourceModifiedAt = sourceModifiedAt;
    }
}
