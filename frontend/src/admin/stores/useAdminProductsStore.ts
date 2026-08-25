import { create } from 'zustand'
import type { Page } from '@/types/api'
import adminApi, { type ProductListParams } from '@/admin/services/adminApi'
import type { ProductDto } from '@/admin/types/admin'

interface AdminProductsState {
  data: Page<ProductDto> | null
  loading: boolean
  fetch: (params: ProductListParams) => Promise<void>
  toggleActive: (id: number) => Promise<void>
  remove: (id: number) => Promise<void>
}

export const useAdminProductsStore = create<AdminProductsState>((set, get) => ({
  data: null,
  loading: false,
  fetch: async (params) => {
    set({ loading: true })
    try {
      const data = await adminApi.products.list(params)
      set({ data, loading: false })
    } catch (err) {
      set({ loading: false })
      throw err
    }
  },
  toggleActive: async (id) => {
    const updated = await adminApi.products.toggleActive(id)
    const data = get().data
    if (data) {
      set({ data: { ...data, content: data.content.map((p) => (p.id === updated.id ? updated : p)) } })
    }
  },
  remove: async (id) => {
    await adminApi.products.delete(id)
    const data = get().data
    if (data) {
      set({ data: { ...data, content: data.content.filter((p) => p.id !== id) } })
    }
  },
}))
