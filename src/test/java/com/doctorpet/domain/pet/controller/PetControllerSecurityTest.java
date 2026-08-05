package com.doctorpet.domain.pet.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doctorpet.domain.pet.dto.request.PetCreateRequest;
import com.doctorpet.domain.pet.dto.request.PetUpdateRequest;
import com.doctorpet.domain.pet.dto.response.PetResponse;
import com.doctorpet.domain.pet.entity.PetSpecies;
import com.doctorpet.domain.pet.service.PetService;
import com.doctorpet.global.config.SecurityConfig;
import com.doctorpet.global.security.JwtAccessDeniedHandler;
import com.doctorpet.global.security.JwtAuthenticationEntryPoint;
import com.doctorpet.global.security.JwtTokenProvider;
import com.doctorpet.global.security.MemberBlacklistPort;
import com.doctorpet.global.security.AccessTokenBlacklistPort;
import com.doctorpet.global.security.MemberPrincipal;
import com.doctorpet.global.security.TokenType;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Level 2 — 보안 계약 검증. addFilters를 끄지 않아(기본값 true) 실제 SecurityConfig·
 * JwtAuthenticationFilter가 그대로 동작한다. {@link PetControllerTest}는 addFilters=false로
 * SecurityContext를 직접 주입해 비즈니스 로직 계약만 보는데, 그 방식으로는 "/api/pets가 실수로
 * permitAll로 바뀌어도" 테스트가 통과해버린다(MemberControllerSecurityTest 리뷰 지적과 동일한
 * 이유로 선제 적용).
 */
@WebMvcTest(controllers = PetController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
class PetControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PetService petService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    // JwtAuthenticationFilter 생성자 의존성(탈퇴 회원 Access Token 블랙리스트 체크, 리뷰 지적 P1
    // 대응) — 없으면 컨텍스트 로딩이 실패한다.
    @MockitoBean
    private MemberBlacklistPort memberBlacklistPort;

    @MockitoBean
    private AccessTokenBlacklistPort accessTokenBlacklistPort; // #124 - JwtAuthenticationFilter 생성자 의존성

    @Test
    @DisplayName("Authorization 헤더 없이 요청하면 401을 반환한다 — /api/pets가 실수로 permitAll이 되면 이 테스트가 잡는다")
    void register_withoutAuthorizationHeader_returnsUnauthorized() throws Exception {
        PetCreateRequest request = new PetCreateRequest("초코", PetSpecies.DOG, 3, new BigDecimal("5.4"), true);

        mockMvc.perform(post("/api/pets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_002"));
    }

    @Test
    @DisplayName("유효한 Access Token으로 요청하면 201과 등록된 프로필을 반환한다")
    void register_withValidToken_returnsCreated() throws Exception {
        String accessToken = "valid-access-token";
        given(jwtTokenProvider.validateToken(accessToken)).willReturn(true);
        given(jwtTokenProvider.getTokenType(accessToken)).willReturn(TokenType.ACCESS);
        given(jwtTokenProvider.getMemberPrincipal(accessToken))
                .willReturn(new MemberPrincipal(1L, "guardian@example.com", "GUARDIAN"));
        given(petService.register(anyLong(), any(PetCreateRequest.class)))
                .willReturn(new PetResponse(10L, "초코", PetSpecies.DOG, 3, new BigDecimal("5.4"), true));

        PetCreateRequest request = new PetCreateRequest("초코", PetSpecies.DOG, 3, new BigDecimal("5.4"), true);

        mockMvc.perform(post("/api/pets")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.petId").value(10));
    }

    @Test
    @DisplayName("Authorization 헤더 없이 목록을 조회하면 401을 반환한다")
    void getMyPets_withoutAuthorizationHeader_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/pets"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_002"));
    }

    @Test
    @DisplayName("유효한 Access Token으로 목록을 조회하면 200을 반환한다")
    void getMyPets_withValidToken_returnsOk() throws Exception {
        String accessToken = "valid-access-token";
        given(jwtTokenProvider.validateToken(accessToken)).willReturn(true);
        given(jwtTokenProvider.getTokenType(accessToken)).willReturn(TokenType.ACCESS);
        given(jwtTokenProvider.getMemberPrincipal(accessToken))
                .willReturn(new MemberPrincipal(1L, "guardian@example.com", "GUARDIAN"));
        given(petService.getMyPets(1L))
                .willReturn(List.of(new PetResponse(10L, "초코", PetSpecies.DOG, 3, new BigDecimal("5.4"), true)));

        mockMvc.perform(get("/api/pets").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].petId").value(10));
    }

    @Test
    @DisplayName("Authorization 헤더 없이 상세 조회하면 401을 반환한다 — /api/pets/{petId}가 실수로 permitAll이 되면 이 테스트가 잡는다")
    void getPet_withoutAuthorizationHeader_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/pets/{petId}", 10L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_002"));
    }

    @Test
    @DisplayName("유효한 Access Token으로 상세 조회하면 200을 반환한다")
    void getPet_withValidToken_returnsOk() throws Exception {
        String accessToken = "valid-access-token";
        given(jwtTokenProvider.validateToken(accessToken)).willReturn(true);
        given(jwtTokenProvider.getTokenType(accessToken)).willReturn(TokenType.ACCESS);
        given(jwtTokenProvider.getMemberPrincipal(accessToken))
                .willReturn(new MemberPrincipal(1L, "guardian@example.com", "GUARDIAN"));
        given(petService.getPet(1L, 10L))
                .willReturn(new PetResponse(10L, "초코", PetSpecies.DOG, 3, new BigDecimal("5.4"), true));

        mockMvc.perform(get("/api/pets/{petId}", 10L).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.petId").value(10));
    }

    @Test
    @DisplayName("Authorization 헤더 없이 수정하면 401을 반환한다 — /api/pets/{petId} PATCH가 실수로 permitAll이 되면 이 테스트가 잡는다")
    void updatePet_withoutAuthorizationHeader_returnsUnauthorized() throws Exception {
        PetUpdateRequest request = new PetUpdateRequest("초코2", PetSpecies.DOG, 4, new BigDecimal("6.0"), false);

        mockMvc.perform(patch("/api/pets/{petId}", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_002"));
    }

    @Test
    @DisplayName("유효한 Access Token으로 수정하면 200을 반환한다")
    void updatePet_withValidToken_returnsOk() throws Exception {
        String accessToken = "valid-access-token";
        given(jwtTokenProvider.validateToken(accessToken)).willReturn(true);
        given(jwtTokenProvider.getTokenType(accessToken)).willReturn(TokenType.ACCESS);
        given(jwtTokenProvider.getMemberPrincipal(accessToken))
                .willReturn(new MemberPrincipal(1L, "guardian@example.com", "GUARDIAN"));
        given(petService.update(anyLong(), anyLong(), any(PetUpdateRequest.class)))
                .willReturn(new PetResponse(10L, "초코2", PetSpecies.DOG, 4, new BigDecimal("6.0"), false));

        PetUpdateRequest request = new PetUpdateRequest("초코2", PetSpecies.DOG, 4, new BigDecimal("6.0"), false);

        mockMvc.perform(patch("/api/pets/{petId}", 10L)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.petId").value(10));
    }

    @Test
    @DisplayName("Authorization 헤더 없이 삭제하면 401을 반환한다 — /api/pets/{petId} DELETE가 실수로 permitAll이 되면 이 테스트가 잡는다")
    void deletePet_withoutAuthorizationHeader_returnsUnauthorized() throws Exception {
        mockMvc.perform(delete("/api/pets/{petId}", 10L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_002"));
    }

    @Test
    @DisplayName("유효한 Access Token으로 삭제하면 204를 반환한다")
    void deletePet_withValidToken_returnsNoContent() throws Exception {
        String accessToken = "valid-access-token";
        given(jwtTokenProvider.validateToken(accessToken)).willReturn(true);
        given(jwtTokenProvider.getTokenType(accessToken)).willReturn(TokenType.ACCESS);
        given(jwtTokenProvider.getMemberPrincipal(accessToken))
                .willReturn(new MemberPrincipal(1L, "guardian@example.com", "GUARDIAN"));
        doNothing().when(petService).delete(1L, 10L);

        mockMvc.perform(delete("/api/pets/{petId}", 10L).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("병원 스태프(HOSPITAL_STAFF) 토큰으로 요청하면 403을 반환한다 — /api/pets는 SA §8-2상 보호자 전용")
    void register_withHospitalStaffToken_returnsForbidden() throws Exception {
        String accessToken = "hospital-staff-access-token";
        given(jwtTokenProvider.validateToken(accessToken)).willReturn(true);
        given(jwtTokenProvider.getTokenType(accessToken)).willReturn(TokenType.ACCESS);
        given(jwtTokenProvider.getMemberPrincipal(accessToken))
                .willReturn(new MemberPrincipal(2L, "staff@example.com", "HOSPITAL_STAFF"));

        PetCreateRequest request = new PetCreateRequest("초코", PetSpecies.DOG, 3, new BigDecimal("5.4"), true);

        mockMvc.perform(post("/api/pets")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_003"));
    }
}
