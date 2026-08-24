package com.doctorpet.domain.hospital.publicdata.mapper;

import com.doctorpet.domain.hospital.publicdata.config.AnimalHospitalApiProperties;
import com.doctorpet.domain.hospital.publicdata.model.Wgs84Coordinate;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 공공데이터의 투영 좌표를 검색에 사용하는 WGS84 경위도로 변환합니다.
 */
@Component
public class AnimalHospitalCoordinateConverter {

    private static final String TARGET_CRS = "EPSG:4326";
    private static final int COORDINATE_SCALE = 7;

    private final CoordinateTransform coordinateTransform;

    public AnimalHospitalCoordinateConverter(
            AnimalHospitalApiProperties properties
    ) {
        CRSFactory crsFactory = new CRSFactory();
        CoordinateReferenceSystem sourceCrs =
                crsFactory.createFromName(properties.sourceCrs());
        CoordinateReferenceSystem targetCrs =
                crsFactory.createFromName(TARGET_CRS);

        this.coordinateTransform = new CoordinateTransformFactory()
                .createTransform(sourceCrs, targetCrs);
    }

    /**
     * 원본 X/Y가 모두 있을 때만 변환하며, X는 경도이고 Y는 위도입니다.
     */
    public Wgs84Coordinate convert(
            BigDecimal sourceX,
            BigDecimal sourceY
    ) {
        if (sourceX == null || sourceY == null) {
            return new Wgs84Coordinate(null, null);
        }

        ProjCoordinate source = new ProjCoordinate(
                sourceX.doubleValue(),
                sourceY.doubleValue()
        );
        ProjCoordinate target = new ProjCoordinate();
        coordinateTransform.transform(source, target);

        return new Wgs84Coordinate(
                round(target.x),
                round(target.y)
        );
    }

    private BigDecimal round(double value) {
        return BigDecimal.valueOf(value)
                .setScale(COORDINATE_SCALE, RoundingMode.HALF_UP);
    }
}
