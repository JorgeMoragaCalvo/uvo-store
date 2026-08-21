import { create } from 'zustand'
import api from '../services/api'
import type { Category, HomeBanner, PublicStoreSettings } from '../types/api'

interface StoreSettingsState {
  settings: PublicStoreSettings | null
  banners: HomeBanner[]
  categories: Category[]
  loading: boolean
  loaded: boolean
  error: string | null
  fetchAll: () => Promise<void>
}

function applyTheme(settings: PublicStoreSettings) {
  const root = document.documentElement.style
  root.setProperty('--color-primary', settings.primaryColor)
  root.setProperty('--color-secondary', settings.secondaryColor)
  root.setProperty('--color-accent', settings.accentColor)
  root.setProperty('--color-dark', settings.darkColor)
}

export const useStoreSettingsStore = create<StoreSettingsState>((set, get) => ({
  settings: null,
  banners: [],
  categories: [],
  loading: false,
  loaded: false,
  error: null,

  async fetchAll() {
    if (get().loading || get().loaded) return
    set({ loading: true, error: null })

    try {
      const [settings, banners, categories] = await Promise.all([
        api.storeSettings.get(),
        api.homeBanners.getAll(),
        api.categories.getAll(),
      ])
      applyTheme(settings)
      set({ settings, banners, categories, loading: false, loaded: true })
    } catch (error) {
      set({ loading: false, error: (error as { message?: string }).message ?? 'Error loading store settings' })
    }
  },
}))
