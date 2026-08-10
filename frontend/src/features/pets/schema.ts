// 펫 프로필 폼 검증 (SA §8-2). 등록 { name, species, age, weight, neutered }.
import { z } from 'zod'
import { PetSpecies } from '@/types/enums'

export const petSchema = z.object({
  name: z.string().min(1, '이름을 입력해 주세요.').max(30, '이름은 30자 이하여야 합니다.'),
  species: z.enum([PetSpecies.DOG, PetSpecies.CAT], {
    message: '종을 선택해 주세요.',
  }),
  // Input에서 valueAsNumber로 등록 → NaN 방어 후 범위 검증.
  age: z
    .number({ message: '나이를 입력해 주세요.' })
    .int('나이는 정수여야 합니다.')
    .min(0, '나이는 0 이상이어야 합니다.')
    .max(50, '나이를 확인해 주세요.'),
  // 백엔드 @Digits(integer=3, fraction=2) 미러링: 정수부 3자리 미만(<1000)·소수 2자리 이하.
  weight: z
    .number({ message: '몸무게를 입력해 주세요.' })
    .positive('몸무게는 0보다 커야 합니다.')
    .max(200, '몸무게를 확인해 주세요.')
    .refine((v) => Math.abs(v * 100 - Math.round(v * 100)) < 1e-9, {
      message: '몸무게는 소수점 둘째 자리까지 입력할 수 있습니다.',
    }),
  neutered: z.boolean(),
})
export type PetInput = z.infer<typeof petSchema>
