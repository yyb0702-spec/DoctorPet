/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { fileURLToPath, URL } from 'node:url'

// Vite 설정 — dev proxy(/api→백엔드), 경로 별칭(@), Tailwind, Vitest.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      // 백엔드 CORS 미설정 → dev 서버가 /api 요청을 백엔드로 프록시한다.
      '/api': {
        target: process.env.VITE_PROXY_TARGET ?? 'http://localhost:8080',
        changeOrigin: true,
      },
      // 채팅은 native WebSocket + STOMP 전용 경로다. API 프록시와 분리해 Upgrade를 전달한다.
      '/ws/chat': {
        target: process.env.VITE_PROXY_TARGET ?? 'http://localhost:8080',
        // Spring의 native WebSocket 기본 same-origin 검사는 Origin과 Host를 대조한다.
        // 개발 브라우저(127.0.0.1:5173)의 Host를 유지해야 Upgrade가 STOMP CONNECT까지 도달한다.
        // REST 프록시와 달리 여기서는 target Host로 바꾸지 않는다.
        ws: true,
      },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: true,
  },
})
