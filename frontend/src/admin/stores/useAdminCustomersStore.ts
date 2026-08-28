import { create } from 'zustand'
import type { Page } from '@/types/api'
import adminApi, { type CustomerListParams } from '@/admin/services/adminApi'
import type { AdminCustomerDetailDto, AdminCustomerSummaryDto, AdminOrderSummary } from '@/admin/types/admin'

interface AdminCustomersState {
  data: Page<AdminCustomerSummaryDto> | null
  loading: boolean
  detail: AdminCustomerDetailDto | null
  detailLoading: boolean
  orders: Page<AdminOrderSummary> | null
  ordersLoading: boolean
  fetchList: (params: CustomerListParams) => Promise<void>
  fetchDetail: (id: number) => Promise<void>
  fetchOrders: (customerId: number, page: number) => Promise<void>
  remove: (id: number) => Promise<void>
  refreshDetail: () => Promise<void>
}

export const useAdminCustomersStore = create<AdminCustomersState>((set, get) => ({
  data: null,
  loading: false,
  detail: null,
  detailLoading: false,
  orders: null,
  ordersLoading: false,
  fetchList: async (params) => {
    set({ loading: true })
    try {
      const data = await adminApi.customers.list(params)
      set({ data, loading: false })
    } catch (err) {
      set({ loading: false })
      throw err
    }
  },
  fetchDetail: async (id) => {
    set({ detailLoading: true })
    try {
      const detail = await adminApi.customers.getById(id)
      set({ detail, detailLoading: false })
    } catch (err) {
      set({ detailLoading: false })
      throw err
    }
  },
  fetchOrders: async (customerId, page) => {
    set({ ordersLoading: true })
    try {
      const orders = await adminApi.customers.getOrders(customerId, page)
      set({ orders, ordersLoading: false })
    } catch (err) {
      set({ ordersLoading: false })
      throw err
    }
  },
  remove: async (id) => {
    await adminApi.customers.delete(id)
    const data = get().data
    if (data) {
      set({ data: { ...data, content: data.content.filter((customer) => customer.id !== id) } })
    }
  },
  refreshDetail: async () => {
    const detail = get().detail
    if (!detail) return
    const refreshed = await adminApi.customers.getById(detail.id)
    set({ detail: refreshed })
  },
}))
