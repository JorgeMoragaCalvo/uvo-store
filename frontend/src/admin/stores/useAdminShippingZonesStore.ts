import { create } from 'zustand'
import adminApi from '@/admin/services/adminApi'
import type { ShippingZoneDto } from '@/admin/types/admin'

interface AdminShippingZonesState {
  zones: ShippingZoneDto[]
  loading: boolean
  fetch: (search?: string) => Promise<void>
  remove: (id: number) => Promise<void>
  toggleStatus: (id: number) => Promise<void>
}

export const useAdminShippingZonesStore = create<AdminShippingZonesState>((set, get) => ({
  zones: [],
  loading: false,
  fetch: async (search) => {
    set({ loading: true })
    try {
      const zones = await adminApi.shippingZones.list(search)
      set({ zones, loading: false })
    } catch (err) {
      set({ loading: false })
      throw err
    }
  },
  remove: async (id) => {
    await adminApi.shippingZones.delete(id)
    set({ zones: get().zones.filter((zone) => zone.id !== id) })
  },
  toggleStatus: async (id) => {
    const updated = await adminApi.shippingZones.toggleStatus(id)
    set({ zones: get().zones.map((zone) => (zone.id === id ? updated : zone)) })
  },
}))
