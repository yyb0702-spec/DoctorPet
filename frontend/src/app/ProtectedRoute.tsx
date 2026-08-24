// 인증 가드 — 미인증 시 로그인으로 리다이렉트하고, 원래 목적지를 state로 넘긴다.
import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuthStore } from '@/lib/auth/authStore'

export function ProtectedRoute() {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  const location = useLocation()

  if (!isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location }} />
  }
  return <Outlet />
}
