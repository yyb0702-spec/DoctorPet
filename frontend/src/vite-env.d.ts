/// <reference types="vite/client" />

// 클라이언트 노출 환경 변수 타입.
interface ImportMetaEnv {
  readonly VITE_API_BASE_URL: string
  readonly VITE_ENABLE_MOCKS?: string
  readonly VITE_FULL_MOCK?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
