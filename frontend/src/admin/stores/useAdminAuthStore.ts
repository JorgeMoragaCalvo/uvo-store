import { create } from 'zustand'
import { persist } from 'zustand/middleware'

export interface AdminUser {
  id: number
  name: string
  email: string
  permissions: string[]
}

interface AdminAuthState {
  token: string | null
  user: AdminUser | null
  login: (token: string, user: AdminUser) => void
  logout: () => void
}

// A1: the single place that answers "may this user do X". Note it is a convenience for the UI only
// — every endpoint is enforced server-side with @PreAuthorize, so tampering with what's in
// localStorage reveals menu entries that then answer 403.
// A user persisted before permissions existed has no `permissions` array; treat that as none.
export function hasPermission(user: AdminUser | null, permission: string): boolean {
  return user?.permissions?.includes(permission) ?? false
}

// Persisted separately from the storefront's own state (uvostore_cart etc.) so logging out of
// the admin panel never touches a customer's in-progress cart, and vice versa.
export const useAdminAuthStore = create<AdminAuthState>()(
  persist(
    (set) => ({
      token: null,
      user: null,
      login: (token, user) => set({ token, user }),
      logout: () => set({ token: null, user: null }),
    }),
    { name: 'uvostore_admin_auth' },
  ),
)
