// 펫 프로필 쿼리·mutation 훅.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { petApi, uploadPetImageFile } from './api'
import type { PetInput } from './schema'
import { useAuthStore } from '@/lib/auth/authStore'

export const petKeys = {
  all: ['pets'] as const,
  detail: (id: number) => ['pets', id] as const,
}

export function usePets() {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  return useQuery({
    queryKey: petKeys.all,
    queryFn: petApi.list,
    enabled: isAuthenticated,
  })
}

export function useCreatePet() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: PetInput) => petApi.create(input),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: petKeys.all }),
  })
}

export function useUpdatePet(petId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: Partial<PetInput>) => petApi.update(petId, input),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: petKeys.all })
      queryClient.invalidateQueries({ queryKey: petKeys.detail(petId) })
    },
  })
}

/*
  프로필 이미지 교체 — 발급(POST upload-url) → presigned PUT → 확정(PATCH imageUrl) 3단계다.
  서버는 업로드 성공 여부를 알 수 없어서 저장 확정을 별도 PATCH로 요구한다(PetService 주석).
  중간에 실패하면 imageUrl을 확정하지 않으므로 펫 프로필은 이전 이미지를 그대로 유지한다.
*/
export function useUploadPetImage(petId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (file: File) => {
      const issued = await petApi.createImageUploadUrl(petId, file.type)
      await uploadPetImageFile(issued.uploadUrl, file)
      return petApi.update(petId, { imageUrl: issued.imageUrl })
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: petKeys.all })
      queryClient.invalidateQueries({ queryKey: petKeys.detail(petId) })
    },
  })
}

export function useDeletePet() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (petId: number) => petApi.remove(petId),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: petKeys.all }),
  })
}
