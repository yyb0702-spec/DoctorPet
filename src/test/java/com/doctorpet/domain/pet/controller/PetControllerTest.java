package com.doctorpet.domain.pet.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doctorpet.domain.pet.dto.request.PetCreateRequest;
import com.doctorpet.domain.pet.dto.request.PetUpdateRequest;
import com.doctorpet.domain.pet.dto.response.PetResponse;
import com.doctorpet.domain.pet.entity.PetSpecies;
import com.doctorpet.domain.pet.exception.PetErrorCode;
import com.doctorpet.domain.pet.service.PetService;
import com.doctorpet.global.config.SecurityConfig;
import com.doctorpet.global.exception.CommonErrorCode;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.security.JwtAccessDeniedHandler;
import com.doctorpet.global.security.JwtAuthenticationEntryPoint;
import com.doctorpet.global.security.JwtTokenProvider;
import com.doctorpet.global.security.MemberPrincipal;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Level 2 — API 계약(상태코드·ApiResponse 포맷·Validation) 검증.
 * addFilters=false로 Security 필터 체인은 끄고 SecurityContext를 직접 주입한다(AuthControllerTest·
 * MemberControllerTest와 동일 패턴). 인증 여부 자체에 대한 검증은 {@link PetControllerSecurityTest}가 맡는다.
 */
@WebMvcTest(controllers = PetController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
class PetControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PetService petService;

    // @WebMvcTest는 Filter 타입 빈(JwtAuthenticationFilter)을 addFilters=false여도 컨텍스트에
    // 생성하므로, 그 생성자 의존성인 JwtTokenProvider가 없으면 컨텍스트 로딩 자체가 실패한다.
    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("등록 성공 시 201과 등록된 프로필을 반환한다")
    void register_success() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(1L));
        PetCreateRequest request = new PetCreateRequest("초코", PetSpecies.DOG, 3, new BigDecimal("5.4"), true);
        PetResponse response = new PetResponse(10L, "초코", PetSpecies.DOG, 3, new BigDecimal("5.4"), true);
        given(petService.register(anyLong(), any(PetCreateRequest.class))).willReturn(response);

        mockMvc.perform(post("/api/pets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.petId").value(10))
                .andExpect(jsonPath("$.data.name").value("초코"))
                .andExpect(jsonPath("$.data.species").value("DOG"))
                .andExpect(jsonPath("$.data.age").value(3))
                .andExpect(jsonPath("$.data.neutered").value(true));
    }

    @Test
    @DisplayName("이름이 비어있으면 400과 COMMON_001을 반환한다")
    void register_blankName() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(1L));
        String body = """
                {"name":"","species":"DOG","age":3,"weight":5.4,"neutered":true}
                """;

        mockMvc.perform(post("/api/pets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("이름이 255자를 초과하면 400과 COMMON_001을 반환한다")
    void register_nameTooLong() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(1L));
        PetCreateRequest request = new PetCreateRequest(
                "초".repeat(256), PetSpecies.DOG, 3, new BigDecimal("5.4"), true);

        mockMvc.perform(post("/api/pets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("species가 화이트리스트(DOG/CAT) 밖의 값이면 400과 COMMON_001을 반환한다")
    void register_invalidSpecies() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(1L));
        String body = """
                {"name":"초코","species":"BIRD","age":3,"weight":5.4,"neutered":true}
                """;

        mockMvc.perform(post("/api/pets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("나이가 음수면 400과 COMMON_001을 반환한다")
    void register_negativeAge() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(1L));
        PetCreateRequest request = new PetCreateRequest(
                "초코", PetSpecies.DOG, -1, new BigDecimal("5.4"), true);

        mockMvc.perform(post("/api/pets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("체중이 0 이하면 400과 COMMON_001을 반환한다")
    void register_nonPositiveWeight() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(1L));
        PetCreateRequest request = new PetCreateRequest(
                "초코", PetSpecies.DOG, 3, new BigDecimal("0"), true);

        mockMvc.perform(post("/api/pets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("중성화 여부가 없으면 400과 COMMON_001을 반환한다")
    void register_missingNeutered() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(1L));
        String body = """
                {"name":"초코","species":"DOG","age":3,"weight":5.4}
                """;

        mockMvc.perform(post("/api/pets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("목록 조회 성공 시 200과 내 반려동물 목록을 반환한다")
    void getMyPets_success() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(1L));
        List<PetResponse> responses = List.of(
                new PetResponse(10L, "초코", PetSpecies.DOG, 3, new BigDecimal("5.4"), true),
                new PetResponse(11L, "나비", PetSpecies.CAT, 2, new BigDecimal("3.2"), false));
        given(petService.getMyPets(1L)).willReturn(responses);

        mockMvc.perform(get("/api/pets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].petId").value(10))
                .andExpect(jsonPath("$.data[1].petId").value(11));
    }

    @Test
    @DisplayName("상세 조회 성공 시 200과 반려동물 프로필을 반환한다")
    void getPet_success() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(1L));
        PetResponse response = new PetResponse(10L, "초코", PetSpecies.DOG, 3, new BigDecimal("5.4"), true);
        given(petService.getPet(1L, 10L)).willReturn(response);

        mockMvc.perform(get("/api/pets/{petId}", 10L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.petId").value(10))
                .andExpect(jsonPath("$.data.name").value("초코"));
    }

    @Test
    @DisplayName("존재하지 않는 petId를 조회하면 404와 PET_001을 반환한다")
    void getPet_notFound() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(1L));
        given(petService.getPet(1L, 999L)).willThrow(new ServiceException(PetErrorCode.PET_NOT_FOUND));

        mockMvc.perform(get("/api/pets/{petId}", 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PET_001"));
    }

    @Test
    @DisplayName("다른 회원 소유의 반려동물을 조회하면 403과 COMMON_003을 반환한다")
    void getPet_notOwner_returnsForbidden() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(1L));
        given(petService.getPet(1L, 10L)).willThrow(new ServiceException(CommonErrorCode.FORBIDDEN));

        mockMvc.perform(get("/api/pets/{petId}", 10L))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_003"));
    }

    @Test
    @DisplayName("수정 성공 시 200과 수정된 프로필을 반환한다")
    void update_success() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(1L));
        PetUpdateRequest request = new PetUpdateRequest("초코2", PetSpecies.DOG, 4, new BigDecimal("6.0"), false);
        PetResponse response = new PetResponse(10L, "초코2", PetSpecies.DOG, 4, new BigDecimal("6.0"), false);
        given(petService.update(eq(1L), eq(10L), any(PetUpdateRequest.class))).willReturn(response);

        mockMvc.perform(patch("/api/pets/{petId}", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.petId").value(10))
                .andExpect(jsonPath("$.data.name").value("초코2"))
                .andExpect(jsonPath("$.data.age").value(4))
                .andExpect(jsonPath("$.data.neutered").value(false));
    }

    @Test
    @DisplayName("수정 시 이름이 비어있으면 400과 COMMON_001을 반환한다")
    void update_blankName() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(1L));
        String body = """
                {"name":"","species":"DOG","age":4,"weight":6.0,"neutered":false}
                """;

        mockMvc.perform(patch("/api/pets/{petId}", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("수정 시 species가 화이트리스트 밖의 값이면 400과 COMMON_001을 반환한다")
    void update_invalidSpecies() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(1L));
        String body = """
                {"name":"초코","species":"BIRD","age":4,"weight":6.0,"neutered":false}
                """;

        mockMvc.perform(patch("/api/pets/{petId}", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("수정 시 나이가 음수면 400과 COMMON_001을 반환한다")
    void update_negativeAge() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(1L));
        PetUpdateRequest request = new PetUpdateRequest("초코", PetSpecies.DOG, -1, new BigDecimal("6.0"), false);

        mockMvc.perform(patch("/api/pets/{petId}", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("수정 시 체중이 0 이하면 400과 COMMON_001을 반환한다")
    void update_nonPositiveWeight() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(1L));
        PetUpdateRequest request = new PetUpdateRequest("초코", PetSpecies.DOG, 4, new BigDecimal("0"), false);

        mockMvc.perform(patch("/api/pets/{petId}", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("수정 시 중성화 여부가 없으면 400과 COMMON_001을 반환한다")
    void update_missingNeutered() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(1L));
        String body = """
                {"name":"초코","species":"DOG","age":4,"weight":6.0}
                """;

        mockMvc.perform(patch("/api/pets/{petId}", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("존재하지 않는 petId를 수정하면 404와 PET_001을 반환한다")
    void update_notFound() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(1L));
        PetUpdateRequest request = new PetUpdateRequest("초코", PetSpecies.DOG, 4, new BigDecimal("6.0"), false);
        given(petService.update(eq(1L), eq(999L), any(PetUpdateRequest.class)))
                .willThrow(new ServiceException(PetErrorCode.PET_NOT_FOUND));

        mockMvc.perform(patch("/api/pets/{petId}", 999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PET_001"));
    }

    @Test
    @DisplayName("다른 회원 소유의 반려동물을 수정하면 403과 COMMON_003을 반환한다")
    void update_notOwner_returnsForbidden() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(1L));
        PetUpdateRequest request = new PetUpdateRequest("초코", PetSpecies.DOG, 4, new BigDecimal("6.0"), false);
        given(petService.update(eq(1L), eq(10L), any(PetUpdateRequest.class)))
                .willThrow(new ServiceException(CommonErrorCode.FORBIDDEN));

        mockMvc.perform(patch("/api/pets/{petId}", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_003"));
    }

    private Authentication memberAuthentication(Long memberId) {
        MemberPrincipal principal = new MemberPrincipal(memberId, "guardian@example.com", "GUARDIAN");
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_GUARDIAN")));
    }
}
