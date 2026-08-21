package com.doctorpet.domain.hospital.dto.request;

/**
 * 시간표 저장 요청의 의도다. CREATE는 같은 발효일이 이미 있으면 덮어쓰지 않고 충돌로 끝내며,
 * UPDATE는 목록에서 선택한 시간표의 식별자와 마지막 수정 시각을 함께 검증한다.
 */
public enum OperatingHoursSaveMode {
    CREATE,
    UPDATE
}
