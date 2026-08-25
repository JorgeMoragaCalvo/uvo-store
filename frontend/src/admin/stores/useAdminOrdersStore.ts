import { create } from 'zustand'
import type { Page } from '@/types/api'
import adminApi, { type OrderListParams } from '@/admin/services/adminApi'
import type { AdminOrderDetail, AdminOrderSummary } from '@/admin/types/admin'

interface AdminOrdersState {
  data: Page<AdminOrderSummary> | null
  loading: boolean
  detail: AdminOrderDetail | null
  detailLoading: boolean
  fetchList: (params: OrderListParams) => Promise<void>
  fetchDetail: (id: number) => Promise<void>
  applyDetailUpdate: (action: () => Promise<AdminOrderDetail>) => Promise<void>
}

export const useAdminOrdersStore = create<AdminOrdersState>((set) => ({
  data: null,
  loading: false,
  detail: null,
  detailLoading: false,
  fetchList: async (params) => {
    set({ loading: true })
    try {
      const data = await adminApi.orders.list(params)
      set({ data, loading: false })
    } catch (err) {
      set({ loading: false })
      throw err
    }
  },
  fetchDetail: async (id) => {
    set({ detailLoading: true })
    try {
      const detail = await adminApi.orders.getById(id)
      set({ detail, detailLoading: false })
    } catch (err) {
      set({ detailLoading: false })
      throw err
    }
  },
  applyDetailUpdate: async (action) => {
    const detail = await action()
    set({ detail })
  },
}))
