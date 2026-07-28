package com.doctorpet.global.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Level 1 — 빌링키 암복호화(AES-256-GCM) 왕복·보안 속성 검증. Spring 컨텍스트·DB 불필요.
 */
class BillingKeyCryptorTest {

    // 테스트 전용 256-bit 키(32-byte). 실제 키는 env로 주입하며 커밋하지 않는다.
    private static final String TEST_KEY_BASE64 = Base64.getEncoder().encodeToString(new byte[32]);

    private BillingKeyCryptor cryptor;

    @BeforeEach
    void setUp() {
        cryptor = new BillingKeyCryptor(TEST_KEY_BASE64);
    }

    @Test
    @DisplayName("암호화 후 복호화하면 원문 빌링키가 그대로 복원된다")
    void encryptThenDecrypt_roundTrips() {
        String billingKey = "billing_key_1234567890abcdef";

        String encrypted = cryptor.encrypt(billingKey);

        assertThat(cryptor.decrypt(encrypted)).isEqualTo(billingKey);
    }

    @Test
    @DisplayName("암호문에는 원문이 노출되지 않고 버전 접두(v1:)가 붙는다")
    void encrypt_doesNotExposePlaintext() {
        String billingKey = "billing_key_secret_value";

        String encrypted = cryptor.encrypt(billingKey);

        assertThat(encrypted).startsWith("v1:");
        assertThat(encrypted).doesNotContain(billingKey);
    }

    @Test
    @DisplayName("같은 원문을 두 번 암호화하면 랜덤 IV로 인해 서로 다른 암호문이 나온다")
    void encrypt_usesRandomIv() {
        String billingKey = "same_billing_key";

        String first = cryptor.encrypt(billingKey);
        String second = cryptor.encrypt(billingKey);

        assertThat(first).isNotEqualTo(second);
        assertThat(cryptor.decrypt(first)).isEqualTo(billingKey);
        assertThat(cryptor.decrypt(second)).isEqualTo(billingKey);
    }

    @Test
    @DisplayName("키 길이가 256-bit(32-byte)가 아니면 생성 시점에 실패한다")
    void constructor_rejectsWrongKeyLength() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new BillingKeyCryptor(shortKey))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("변조된 암호문은 복호화 시 실패한다(GCM 인증 태그 검증)")
    void decrypt_rejectsTamperedCipherText() {
        String encrypted = cryptor.encrypt("billing_key_to_tamper");
        // 마지막 문자를 바꿔 태그를 훼손한다.
        String tampered = encrypted.substring(0, encrypted.length() - 1)
                + (encrypted.endsWith("A") ? "B" : "A");

        assertThatThrownBy(() -> cryptor.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }
}
