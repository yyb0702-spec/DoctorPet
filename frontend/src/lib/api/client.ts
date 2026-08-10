// ApiResponse 언랩 + 에러 정규화를 한 곳에 모은 얇은 요청 헬퍼.
// 각 feature의 api.ts는 이 http를 통해 호출하고, 성공 시 data(T)만 받는다.
import type { AxiosRequestConfig } from 'axios'
import { api } from '@/lib/api/axios'
import { ApiError, toApiError } from '@/lib/api/error'
import type { ApiResponse } from '@/types/api'

async function request<T>(config: AxiosRequestConfig): Promise<T> {
  try {
    const res = await api.request<ApiResponse<T>>(config)
    const body = res.data
    // 일부 엔드포인트(DELETE)는 204 + 본문 없음, ApiResponse로 감싸지 않는다.
    if (res.status === 204 || body == null || typeof body !== 'object') {
      return undefined as T
    }
    // 성공은 code="SUCCESS" (SA §7). 2xx인데 code가 다르면(예외적) 실제 code/message로 에러화.
    if (body.code !== 'SUCCESS') {
      throw new ApiError(
        body.code,
        body.message ?? '요청을 처리하지 못했습니다.',
        res.status,
      )
    }
    return body.data as T
  } catch (error) {
    throw toApiError(error)
  }
}

export const http = {
  get: <T>(url: string, config?: AxiosRequestConfig) =>
    request<T>({ ...config, method: 'GET', url }),
  post: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
    request<T>({ ...config, method: 'POST', url, data }),
  patch: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
    request<T>({ ...config, method: 'PATCH', url, data }),
  put: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
    request<T>({ ...config, method: 'PUT', url, data }),
  delete: <T>(url: string, config?: AxiosRequestConfig) =>
    request<T>({ ...config, method: 'DELETE', url }),
}
