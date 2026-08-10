/// <reference types="vite/client" />

// 클라이언트 노출 환경 변수 타입.
interface ImportMetaEnv {
  readonly VITE_API_BASE_URL: string
  readonly VITE_ENABLE_MOCKS?: string
  readonly VITE_FULL_MOCK?: string
  readonly VITE_ALLOW_MANUAL_BILLING_KEY?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
