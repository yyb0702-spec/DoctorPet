package com.doctorpet.domain.pet.controller;

import com.doctorpet.domain.pet.dto.request.PetCreateRequest;
import com.doctorpet.domain.pet.dto.request.PetUpdateRequest;
import com.doctorpet.domain.pet.dto.response.PetResponse;
import com.doctorpet.domain.pet.service.PetService;
import com.doctorpet.global.response.ApiResponse;
import com.doctorpet.global.security.MemberPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
  반려동물 프로필 API. SA §8-2 — 등록·목록 조회·상세 조회·수정·삭제.
 */
@RestController
@RequestMapping("/api/pets")
@RequiredArgsConstructor
public class PetController {

    private final PetService petService;

    @PostMapping
    public ResponseEntity<ApiResponse<PetResponse>> register(
            @AuthenticationPrincipal MemberPrincipal principal,
            @Valid @RequestBody PetCreateRequest request
    ) {
        PetResponse response = petService.register(principal.memberId(), request);

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<PetResponse>>> getMyPets(
            @AuthenticationPrincipal MemberPrincipal principal
    ) {
        List<PetResponse> response = petService.getMyPets(principal.memberId());

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{petId}")
    public ResponseEntity<ApiResponse<PetResponse>> getPet(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable Long petId
    ) {
        PetResponse response = petService.getPet(principal.memberId(), petId);

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PatchMapping("/{petId}")
    public ResponseEntity<ApiResponse<PetResponse>> updatePet(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable Long petId,
            @Valid @RequestBody PetUpdateRequest request
    ) {
        PetResponse response = petService.update(principal.memberId(), petId, request);

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @DeleteMapping("/{petId}")
    public ResponseEntity<Void> deletePet(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable Long petId
    ) {
        petService.delete(principal.memberId(), petId);
        // 본문 없는 성공 응답은 204로 반환한다(코드컨벤션 — 생성 201·조회/수정 200·본문 없음 204).
        return ResponseEntity.noContent().build();
    }
}
