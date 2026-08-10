// AI 상담 API (실연동). 미인증도 호출 가능(백엔드가 IP 기준 rate limit).
import { http } from '@/lib/api/client'
import type { AiConsultationInput, AiConsultationResult } from './types'

export const aiApi = {
  consult: (input: AiConsultationInput) =>
    http.post<AiConsultationResult>('/ai/consultations', input),
}
