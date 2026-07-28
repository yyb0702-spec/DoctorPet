package com.doctorpet.global.crypto;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/*
  빌링키 암복호화 전용 컴포넌트(AES-256-GCM). 서비스에 암복호화를 인라인하지 않고 분리해
  #34 청구 시점에도 원문 빌링키를 안전하게 복호화(로그·저장 금지)해 재사용할 수 있게 계약을 연다.
  키는 env(payment.billing-key.enc-key, Base64 256-bit)로 주입하며 코드·로그·커밋에 남기지 않는다(SA §9-4, AGENTS 보안).
 */
@Component
public class BillingKeyCryptor {

    private static final String KEY_ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int AES_256_KEY_LENGTH_BYTES = 32;
    // 저장값 앞에 키 버전을 붙여 향후 키 회전 시 구·신 키를 구분할 수 있게 한다(현재 v1만 존재).
    private static final String VERSION_PREFIX = "v1:";

    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public BillingKeyCryptor(@Value("${payment.billing-key.enc-key}") String base64Key) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        if (keyBytes.length != AES_256_KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "payment.billing-key.enc-key는 Base64로 인코딩된 256-bit(32-byte) 키여야 합니다.");
        }
        this.secretKey = new SecretKeySpec(keyBytes, KEY_ALGORITHM);
    }

    /** 원문 빌링키를 암호화해 저장용 문자열(버전 접두 + Base64(IV‖ciphertext‖tag))로 반환한다. */
    public String encrypt(String plainBillingKey) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] cipherText = cipher.doFinal(plainBillingKey.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            return VERSION_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            // 예외 메시지·로그에 원문 빌링키를 절대 포함하지 않는다.
            throw new IllegalStateException("빌링키 암호화에 실패했습니다.", e);
        }
    }

    /** 저장된 암호문을 복호화해 원문 빌링키를 반환한다. 결과는 로그·저장하지 않는다(#34 청구 시점 사용). */
    public String decrypt(String storedValue) {
        try {
            String base64 = stripVersionPrefix(storedValue);
            byte[] combined = Base64.getDecoder().decode(base64);

            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH_BYTES);
            byte[] cipherText = new byte[combined.length - GCM_IV_LENGTH_BYTES];
            System.arraycopy(combined, GCM_IV_LENGTH_BYTES, cipherText, 0, cipherText.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("빌링키 복호화에 실패했습니다.", e);
        }
    }

    private String stripVersionPrefix(String storedValue) {
        if (storedValue == null || !storedValue.startsWith(VERSION_PREFIX)) {
            throw new IllegalStateException("알 수 없는 빌링키 암호문 형식입니다.");
        }
        return storedValue.substring(VERSION_PREFIX.length());
    }
}
