package com.doctorpet.domain.payment.service;

import com.doctorpet.domain.payment.entity.PaymentItem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/*
  청구 항목 초안의 낙관적 검증 토큰(SA §9-4 "초안 교체 경합").

  초안 저장(PUT)과 청구(POST)는 별도 요청이라 그 사이에 예약 행 잠금이 유지되지 않는다. 두 요청 사이에
  다른 스태프가 같은 예약의 초안을 통째로 바꾸면, 먼저 저장한 스태프의 청구가 **화면에서 확인한 금액이
  아니라 남의 초안 합계**로 성립한다. 그래서 조회·저장 응답이 이 토큰을 함께 내려주고, 청구 요청이 그
  토큰을 되보내 잠금 아래에서 다시 계산한 값과 대조한다 — 다르면 청구하지 않고 409로 거부한다.

  토큰은 서버가 만드는 값이며 클라이언트가 금액을 결정하지 못한다(총액은 여전히 서버가 항목 합계로 산출).
  초안 전체 교체는 삭제 후 재삽입이라 id가 새로 발급되므로, id를 포함한 이 값은 내용이 같아도 교체를
  감지한다 — "누가 언제 바꿨는가"가 아니라 "내가 본 그 초안인가"를 보는 것이 목적이다.
 */
public final class PaymentItemDraftToken {

    private PaymentItemDraftToken() {
    }

    /** 초안 목록의 정규화 문자열을 SHA-256으로 요약한다. 조회 순서(id 오름차순)를 그대로 쓴다. */
    public static String of(List<PaymentItem> drafts) {
        String canonical = drafts.stream()
                .map(item -> item.getId()
                        + ":" + item.getName()
                        + ":" + item.getQuantity()
                        + ":" + item.getUnitPrice())
                .collect(Collectors.joining("|"));
        return sha256Hex(canonical);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 JDK가 제공하므로 도달하지 않는다. 도달하면 설정 오류이므로 감추지 않는다.
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }
}
