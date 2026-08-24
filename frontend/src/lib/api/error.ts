// 백엔드 예외 응답을 프론트 표준 에러로 정규화한다 (SA §7).
// 실패 응답도 ApiResponse{ code, message, data:null } 형태로 온다.
import { AxiosError } from 'axios'
import type { ApiResponse } from '@/types/api'

export class ApiError extends Error {
  // 백엔드 ErrorCode ({DOMAIN}_{3자리}) 또는 통신 실패 시 합성 코드.
  readonly code: string
  // HTTP 상태 (네트워크 실패 시 0).
  readonly status: number

  constructor(code: string, message: string, status: number) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
  }
}

// 통신 자체가 실패했을 때 쓰는 합성 코드.
export const NETWORK_ERROR_CODE = 'NETWORK_ERROR'
export const UNKNOWN_ERROR_CODE = 'UNKNOWN_ERROR'

export function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) return error

  if (error instanceof AxiosError) {
    const status = error.response?.status ?? 0
    const body = error.response?.data as ApiResponse<unknown> | undefined
    if (body && typeof body.code === 'string') {
      return new ApiError(body.code, body.message ?? '요청을 처리하지 못했습니다.', status)
    }
    if (status === 0) {
      return new ApiError(
        NETWORK_ERROR_CODE,
        '서버에 연결하지 못했습니다. 네트워크를 확인해 주세요.',
        0,
      )
    }
    return new ApiError(UNKNOWN_ERROR_CODE, error.message, status)
  }

  if (error instanceof Error) {
    return new ApiError(UNKNOWN_ERROR_CODE, error.message, 0)
  }
  return new ApiError(UNKNOWN_ERROR_CODE, '알 수 없는 오류가 발생했습니다.', 0)
}
