package com.doctorpet.domain.payment.audit;

/*
  청구 감사 기록의 행위 채널(고도화 3.3·3.5-a). 청구는 세 경로로 접수되며 행위자 역할이 다르다 — 감사가
  스태프 청구와 보호자 셀프 복구를 구분하지 못하면 과다청구·탈취 추적이 흐려진다. 그래서 감사 기록에 채널을
  함께 남겨 "누가(스태프/보호자)·어떤 절차로 청구했는지"를 사후에 구분할 수 있게 한다.
 */
public enum PaymentChargeChannel {
    // 병원 스태프의 정상 청구(진료 완료 후 최초 청구).
    STAFF_CHARGE,
    // 병원 스태프의 정정 재청구(전액 환불 뒤 금액 정정, 3.5-a).
    STAFF_CORRECTION,
    // 보호자의 결제 실패 셀프 복구(OFFLINE_REQUIRED 재청구, 3.3). 행위자는 스태프가 아니라 보호자다.
    GUARDIAN_RECOVERY
}
