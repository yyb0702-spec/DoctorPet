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
  // 프로필 이미지. presigned 업로드를 마친 뒤 PATCH로 확정한 값이고, 없으면 null이다.
  imageUrl: string | null
}

// POST /pets/{petId}/image/upload-url 응답. uploadUrl에 파일을 직접 PUT한 뒤 imageUrl로 저장을 확정한다.
export interface PetImageUploadUrl {
  uploadUrl: string
  imageUrl: string
  expiresInSeconds: number
}

export const petApi = {
  list: () => http.get<Pet[]>('/pets'),
  get: (petId: number) => http.get<Pet>(`/pets/${petId}`),
  create: (input: PetInput) => http.post<Pet>('/pets', input),
  // imageUrl은 폼(PetInput) 밖 필드다 — presigned 업로드를 마친 뒤 저장을 확정할 때만 함께 보낸다.
  update: (petId: number, input: Partial<PetInput> & { imageUrl?: string }) =>
    http.patch<Pet>(`/pets/${petId}`, input),
  remove: (petId: number) => http.delete<void>(`/pets/${petId}`),
  // 프로필 이미지 업로드 URL 발급. contentType은 image/jpeg·png·webp만 허용된다(서버 @Pattern).
  createImageUploadUrl: (petId: number, contentType: string) =>
    http.post<PetImageUploadUrl>(`/pets/${petId}/image/upload-url`, {
      contentType,
    }),
}

/*
  발급받은 presigned URL로 파일 바이트를 직접 올린다(서버는 바이트를 중계하지 않는다).

  공용 http/axios 인스턴스를 쓰지 않는 이유: 그 인스턴스는 Authorization 헤더를 붙이고 401이면
  재발급 인터셉터를 태우는데, presigned URL은 쿼리 서명으로 이미 인가돼 있어 헤더가 붙으면 S3가
  서명 불일치로 거절한다. 그래서 fetch로 헤더를 Content-Type 하나만 실어 보낸다.
*/
export async function uploadPetImageFile(
  uploadUrl: string,
  file: File,
): Promise<void> {
  const res = await fetch(uploadUrl, {
    method: 'PUT',
    headers: { 'Content-Type': file.type },
    body: file,
  })
  if (!res.ok) {
    throw new Error(`이미지 업로드에 실패했습니다. (HTTP ${res.status})`)
  }
}
