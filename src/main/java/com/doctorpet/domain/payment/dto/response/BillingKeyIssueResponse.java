package com.doctorpet.domain.payment.dto.response;

// 인증된 보호자에게만 돌려주는 SDK 빌링키 발급 시도 정보. issueId는 콜백 원문을 서버에서 귀속할
// 때 쓰는 단기 난수이며 memberId·빌링키를 담지 않는다.
public record BillingKeyIssueResponse(
        String issueId,
        String redirectUrl
) {
}
