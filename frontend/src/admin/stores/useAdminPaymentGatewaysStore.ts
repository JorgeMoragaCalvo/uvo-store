import { create } from 'zustand'
import adminApi from '@/admin/services/adminApi'
import type { PaymentGateway, PaymentGatewayConfigDto } from '@/admin/types/admin'

interface AdminPaymentGatewaysState {
  configs: PaymentGatewayConfigDto[]
  loading: boolean
  fetch: () => Promise<void>
  update: (gateway: PaymentGateway, enabled: boolean, credentials: Record<string, string>) => Promise<void>
}

export const useAdminPaymentGatewaysStore = create<AdminPaymentGatewaysState>((set, get) => ({
  configs: [],
  loading: false,
  fetch: async () => {
    set({ loading: true })
    try {
      const configs = await adminApi.paymentGateways.list()
      set({ configs, loading: false })
    } catch (err) {
      set({ loading: false })
      throw err
    }
  },
  update: async (gateway, enabled, credentials) => {
    const updated = await adminApi.paymentGateways.update(gateway, enabled, credentials)
    const existing = get().configs
    const withoutGateway = existing.filter((c) => c.gateway !== gateway)
    set({ configs: [...withoutGateway, updated] })
  },
}))
