import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'

// MSW mock을 켜는 조건: 개발 + VITE_ENABLE_MOCKS=true.
// 미구현 API(병원 검색·예약 목록/상세·결제 내역·알림 등)를 계약 기준으로 가로챈다.
async function enableMocking() {
  if (!import.meta.env.DEV || import.meta.env.VITE_ENABLE_MOCKS !== 'true') {
    return
  }
  const { worker } = await import('./mocks/browser')
  // 실연동 대상(핸들러 없는 요청)은 그대로 백엔드 프록시로 흘려보낸다.
  return worker.start({ onUnhandledRequest: 'bypass' })
}

enableMocking().then(() => {
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <App />
    </StrictMode>,
  )
})
