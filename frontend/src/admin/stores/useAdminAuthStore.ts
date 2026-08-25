import { create } from 'zustand'
import { persist } from 'zustand/middleware'

export interface AdminUser {
  id: number
  name: string
  email: string
}

interface AdminAuthState {
  token: string | null
  user: AdminUser | null
  login: (token: string, user: AdminUser) => void
  logout: () => void
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
