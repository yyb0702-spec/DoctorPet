// JWT 토큰 저장소 (SA §6-1 Access/Refresh).
// axios 인터셉터가 React 밖에서 동기적으로 접근해야 하므로 모듈 단위 단일 출처로 둔다.
//
// 저장 전략(MVP): access·refresh 모두 localStorage. 새로고침 후에도 세션이 유지된다.
// 트레이드오프: localStorage는 XSS에 노출된다. 백엔드가 토큰을 body로 반환하므로
// httpOnly 쿠키를 쓰려면 백엔드 변경이 필요하다 — 안정화 후 재검토(프론트 스택 문서 §4).
// 이 파일 뒤로 저장 위치를 캡슐화해, 전환 시 변경 범위를 여기로 국한한다.

const ACCESS_KEY = 'dp.accessToken'
const REFRESH_KEY = 'dp.refreshToken'

let accessTokenCache: string | null = null
let refreshTokenCache: string | null = null
let loaded = false

type Listener = () => void
const listeners = new Set<Listener>()

function load() {
  if (loaded) return
  try {
    accessTokenCache = localStorage.getItem(ACCESS_KEY)
    refreshTokenCache = localStorage.getItem(REFRESH_KEY)
  } catch {
    // SSR/프라이빗 모드 등 localStorage 불가 환경 방어.
    accessTokenCache = null
    refreshTokenCache = null
  }
  loaded = true
}

function notify() {
  listeners.forEach((l) => l())
}

export const tokenStore = {
  getAccessToken(): string | null {
    load()
    return accessTokenCache
  },
  getRefreshToken(): string | null {
    load()
    return refreshTokenCache
  },
  setTokens(accessToken: string, refreshToken: string) {
    accessTokenCache = accessToken
    refreshTokenCache = refreshToken
    try {
      localStorage.setItem(ACCESS_KEY, accessToken)
      localStorage.setItem(REFRESH_KEY, refreshToken)
    } catch {
      /* noop */
    }
    notify()
  },
  clear() {
    accessTokenCache = null
    refreshTokenCache = null
    try {
      localStorage.removeItem(ACCESS_KEY)
      localStorage.removeItem(REFRESH_KEY)
    } catch {
      /* noop */
    }
    notify()
  },
  hasSession(): boolean {
    load()
    return Boolean(accessTokenCache)
  },
  // UI(zustand 등)가 토큰 변화에 반응하도록 구독을 제공한다.
  subscribe(listener: Listener): () => void {
    listeners.add(listener)
    return () => listeners.delete(listener)
  },
}
