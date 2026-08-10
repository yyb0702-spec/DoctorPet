// 펫 프로필 쿼리·mutation 훅.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { petApi } from './api'
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

export function useDeletePet() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (petId: number) => petApi.remove(petId),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: petKeys.all }),
  })
}
