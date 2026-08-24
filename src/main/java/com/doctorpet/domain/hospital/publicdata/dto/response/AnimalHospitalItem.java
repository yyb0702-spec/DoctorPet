package com.doctorpet.domain.hospital.publicdata.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OpenAPI에서 조회한 동물병원 한 건의 원본 필드를 표현합니다.
 * 빈 문자열과 날짜·숫자 변환은 이후 정규화 단계에서 처리합니다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnimalHospitalItem(
        @JsonProperty("BPLC_NM") String businessPlaceName,
        @JsonProperty("CLSBIZ_YMD") String closureDate,
        @JsonProperty("CRD_INFO_X") String coordinateX,
        @JsonProperty("CRD_INFO_Y") String coordinateY,
        @JsonProperty("DAT_UPDT_PNT") String dataUpdatedAt,
        @JsonProperty("DAT_UPDT_SE") String dataUpdateType,
        @JsonProperty("DTL_SALS_STTS_CD") String detailBusinessStatusCode,
        @JsonProperty("DTL_SALS_STTS_NM") String detailBusinessStatusName,
        @JsonProperty("LAST_MDFCN_PNT") String lastModifiedAt,
        @JsonProperty("LCPMT_RTRCN_YMD") String licenseCancellationDate,
        @JsonProperty("LCPMT_YMD") String licenseDate,
        @JsonProperty("LCTN_AREA") String locationArea,
        @JsonProperty("LCTN_ZIP") String locationZipcode,
        @JsonProperty("LOTNO_ADDR") String lotNumberAddress,
        @JsonProperty("MNG_NO") String managementNumber,
        @JsonProperty("OPN_ATMY_GRP_CD") String localGovernmentCode,
        @JsonProperty("RGHT_MNBD_SN") String rightsHolderTypeCode,
        @JsonProperty("ROAD_NM_ADDR") String roadNameAddress,
        @JsonProperty("ROAD_NM_ZIP") String roadNameZipcode,
        @JsonProperty("ROBIZ_YMD") String reopeningDate,
        @JsonProperty("SALS_STTS_CD") String businessStatusCode,
        @JsonProperty("SALS_STTS_NM") String businessStatusName,
        @JsonProperty("TCBIZ_BGNG_YMD") String temporaryClosureStartDate,
        @JsonProperty("TCBIZ_END_YMD") String temporaryClosureEndDate,
        @JsonProperty("TELNO") String telephoneNumber
) {
}
