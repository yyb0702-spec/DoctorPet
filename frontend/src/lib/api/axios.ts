// axios 인스턴스 + 인터셉터.
// 요청: Access Token 자동 첨부.
// 응답 401: POST /api/auth/reissue로 Refresh 토큰 회전 재발급 후 원 요청 1회 재시도.
//           동시 401은 single-flight로 재발급 1회만 수행. 재발급 실패 시 세션 만료 발행.
import axios, {
  AxiosError,
  type AxiosRequestConfig,
  type InternalAxiosRequestConfig,
} from 'axios'
import { tokenStore } from '@/lib/auth/tokenStore'
import { emitSessionExpired } from '@/lib/api/authEvents'
import type { ApiResponse } from '@/types/api'

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api'

// 재발급을 시도하면 안 되는(또는 재시도 대상이 아닌) 인증 경로.
const AUTH_PATHS = ['/auth/login', '/auth/signup', '/auth/reissue']

export const api = axios.create({
  baseURL: BASE_URL,
  headers: { 'Content-Type': 'application/json' },
})

// 재발급 전용 클라이언트 — 인터셉터 재귀를 피하기 위해 분리한다.
const reissueClient = axios.create({
  baseURL: BASE_URL,
  headers: { 'Content-Type': 'application/json' },
})

api.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = tokenStore.getAccessToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 진행 중인 재발급 요청 (single-flight). 동시 401들이 이 하나를 공유한다.
let reissuePromise: Promise<string> | null = null

async function reissueAccessToken(): Promise<string> {
  const refreshToken = tokenStore.getRefreshToken()
  if (!refreshToken) throw new Error('no refresh token')

  const { data } = await reissueClient.post<
    ApiResponse<{ accessToken: string; refreshToken: string }>
  >('/auth/reissue', { refreshToken })

  const payload = data.data
  if (!payload?.accessToken || !payload?.refreshToken) {
    throw new Error('reissue returned no tokens')
  }
  // 회전: 새 access·refresh 쌍으로 교체한다.
  tokenStore.setTokens(payload.accessToken, payload.refreshToken)
  return payload.accessToken
}

interface RetriableConfig extends AxiosRequestConfig {
  _retry?: boolean
}

api.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const original = error.config as
      | (InternalAxiosRequestConfig & RetriableConfig)
      | undefined

    const status = error.response?.status
    const url = original?.url ?? ''
    const isAuthPath = AUTH_PATHS.some((p) => url.includes(p))

    // 401 + 재발급 가능 + 아직 재시도 안 함 + 인증 경로 아님 → 재발급 후 재시도.
    if (
      status === 401 &&
      original &&
      !original._retry &&
      !isAuthPath &&
      tokenStore.getRefreshToken()
    ) {
      original._retry = true
      try {
        if (!reissuePromise) {
          reissuePromise = reissueAccessToken().finally(() => {
            reissuePromise = null
          })
        }
        const newAccess = await reissuePromise
        original.headers = original.headers ?? {}
        ;(original.headers as Record<string, string>).Authorization =
          `Bearer ${newAccess}`
        return api(original)
      } catch {
        // 재발급 실패(Refresh 만료·재사용 감지 등) → 세션 정리 후 UI에 알림.
        tokenStore.clear()
        emitSessionExpired()
      }
    }

    return Promise.reject(error)
  },
)
