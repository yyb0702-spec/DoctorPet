package com.doctorpet.domain.payment.service;

import java.util.UUID;
import org.springframework.stereotype.Component;

/*
  멱등키(merchant_payment_id) 생성기(SA §9-4). 청구 시작 시 한 번 생성해 선기록하고, PortOne 승인·재시도·조회에
  동일하게 사용한다. 예약당 결제 1건(reservation_id UNIQUE)이라 전역 유일성만 있으면 되며, 추측·충돌을 막기 위해
  UUID 기반으로 만든다. 컴포넌트로 분리해 테스트에서 고정값 주입이 가능하게 한다.
 */
@Component
public class MerchantPaymentIdGenerator {

    public String generate() {
        return "pay_" + UUID.randomUUID().toString().replace("-", "");
    }
}
