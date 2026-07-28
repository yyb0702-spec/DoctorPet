package com.doctorpet.domain.pet.controller;

import com.doctorpet.domain.pet.dto.request.PetCreateRequest;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
  반려동물 프로필 API. SA §8-2 — 이 Issue 범위는 등록·목록 조회·상세 조회까지다(수정·삭제는 별도 작업).
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
}
