package com.doctorpet.domain.hospital.partnership.infrastructure;

import com.doctorpet.domain.hospital.partnership.dto.PartnerHospitalSeedData;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 클래스패스의 JSON 파일에서 제휴 병원 더미 데이터를 읽습니다.
 */
@Component
public class PartnerHospitalSeedLoader {

    // src/main/resources를 기준으로 제휴 병원 JSON 파일의 위치를 지정합니다.
    private static final String SEED_PATH =
            "seed/partner-hospitals.json";

    // JSON 내용을 PartnerHospitalSeedData 객체로 변환할 때 사용합니다.
    private final ObjectMapper objectMapper;

    public PartnerHospitalSeedLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 제휴 병원 JSON 배열을 Java 레코드 목록으로 변환합니다.
     */
    public List<PartnerHospitalSeedData> load() {
        // 빌드 후에도 읽을 수 있도록 일반 파일 경로 대신 클래스패스 리소스로 접근합니다.
        ClassPathResource resource = new ClassPathResource(SEED_PATH);

        // try-with-resources를 사용해 JSON을 읽은 뒤 입력 스트림을 자동으로 닫습니다.
        try (InputStream inputStream = resource.getInputStream()) {
            // JSON 배열의 내부 타입까지 알려주기 위해 TypeReference를 사용합니다.
            return objectMapper.readValue(
                    inputStream,
                    new TypeReference<>() {
                    }
            );
        } catch (IOException exception) {
            // 파일 누락이나 잘못된 JSON 형식을 애플리케이션 수준 예외로 변환합니다.
            throw new IllegalStateException(
                    "제휴 병원 더미 데이터를 읽지 못했습니다.",
                    exception
            );
        }
    }
}
