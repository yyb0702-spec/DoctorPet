// 펫 프로필 API (SA §8-2). PR 리뷰 중 — 머지되면 실연동, 그 전엔 MSW mock.
// 백엔드 PetResponse 실제 필드 기준: petId, name, species, age, weight, neutered.
// (createdAt 없음. DELETE는 204 + 본문 없음.)
import { http } from '@/lib/api/client'
import type { PetSpecies } from '@/types/enums'
import type { PetInput } from './schema'

export interface Pet {
  petId: number
  name: string
  species: PetSpecies
  age: number
  weight: number
  neutered: boolean
}

export const petApi = {
  list: () => http.get<Pet[]>('/pets'),
  get: (petId: number) => http.get<Pet>(`/pets/${petId}`),
  create: (input: PetInput) => http.post<Pet>('/pets', input),
  update: (petId: number, input: Partial<PetInput>) =>
    http.patch<Pet>(`/pets/${petId}`, input),
  remove: (petId: number) => http.delete<void>(`/pets/${petId}`),
}
