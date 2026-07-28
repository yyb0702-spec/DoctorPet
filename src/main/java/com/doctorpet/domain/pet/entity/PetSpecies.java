package com.doctorpet.domain.pet.entity;

/*
  반려동물 종(species) 화이트리스트. 자유 텍스트를 허용하면 병원 진료역량 매칭·검색 필터링에
  쓰기 어려워 화이트리스트로 제한한다(A 도메인 결정 #4, SA §4 pet_profiles).
 */
public enum PetSpecies {
    DOG,
    CAT
}
