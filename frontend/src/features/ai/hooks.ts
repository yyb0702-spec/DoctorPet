// AI 상담 훅.
import { useMutation } from '@tanstack/react-query'
import { aiApi } from './api'
import type { AiConsultationInput } from './types'

export function useAiConsultation() {
  return useMutation({
    mutationFn: (input: AiConsultationInput) => aiApi.consult(input),
  })
}
