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
  // F19: la salida fue por un 401 y no porque el administrador pulsara "cerrar sesión". Login.tsx la
  // consume una sola vez para explicar por qué apareció ahí. Va en el estado persistido porque la
  // expulsión y la pantalla de login son dos renders distintos.
  sessionExpired: boolean
  login: (token: string, user: AdminUser) => void
  logout: () => void
  markSessionExpired: () => void
  clearSessionExpired: () => void
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
      sessionExpired: false,
      // Un login correcto borra la marca: si se quedara puesta, el aviso reaparecería la próxima vez
      // que alguien visitara la pantalla de login.
      login: (token, user) => set({ token, user, sessionExpired: false }),
      logout: () => set({ token: null, user: null }),
      markSessionExpired: () => set({ sessionExpired: true }),
      clearSessionExpired: () => set({ sessionExpired: false }),
    }),
    { name: 'uvostore_admin_auth' },
  ),
)
