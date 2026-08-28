import { create } from 'zustand'
import type { Page } from '@/types/api'
import adminApi, { type CouponListParams } from '@/admin/services/adminApi'
import type { CouponDto } from '@/admin/types/admin'

interface AdminCouponsState {
  data: Page<CouponDto> | null
  loading: boolean
  fetchList: (params: CouponListParams) => Promise<void>
  remove: (id: number) => Promise<void>
  toggleStatus: (id: number) => Promise<void>
}

export const useAdminCouponsStore = create<AdminCouponsState>((set, get) => ({
  data: null,
  loading: false,
  fetchList: async (params) => {
    set({ loading: true })
    try {
      const data = await adminApi.coupons.list(params)
      set({ data, loading: false })
    } catch (err) {
      set({ loading: false })
      throw err
    }
  },
  remove: async (id) => {
    await adminApi.coupons.delete(id)
    const data = get().data
    if (data) {
      set({ data: { ...data, content: data.content.filter((coupon) => coupon.id !== id) } })
    }
  },
  toggleStatus: async (id) => {
    const updated = await adminApi.coupons.toggleStatus(id)
    const data = get().data
    if (data) {
      set({ data: { ...data, content: data.content.map((coupon) => (coupon.id === id ? updated : coupon)) } })
    }
  },
}))
