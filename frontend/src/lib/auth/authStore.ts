// 인증 상태를 UI에 반응형으로 노출하는 전역 스토어.
// 토큰 자체의 단일 출처는 tokenStore이고, 이 스토어는 그 파생 상태(로그인 여부)만 관리한다.
import { create } from 'zustand'
import { tokenStore } from '@/lib/auth/tokenStore'

interface AuthState {
  isAuthenticated: boolean
  // 로그인 성공(토큰 저장 후) 호출.
  signIn: (accessToken: string, refreshToken: string) => void
  // 명시적 로그아웃(토큰 폐기).
  signOut: () => void
  // 외부(인터셉터·tokenStore)에서 토큰이 바뀌었을 때 상태 동기화.
  sync: () => void
}

export const useAuthStore = create<AuthState>((set) => ({
  isAuthenticated: tokenStore.hasSession(),
  signIn: (accessToken, refreshToken) => {
    tokenStore.setTokens(accessToken, refreshToken)
    set({ isAuthenticated: true })
  },
  signOut: () => {
    tokenStore.clear()
    set({ isAuthenticated: false })
  },
  sync: () => set({ isAuthenticated: tokenStore.hasSession() }),
}))

// tokenStore 변화(재발급 회전·clear 등)를 스토어에 반영.
tokenStore.subscribe(() => {
  useAuthStore.getState().sync()
})
