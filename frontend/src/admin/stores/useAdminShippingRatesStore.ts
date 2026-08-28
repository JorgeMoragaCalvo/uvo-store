import { create } from 'zustand'
import adminApi, { type ShippingRateListParams } from '@/admin/services/adminApi'
import type { ShippingRateDto } from '@/admin/types/admin'

interface AdminShippingRatesState {
  rates: ShippingRateDto[]
  loading: boolean
  fetch: (params: ShippingRateListParams) => Promise<void>
  remove: (id: number) => Promise<void>
  toggleStatus: (id: number) => Promise<void>
}

export const useAdminShippingRatesStore = create<AdminShippingRatesState>((set, get) => ({
  rates: [],
  loading: false,
  fetch: async (params) => {
    set({ loading: true })
    try {
      const rates = await adminApi.shippingRates.list(params)
      set({ rates, loading: false })
    } catch (err) {
      set({ loading: false })
      throw err
    }
  },
  remove: async (id) => {
    await adminApi.shippingRates.delete(id)
    set({ rates: get().rates.filter((rate) => rate.id !== id) })
  },
  toggleStatus: async (id) => {
    const updated = await adminApi.shippingRates.toggleStatus(id)
    set({ rates: get().rates.map((rate) => (rate.id === id ? updated : rate)) })
  },
}))
