import { beforeEach, describe, expect, it } from 'vitest'
import { useAdminAuthStore } from './useAdminAuthStore'

describe('useAdminAuthStore', () => {
  beforeEach(() => {
    localStorage.clear()
    useAdminAuthStore.setState({ token: null, user: null })
  })

  it('starts logged out', () => {
    expect(useAdminAuthStore.getState().token).toBeNull()
    expect(useAdminAuthStore.getState().user).toBeNull()
  })

  it('stores the token and user on login', () => {
    useAdminAuthStore.getState().login('jwt-token', { id: 1, name: 'Admin Demo', email: 'admin@demo.local' })

    expect(useAdminAuthStore.getState().token).toBe('jwt-token')
    expect(useAdminAuthStore.getState().user).toEqual({ id: 1, name: 'Admin Demo', email: 'admin@demo.local' })
  })

  it('clears the token and user on logout', () => {
    useAdminAuthStore.getState().login('jwt-token', { id: 1, name: 'Admin Demo', email: 'admin@demo.local' })
    useAdminAuthStore.getState().logout()

    expect(useAdminAuthStore.getState().token).toBeNull()
    expect(useAdminAuthStore.getState().user).toBeNull()
  })

  it('persists the session under its own localStorage key', () => {
    useAdminAuthStore.getState().login('jwt-token', { id: 1, name: 'Admin Demo', email: 'admin@demo.local' })

    const stored = JSON.parse(localStorage.getItem('uvostore_admin_auth') ?? 'null')
    expect(stored?.state?.token).toBe('jwt-token')
  })
})
