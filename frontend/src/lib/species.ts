// 반려동물 종 표시 라벨·이모지·선택지 순서. 백엔드 PetSpecies(8종)와 일치시킨다.
// 화면에서 종을 문자열 비교로 하드코딩하지 말고 여기 맵을 쓴다(종 추가 시 한 곳만 고치면 됨).
import { PetSpecies } from '@/types/enums'

export const SPECIES_LABEL: Record<PetSpecies, string> = {
  DOG: '강아지',
  CAT: '고양이',
  BIRD: '새',
  RABBIT: '토끼',
  HAMSTER: '햄스터',
  GUINEA_PIG: '기니피그',
  FERRET: '페럿',
  REPTILE: '파충류',
}

export const SPECIES_EMOJI: Record<PetSpecies, string> = {
  DOG: '🐶',
  CAT: '🐱',
  BIRD: '🐦',
  RABBIT: '🐰',
  HAMSTER: '🐹',
  GUINEA_PIG: '🐭',
  FERRET: '🦦',
  REPTILE: '🦎',
}

// 등록 폼·검색 필터 선택지 노출 순서.
export const SPECIES_ORDER: PetSpecies[] = [
  PetSpecies.DOG,
  PetSpecies.CAT,
  PetSpecies.BIRD,
  PetSpecies.RABBIT,
  PetSpecies.HAMSTER,
  PetSpecies.GUINEA_PIG,
  PetSpecies.FERRET,
  PetSpecies.REPTILE,
]

// 백엔드에서 온 종 문자열을 안전하게 라벨로 바꾼다(미지의 값이면 원문 그대로).
export function speciesLabel(species: string): string {
  return SPECIES_LABEL[species as PetSpecies] ?? species
}

export function speciesEmoji(species: string): string {
  return SPECIES_EMOJI[species as PetSpecies] ?? '🐾'
}
