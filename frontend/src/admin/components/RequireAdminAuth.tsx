import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { useAdminAuthStore } from '@/admin/stores/useAdminAuthStore'

export default function RequireAdminAuth({ children }: { children: ReactNode }) {
  const token = useAdminAuthStore((state) => state.token)
  const location = useLocation()

  if (!token) {
    return <Navigate to="/admin/login" state={{ from: location }} replace />
  }

  return children
}
