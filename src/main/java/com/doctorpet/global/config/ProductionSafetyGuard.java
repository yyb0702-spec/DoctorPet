package com.doctorpet.global.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/*
  prod 프로파일로 부팅하는데 payment.gateway 또는 mail.provider가 fake로 남아있으면 즉시
  부팅을 실패시킨다(리뷰 지적 P1). FakePaymentGateway/FakeEmailGateway는 @ConditionalOnProperty
  만으로 등록되고 @Profile 제한이 없어서, 이 CI/CD 배포 템플릿(.env.example)을 그대로 복사해
  운영에 쓰면 결제가 항상 승인된 것처럼 저장되거나 인증·재설정 링크가 로그에 그대로 남는 사고로
  이어진다. 배포 스크립트가 "떴으니 성공"으로 오판하지 않도록, 이 조합에서는 컨테이너 자체가
  뜨지 못하게 막는다.

  ai.gateway는 의도적으로 검사하지 않는다(리뷰 지적 P3) — FakeAiGateway 외에 실연동 구현체가
  아직 없어서, 이걸 막으면 prod 프로파일 자체가 영원히 부팅할 수 없게 된다. 실연동 AiGateway가
  추가되면 그때 이 검사에 payment.gateway/mail.provider와 같은 방식으로 포함시킬 것.

  image.storage.provider도 같은 이유로 이 검사에 포함한다 — fake로 남아있으면 반려동물 프로필
  이미지 업로드 URL이 실제 S3가 아니라 존재하지 않는 fake 호스트를 가리켜, 업로드 자체가
  조용히 실패한다.
 */
@Component
public class ProductionSafetyGuard {

    private final Environment environment;

    @Value("${payment.gateway:}")
    private String paymentGateway;

    @Value("${mail.provider:}")
    private String mailProvider;

    @Value("${image.storage.provider:}")
    private String imageStorageProvider;

    public ProductionSafetyGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void verify() {
        boolean isProd = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (!isProd) {
            return;
        }

        List<String> violations = new ArrayList<>();
        if ("fake".equalsIgnoreCase(paymentGateway)) {
            violations.add("payment.gateway=fake");
        }
        if ("fake".equalsIgnoreCase(mailProvider)) {
            violations.add("mail.provider=fake");
        }
        if ("fake".equalsIgnoreCase(imageStorageProvider)) {
            violations.add("image.storage.provider=fake");
        }

        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "prod 프로파일에서 다음 설정이 fake로 남아있어 부팅을 중단합니다: " + violations
                            + " — 결제가 항상 승인된 것처럼 저장되거나 인증·재설정 링크가 로그에 노출되거나 "
                            + "이미지 업로드가 조용히 실패할 수 있습니다. "
                            + "payment.gateway=portone, mail.provider=smtp, image.storage.provider=s3로 "
                            + "실제 게이트웨이를 설정하세요. "
                            + "정말 fake로 임시 검증만 하려는 것이라면 prod가 아닌 다른 프로파일을 쓰세요."
            );
        }
    }
}
