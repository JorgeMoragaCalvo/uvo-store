import { create } from 'zustand'
import type { Page } from '@/types/api'
import adminApi, { type UserListParams } from '@/admin/services/adminApi'
import type { AdminUserDto } from '@/admin/types/admin'

interface AdminUsersState {
  data: Page<AdminUserDto> | null
  loading: boolean
  fetchList: (params: UserListParams) => Promise<void>
  remove: (id: number) => Promise<void>
  toggleStatus: (id: number) => Promise<void>
  resendInvitation: (id: number) => Promise<void>
}

export const useAdminUsersStore = create<AdminUsersState>((set, get) => ({
  data: null,
  loading: false,
  fetchList: async (params) => {
    set({ loading: true })
    try {
      const data = await adminApi.users.list(params)
      set({ data, loading: false })
    } catch (err) {
      set({ loading: false })
      throw err
    }
  },
  remove: async (id) => {
    await adminApi.users.delete(id)
    const data = get().data
    if (data) {
      set({ data: { ...data, content: data.content.filter((user) => user.id !== id) } })
    }
  },
  toggleStatus: async (id) => {
    const updated = await adminApi.users.toggleStatus(id)
    const data = get().data
    if (data) {
      set({ data: { ...data, content: data.content.map((user) => (user.id === id ? updated : user)) } })
    }
  },
  // PROD-06: se guarda la fila que devuelve el servidor porque trae el `invitationSentAt` nuevo, y de él
  // depende cuándo se puede volver a reenviar.
  resendInvitation: async (id) => {
    const updated = await adminApi.users.resendInvitation(id)
    const data = get().data
    if (data) {
      set({ data: { ...data, content: data.content.map((user) => (user.id === id ? updated : user)) } })
    }
  },
}))
