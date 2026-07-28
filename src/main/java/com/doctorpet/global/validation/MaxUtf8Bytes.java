package com.doctorpet.global.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 문자열의 UTF-8 인코딩 후 바이트 길이가 {@link #value()} 이하인지 검증한다.
 *
 * {@code @Size(max = ...)}는 문자(character) 개수를 세기 때문에, 한글·이모지처럼 문자당
 * 여러 바이트를 쓰는 입력에는 맞지 않는 경우가 있다 — 예를 들어 BCrypt는 72 "바이트" 제한이라,
 * 한글 25자(문자 수로는 72 이하)도 UTF-8로는 75바이트가 되어 제한을 넘을 수 있다.
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = MaxUtf8BytesValidator.class)
public @interface MaxUtf8Bytes {

    int value();

    String message() default "허용된 바이트 길이를 초과했습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
