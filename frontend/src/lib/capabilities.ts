// 진료역량(CapabilityValue) 표시 라벨과 분류별 노출 순서. 백엔드 CapabilityValue(19개)와 일치시킨다.
// 종 8개는 PetSpecies와 값이 같으므로 SPECIES_LABEL을 재사용한다(두 맵이 따로 흘러가지 않게).
import { CapabilityType, CapabilityValue } from '@/types/enums'
import { SPECIES_LABEL } from '@/lib/species'
import type { PetSpecies } from '@/types/enums'

export const CAPABILITY_TYPE_LABEL: Record<CapabilityType, string> = {
  SPECIES: '진료 가능 종',
  EXAM: '검사',
  TREATMENT: '치료',
  EQUIPMENT: '장비',
}

// 분류별 값 목록. 백엔드 CapabilityValue의 type과 같은 묶음이다.
export const CAPABILITY_GROUPS: {
  type: CapabilityType
  values: CapabilityValue[]
}[] = [
  {
    type: CapabilityType.SPECIES,
    values: [
      CapabilityValue.DOG,
      CapabilityValue.CAT,
      CapabilityValue.BIRD,
      CapabilityValue.RABBIT,
      CapabilityValue.HAMSTER,
      CapabilityValue.GUINEA_PIG,
      CapabilityValue.FERRET,
      CapabilityValue.REPTILE,
    ],
  },
  {
    type: CapabilityType.EXAM,
    values: [
      CapabilityValue.BLOOD_TEST,
      CapabilityValue.XRAY,
      CapabilityValue.ULTRASOUND,
    ],
  },
  {
    type: CapabilityType.TREATMENT,
    values: [
      CapabilityValue.ORTHOPEDIC_CARE,
      CapabilityValue.DENTAL_CARE,
      CapabilityValue.OPHTHALMIC_CARE,
      CapabilityValue.REHABILITATION,
      CapabilityValue.ONCOLOGY_CARE,
    ],
  },
  {
    type: CapabilityType.EQUIPMENT,
    values: [
      CapabilityValue.CT,
      CapabilityValue.MRI,
      CapabilityValue.ENDOSCOPE,
    ],
  },
]

// 종 이외 값의 라벨. 종은 SPECIES_LABEL에서 가져온다.
const NON_SPECIES_LABEL: Record<string, string> = {
  BLOOD_TEST: '혈액검사',
  XRAY: '엑스레이',
  ULTRASOUND: '초음파',
  ORTHOPEDIC_CARE: '정형외과',
  DENTAL_CARE: '치과',
  OPHTHALMIC_CARE: '안과',
  REHABILITATION: '재활치료',
  ONCOLOGY_CARE: '종양치료',
  CT: 'CT',
  MRI: 'MRI',
  ENDOSCOPE: '내시경',
}

const KNOWN_VALUES = new Set<string>(
  CAPABILITY_GROUPS.flatMap((group) => group.values),
)

/*
  이 화면이 아는 값인지. 백엔드가 화이트리스트를 늘렸는데 프론트가 아직 모르면 그 값은 분류
  그룹 어디에도 없어 체크박스가 생기지 않는다 — 저장 시 값은 보존되지만 스태프가 해제할 방법이
  없어진다. 그래서 편집 화면이 "모르는 값"을 따로 모아 보여줄 수 있게 판별만 내보낸다.
*/
export function isKnownCapability(capability: string): boolean {
  return KNOWN_VALUES.has(capability)
}

// 백엔드에서 온 값을 라벨로 바꾼다(모르는 값이면 원문 그대로 — 화이트리스트가 늘어나도 화면이 깨지지 않는다).
export function capabilityLabel(capability: string): string {
  return (
    SPECIES_LABEL[capability as PetSpecies] ??
    NON_SPECIES_LABEL[capability] ??
    capability
  )
}
