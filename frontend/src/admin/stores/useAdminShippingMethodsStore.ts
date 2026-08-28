import { create } from 'zustand'
import adminApi from '@/admin/services/adminApi'
import type { ShippingMethodDto } from '@/admin/types/admin'

interface AdminShippingMethodsState {
  methods: ShippingMethodDto[]
  loading: boolean
  fetch: () => Promise<void>
  remove: (id: number) => Promise<void>
  toggleStatus: (id: number) => Promise<void>
}

export const useAdminShippingMethodsStore = create<AdminShippingMethodsState>((set, get) => ({
  methods: [],
  loading: false,
  fetch: async () => {
    set({ loading: true })
    try {
      const methods = await adminApi.shippingMethods.list()
      set({ methods, loading: false })
    } catch (err) {
      set({ loading: false })
      throw err
    }
  },
  remove: async (id) => {
    await adminApi.shippingMethods.delete(id)
    set({ methods: get().methods.filter((method) => method.id !== id) })
  },
  toggleStatus: async (id) => {
    const updated = await adminApi.shippingMethods.toggleStatus(id)
    set({ methods: get().methods.map((method) => (method.id === id ? updated : method)) })
  },
}))
