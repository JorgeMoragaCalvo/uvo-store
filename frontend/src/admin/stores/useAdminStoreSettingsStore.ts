import { create } from 'zustand'
import adminApi from '@/admin/services/adminApi'
import type { StoreSettingsDto } from '@/admin/types/admin'

interface AdminStoreSettingsState {
  settings: StoreSettingsDto | null
  loading: boolean
  fetch: () => Promise<void>
  update: (formData: FormData) => Promise<void>
}

export const useAdminStoreSettingsStore = create<AdminStoreSettingsState>((set) => ({
  settings: null,
  loading: false,
  fetch: async () => {
    set({ loading: true })
    try {
      const settings = await adminApi.storeSettings.get()
      set({ settings, loading: false })
    } catch (err) {
      set({ loading: false })
      throw err
    }
  },
  update: async (formData) => {
    const updated = await adminApi.storeSettings.update(formData)
    set({ settings: updated })
  },
}))
