import { create } from 'zustand'
import adminApi from '@/admin/services/adminApi'
import type { CategoryDto } from '@/admin/types/admin'

interface AdminCategoriesState {
  categories: CategoryDto[]
  loading: boolean
  fetch: () => Promise<void>
  remove: (id: number) => Promise<void>
}

export const useAdminCategoriesStore = create<AdminCategoriesState>((set, get) => ({
  categories: [],
  loading: false,
  fetch: async () => {
    set({ loading: true })
    try {
      const categories = await adminApi.categories.list()
      set({ categories, loading: false })
    } catch (err) {
      set({ loading: false })
      throw err
    }
  },
  remove: async (id) => {
    await adminApi.categories.delete(id)
    set({ categories: get().categories.filter((c) => c.id !== id) })
  },
}))
