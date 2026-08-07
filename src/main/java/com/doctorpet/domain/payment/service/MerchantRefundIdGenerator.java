package com.doctorpet.domain.payment.service;

import java.util.UUID;
import org.springframework.stereotype.Component;

/*
  환불 멱등키(merchant_refund_id) 생성기(#37). 환불 선점 시 한 번 생성해 선기록하고, PG 취소·재시도에 동일하게
  사용한다 — 재시도가 새 키를 만들면 PG가 이중 취소를 막을 수 없다. 결제 멱등키(pay_)와 접두어를 달리해
  로그·PG 콘솔에서 승인과 취소를 구분할 수 있게 한다. 컴포넌트로 분리해 테스트에서 고정값 주입이 가능하게 한다.
 */
@Component
public class MerchantRefundIdGenerator {

    public String generate() {
        return "rfd_" + UUID.randomUUID().toString().replace("-", "");
    }
}
