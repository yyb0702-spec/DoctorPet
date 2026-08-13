package com.doctorpet.domain.payment.service;

/**
 * 청구 항목 입력값(고도화 결제 3.1). 컨트롤러 DTO(PaymentItemRequest)를 서비스 계층까지 끌고 오지 않기 위한
 * 도메인 커맨드다. 항목 금액은 담지 않는다 — {@code quantity * unitPrice}로 서버가 계산하므로 입력이 아니다.
 *
 * @param name      항목명(청구 시점 스냅샷)
 * @param quantity  수량. 양수만 유효하며 검증은 PaymentChargeService가 수행한다
 * @param unitPrice 단가(원). 할인·조정 항목을 위해 음수를 허용한다
 */
public record PaymentItemCommand(String name, int quantity, int unitPrice) {
}
