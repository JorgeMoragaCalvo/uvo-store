import { create } from 'zustand'
import adminApi from '@/admin/services/adminApi'
import type { HomeBannerDto } from '@/admin/types/admin'

interface AdminBannersState {
  banners: HomeBannerDto[]
  loading: boolean
  fetch: () => Promise<void>
  remove: (id: number) => Promise<void>
  toggle: (id: number) => Promise<void>
  reorder: (orderedIds: number[]) => Promise<void>
}

export const useAdminBannersStore = create<AdminBannersState>((set, get) => ({
  banners: [],
  loading: false,
  fetch: async () => {
    set({ loading: true })
    try {
      const banners = await adminApi.banners.list()
      set({ banners: [...banners].sort((a, b) => a.sortOrder - b.sortOrder), loading: false })
    } catch (err) {
      set({ loading: false })
      throw err
    }
  },
  remove: async (id) => {
    await adminApi.banners.delete(id)
    set({ banners: get().banners.filter((b) => b.id !== id) })
  },
  toggle: async (id) => {
    const updated = await adminApi.banners.toggle(id)
    set({ banners: get().banners.map((b) => (b.id === updated.id ? updated : b)) })
  },
  reorder: async (orderedIds) => {
    await adminApi.banners.reorder(orderedIds)
    const byId = new Map(get().banners.map((b) => [b.id, b]))
    set({ banners: orderedIds.map((id, index) => ({ ...byId.get(id)!, sortOrder: index + 1 })) })
  },
}))
