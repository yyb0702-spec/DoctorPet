package com.doctorpet.domain.hospital.publicdata.mapper;

import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.publicdata.dto.response.AnimalHospitalItem;
import com.doctorpet.domain.hospital.publicdata.model.NormalizedAnimalHospitalData;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 공공데이터의 빈 문자열과 문자열 값을 애플리케이션 타입으로 변환합니다.
 */
@Component
public class AnimalHospitalDataNormalizer {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 동물병원 원본 응답 한 건을 정규화된 데이터로 변환합니다.
     */
    public NormalizedAnimalHospitalData normalize(AnimalHospitalItem item) {
        return new NormalizedAnimalHospitalData(
                normalizeText(item.managementNumber()),
                normalizeText(item.localGovernmentCode()),
                normalizeText(item.businessPlaceName()),
                normalizeText(item.telephoneNumber()),
                normalizeText(item.lotNumberAddress()),
                normalizeText(item.roadNameAddress()),
                normalizeText(item.roadNameZipcode()),
                parseDecimal(item.coordinateX()),
                parseDecimal(item.coordinateY()),
                parseDate(item.licenseDate()),
                parseBusinessStatus(item.businessStatusCode()),
                parseDate(item.closureDate()),
                parseDecimal(item.locationArea()),
                parseDateTime(item.dataUpdatedAt())
        );
    }

    private String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private BigDecimal parseDecimal(String value) {
        String normalized = normalizeText(value);
        return normalized == null ? null : new BigDecimal(normalized);
    }

    private LocalDate parseDate(String value) {
        String normalized = normalizeText(value);
        return normalized == null ? null : LocalDate.parse(normalized);
    }

    private LocalDateTime parseDateTime(String value) {
        String normalized = normalizeText(value);
        return normalized == null
                ? null
                : LocalDateTime.parse(normalized, DATE_TIME_FORMATTER);
    }

    private BusinessStatus parseBusinessStatus(String code) {
        String normalized = normalizeText(code);
        if (normalized == null) {
            return null;
        }

        return switch (normalized) {
            case "01" -> BusinessStatus.OPEN;
            case "02" -> BusinessStatus.CLOSED_TEMP;
            case "03", "04" -> BusinessStatus.CLOSED;
            default -> throw new IllegalArgumentException(
                    "지원하지 않는 영업상태 코드입니다: " + normalized
            );
        };
    }
}
