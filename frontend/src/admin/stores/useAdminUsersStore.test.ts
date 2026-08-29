import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Page } from '@/types/api'
import type { AdminUserDto } from '@/admin/types/admin'

vi.mock('@/admin/services/adminApi', () => ({
  default: {
    users: {
      list: vi.fn(),
      delete: vi.fn(),
      toggleStatus: vi.fn(),
    },
  },
}))

function user(overrides: Partial<AdminUserDto> = {}): AdminUserDto {
  return {
    id: 1,
    name: 'Admin Demo',
    email: 'admin@demo.local',
    phone: null,
    avatar: null,
    active: true,
    lastLoginAt: null,
    notes: null,
    roles: [],
    createdAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

function page(content: AdminUserDto[]): Page<AdminUserDto> {
  return { content, totalElements: content.length, totalPages: 1, number: 0, size: 15 }
}

describe('useAdminUsersStore', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    const { useAdminUsersStore } = await import('./useAdminUsersStore')
    useAdminUsersStore.setState({ data: null, loading: false })
  })

  it('loads a page of users', async () => {
    const adminApi = (await import('@/admin/services/adminApi')).default
    vi.mocked(adminApi.users.list).mockResolvedValue(page([user()]))
    const { useAdminUsersStore } = await import('./useAdminUsersStore')

    await useAdminUsersStore.getState().fetchList({ page: 1 })

    expect(useAdminUsersStore.getState().data?.content).toHaveLength(1)
    expect(useAdminUsersStore.getState().loading).toBe(false)
  })

  it('resets loading and rethrows when the list request fails', async () => {
    const adminApi = (await import('@/admin/services/adminApi')).default
    vi.mocked(adminApi.users.list).mockRejectedValue(new Error('403 self-action not allowed'))
    const { useAdminUsersStore } = await import('./useAdminUsersStore')

    await expect(useAdminUsersStore.getState().fetchList({ page: 1 })).rejects.toThrow('403 self-action not allowed')
    expect(useAdminUsersStore.getState().loading).toBe(false)
  })

  it('removes a user from the current page after deleting it', async () => {
    const adminApi = (await import('@/admin/services/adminApi')).default
    vi.mocked(adminApi.users.list).mockResolvedValue(page([user({ id: 1 }), user({ id: 2, email: 'otro@demo.local' })]))
    vi.mocked(adminApi.users.delete).mockResolvedValue(undefined)
    const { useAdminUsersStore } = await import('./useAdminUsersStore')
    await useAdminUsersStore.getState().fetchList({ page: 1 })

    await useAdminUsersStore.getState().remove(1)

    expect(useAdminUsersStore.getState().data?.content.map((u) => u.id)).toEqual([2])
  })

  it('replaces a user in place after toggling its status', async () => {
    const adminApi = (await import('@/admin/services/adminApi')).default
    vi.mocked(adminApi.users.list).mockResolvedValue(page([user({ id: 1, active: true })]))
    vi.mocked(adminApi.users.toggleStatus).mockResolvedValue(user({ id: 1, active: false }))
    const { useAdminUsersStore } = await import('./useAdminUsersStore')
    await useAdminUsersStore.getState().fetchList({ page: 1 })

    await useAdminUsersStore.getState().toggleStatus(1)

    expect(useAdminUsersStore.getState().data?.content[0].active).toBe(false)
  })
})
