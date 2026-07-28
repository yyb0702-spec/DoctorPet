package com.doctorpet.domain.hospital.repository;

import com.doctorpet.domain.hospital.dto.query.HospitalSearchCandidate;
import com.doctorpet.domain.hospital.dto.query.HospitalSearchCondition;
import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.PartnershipStatus;
import com.doctorpet.domain.hospital.entity.QHospitalCapability;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static com.doctorpet.domain.hospital.entity.QHospital.hospital;
import static com.doctorpet.domain.hospital.entity.QHospitalDetail.hospitalDetail;

/**
 * 병원명·주소, 제휴 여부, 진료역량, 거리 조건으로 병원을 검색합니다.
 */
@Repository
@RequiredArgsConstructor
public class HospitalRepositoryCustomImpl
        implements HospitalRepositoryCustom {

    private static final BigDecimal KILOMETERS_PER_LATITUDE_DEGREE =
            BigDecimal.valueOf(111);

    private final JPAQueryFactory queryFactory;

    @Override
    public List<HospitalSearchCandidate> search(
            HospitalSearchCondition condition
    ) {
        return queryFactory
                // Hospital과 HospitalDetail을 HospitalSearchCandidate 생성자에 바로 넣습니다.
                .select(Projections.constructor(
                        HospitalSearchCandidate.class,
                        hospital,
                        hospitalDetail
                ))
                .from(hospital)
                // 상세정보가 없는 비제휴 병원도 조회하기 위해 LEFT JOIN을 사용합니다.
                .leftJoin(hospitalDetail)
                .on(hospitalDetail.hospital.eq(hospital))
                .where(
                        // 폐업 병원은 제외하고 휴업 병원은 검색 결과에 포함합니다.
                        hospital.businessStatus.ne(BusinessStatus.CLOSED),
                        keywordContains(condition.keyword()),
                        partnerOnly(condition.partnerOnly()),
                        capabilityMatches(condition),
                        withinBoundingBox(condition)
                )
                .fetch();
    }

    /**
     * 병원명·도로명주소·지번주소 중 하나에 검색어가 포함되는 조건을 만듭니다.
     */
    private BooleanExpression keywordContains(
            String keyword
    ) {
        // 검색어가 없으면 이 조건을 적용하지 않습니다.
        if (keyword == null || keyword.isBlank()) {
            return null;
        }

        String normalizedKeyword = keyword.trim();
        // 대소문자를 구분하지 않고 검색어가 문자열 일부에 포함되는지 확인합니다.
        return hospital.name.containsIgnoreCase(normalizedKeyword)
                .or(hospital.addressRoad.containsIgnoreCase(
                        normalizedKeyword
                ))
                .or(hospital.addressJibun.containsIgnoreCase(
                        normalizedKeyword
                ));
    }

    /**
     * partnerOnly가 true일 때 제휴 병원만 조회하는 조건을 만듭니다.
     */
    private BooleanExpression partnerOnly(
            boolean partnerOnly
    ) {
        if (!partnerOnly) {
            return null;
        }

        return hospital.partnershipStatus.eq(
                PartnershipStatus.PARTNER
        );
    }

    /**
     * 요청한 진료역량을 모두 가진 병원만 조회하는 조건을 만듭니다.
     */
    private BooleanExpression capabilityMatches(
            HospitalSearchCondition condition
    ) {
        if (condition.capabilities() == null
                || condition.capabilities().isEmpty()) {
            return null;
        }

        // 서브쿼리에서 hospital_capabilities 테이블을 searchCapability이라는 이름으로 사용합니다.
        QHospitalCapability capability =
                new QHospitalCapability("searchCapability");

        // 같은 역량이 중복 요청되어도 한 종류로 계산합니다.
        long requiredCount = condition.capabilities().stream()
                .distinct()
                .count();

        // 서브쿼리가 반환한 병원 ID 목록에 현재 병원 ID가 포함되는지 검사합니다.
        return hospital.id.in(
                JPAExpressions
                        // 요청 역량 중 하나 이상을 가진 병원의 ID를 조회합니다.
                        .select(capability.hospital.id)
                        .from(capability)
                        .where(capability.capabilityValue.in(
                                condition.capabilities()
                        ))
                        // 병원마다 요청 역량과 일치한 개수를 계산하기 위해 병원 ID로 묶습니다.
                        .groupBy(capability.hospital.id)
                        .having(
                                // 일치한 역량 수가 요청 수와 같으면 요청 역량을 모두 가진 병원입니다.
                                capability.capabilityValue.countDistinct()
                                        .eq(requiredCount)
                        )
        );
    }

    /**
     * 사용자 위치와 검색 반경을 감싸는 위도·경도 사각형 조건을 만듭니다.
     */
    private BooleanExpression withinBoundingBox(
            HospitalSearchCondition condition
    ) {
        if (condition.latitude() == null
                || condition.longitude() == null
                || condition.radiusKm() == null) {
            return null;
        }

        // 검색 반경(km)을 위도 차이로 변환합니다. 위도 1도는 약 111km입니다.
        BigDecimal latitudeDelta = condition.radiusKm()
                .divide(
                        KILOMETERS_PER_LATITUDE_DEGREE,
                        8,
                        RoundingMode.HALF_UP
                );

        // 경도 1도의 거리는 위도에 따라 달라지므로 현재 위도의 cos 값으로 보정합니다.
        double latitudeRadians = Math.toRadians(
                condition.latitude().doubleValue()
        );
        double longitudeDegreeKm =
                111 * Math.cos(latitudeRadians);

        // 극지방에서는 경도 1도의 거리가 0에 가까워 나눗셈을 할 수 없습니다.
        if (Math.abs(longitudeDegreeKm) < 0.000001) {
            return null;
        }

        // 검색 반경(km)을 현재 위도에서의 경도 차이로 변환합니다.
        BigDecimal longitudeDelta = condition.radiusKm()
                .divide(
                        BigDecimal.valueOf(longitudeDegreeKm),
                        8,
                        RoundingMode.HALF_UP
                )
                .abs();

        // 위도와 경도를 각각 중심 좌표±델타 범위로 제한합니다.
        return hospital.coordY.between(
                        condition.latitude().subtract(latitudeDelta),
                        condition.latitude().add(latitudeDelta)
                )
                .and(hospital.coordX.between(
                        condition.longitude().subtract(longitudeDelta),
                        condition.longitude().add(longitudeDelta)
                ));
    }
}
