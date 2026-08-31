import { create } from 'zustand'
import adminApi from '@/admin/services/adminApi'
import type { GeneralSettingsDto, GeneralSettingsUpdateRequest } from '@/admin/types/admin'

interface AdminGeneralSettingsState {
  settings: GeneralSettingsDto | null
  loading: boolean
  fetch: () => Promise<void>
  update: (settings: GeneralSettingsUpdateRequest) => Promise<GeneralSettingsDto>
}

export const useAdminGeneralSettingsStore = create<AdminGeneralSettingsState>((set) => ({
  settings: null,
  loading: false,
  fetch: async () => {
    set({ loading: true })
    try {
      const settings = await adminApi.generalSettings.get()
      set({ settings, loading: false })
    } catch (err) {
      set({ loading: false })
      throw err
    }
  },
  update: async (settings) => {
    const updated = await adminApi.generalSettings.update(settings)
    set({ settings: updated })
    return updated
  },
}))
