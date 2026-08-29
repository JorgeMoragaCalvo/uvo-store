import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { RoleDto } from '@/admin/types/admin'

vi.mock('@/admin/services/adminApi', () => ({
  default: {
    roles: {
      list: vi.fn(),
      delete: vi.fn(),
    },
  },
}))

function role(overrides: Partial<RoleDto> = {}): RoleDto {
  return {
    id: 1,
    name: 'Editor',
    guardName: 'web',
    permissions: [],
    ...overrides,
  }
}

describe('useAdminRolesStore', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    const { useAdminRolesStore } = await import('./useAdminRolesStore')
    useAdminRolesStore.setState({ roles: [], loading: false })
  })

  it('loads all roles', async () => {
    const adminApi = (await import('@/admin/services/adminApi')).default
    vi.mocked(adminApi.roles.list).mockResolvedValue([role()])
    const { useAdminRolesStore } = await import('./useAdminRolesStore')

    await useAdminRolesStore.getState().fetch()

    expect(useAdminRolesStore.getState().roles).toHaveLength(1)
    expect(useAdminRolesStore.getState().loading).toBe(false)
  })

  it('removes a role from the list after deleting it', async () => {
    const adminApi = (await import('@/admin/services/adminApi')).default
    vi.mocked(adminApi.roles.list).mockResolvedValue([role({ id: 1 }), role({ id: 2, name: 'Soporte' })])
    vi.mocked(adminApi.roles.delete).mockResolvedValue(undefined)
    const { useAdminRolesStore } = await import('./useAdminRolesStore')
    await useAdminRolesStore.getState().fetch()

    await useAdminRolesStore.getState().remove(1)

    expect(useAdminRolesStore.getState().roles.map((r) => r.id)).toEqual([2])
  })

  it('resets loading and rethrows when the list request fails', async () => {
    const adminApi = (await import('@/admin/services/adminApi')).default
    vi.mocked(adminApi.roles.list).mockRejectedValue(new Error('network error'))
    const { useAdminRolesStore } = await import('./useAdminRolesStore')

    await expect(useAdminRolesStore.getState().fetch()).rejects.toThrow('network error')
    expect(useAdminRolesStore.getState().loading).toBe(false)
  })
})
