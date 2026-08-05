package com.doctorpet.global.gateway.publicdata;

import com.doctorpet.domain.hospital.publicdata.dto.response.AnimalHospitalApiResponse;

/**
 * 동물병원 공공데이터 외부 연동 추상화입니다.
 *
 * <p>상위 수집 서비스는 제공자별 HTTP 호출 방식에 의존하지 않고 페이지 단위 조회 계약만 사용합니다.
 */
public interface PublicDataGateway {

    /** 전국 동물병원 데이터를 지역 조건 없이 페이지 단위로 조회합니다. */
    AnimalHospitalApiResponse fetch(int pageNo, int numOfRows);
}
