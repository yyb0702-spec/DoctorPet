package com.doctorpet.global.config;

import com.doctorpet.global.security.JwtAccessDeniedHandler;
import com.doctorpet.global.security.JwtAuthenticationEntryPoint;
import com.doctorpet.global.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final JwtAccessDeniedHandler jwtAccessDeniedHandler;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 배포 헬스체크(CI/CD MVP) - 로드밸런서·배포 스크립트가 인증 없이 호출한다.
                        // management.endpoints.web.exposure.include로 health 외 다른 액추에이터
                        // 엔드포인트는 노출 자체를 막아뒀으니(application.yaml), 여기서 전체
                        // /actuator/**를 열어도 실질적으로 health만 응답한다.
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        // 인증/재발급 - 정책상 비인증 API (정책 결정 사항 §1, API 명세서 §1 참고)
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/signup", "/api/auth/login", "/api/auth/reissue"
                        ).permitAll()
                        // 이메일 인증·비밀번호 재설정(백로그 P2) - 로그인 전(또는 로그인 자체가
                        // 불가능한 상태의) 사용자가 호출해야 하므로 비인증 API다.
                        .requestMatchers(HttpMethod.GET, "/api/auth/verify-email").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/verify-email/resend",
                                "/api/auth/password-reset/request",
                                "/api/auth/password-reset/confirm"
                        ).permitAll()
                        // 병원 검색 - 공개 (API 명세서 §3)
                        .requestMatchers(HttpMethod.GET, "/api/hospitals/**").permitAll()
                        // AI 상담 - 공개, 비로그인 임시 상담 허용 (API 명세서 §4)
                        .requestMatchers(HttpMethod.POST, "/api/ai/consultations").permitAll()
                        // 결제 웹훅 - JWT가 아니라 웹훅 서명으로 검증한다(이슈 #48). 컨트롤러가 서명 실패를 401로 거부한다.
                        .requestMatchers(HttpMethod.POST, "/api/payments/webhook").permitAll()
                        // 보호자 예약 요청·취소 (SA §8-5)
                        .requestMatchers(HttpMethod.POST, "/api/reservations").hasRole("GUARDIAN")
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/reservations",
                                "/api/reservations/*"
                        ).hasRole("GUARDIAN")
                        // 보호자 결제 내역 조회 (SA §8-7, 이슈 #47). 본인 예약 여부는 서비스에서 재검증한다.
                        .requestMatchers(HttpMethod.GET, "/api/reservations/*/payments").hasRole("GUARDIAN")
                        .requestMatchers(
                                HttpMethod.PATCH,
                                "/api/reservations/*/cancel"
                        ).hasRole("GUARDIAN")
                        // 병원 예약 운영 API - 병원 스태프 전용 (SA §8-6)
                        .requestMatchers("/api/hospital/**")
                        .hasRole("HOSPITAL_STAFF")
                        // 결제수단 등록·조회·삭제 - 보호자 전용 (이슈 #33)
                        .requestMatchers("/api/payment-methods/**").hasRole("GUARDIAN")
                        // 반려동물 프로필 등록·조회·수정·삭제 - 보호자 전용 (SA §8-2)
                        .requestMatchers("/api/pets/**").hasRole("GUARDIAN")
                        // 진료비 청구(POST /api/hospital/reservations/*/payments)는 위
                        // /api/hospital/** 규칙이 이미 HOSPITAL_STAFF로 가드한다(이슈 #34·#74).
                        .anyRequest().authenticated()
                )
                .exceptionHandling(handler -> handler
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                        .accessDeniedHandler(jwtAccessDeniedHandler)
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
