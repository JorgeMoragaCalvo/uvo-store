import { create } from 'zustand'
import adminApi from '@/admin/services/adminApi'
import type { RoleDto } from '@/admin/types/admin'

interface AdminRolesState {
  roles: RoleDto[]
  loading: boolean
  fetch: () => Promise<void>
  remove: (id: number) => Promise<void>
}

export const useAdminRolesStore = create<AdminRolesState>((set, get) => ({
  roles: [],
  loading: false,
  fetch: async () => {
    set({ loading: true })
    try {
      const roles = await adminApi.roles.list()
      set({ roles, loading: false })
    } catch (err) {
      set({ loading: false })
      throw err
    }
  },
  remove: async (id) => {
    await adminApi.roles.delete(id)
    set({ roles: get().roles.filter((role) => role.id !== id) })
  },
}))
