import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { PublicStoreSettings } from '@/types/api'

vi.mock('@/services/api', () => ({
  default: {
    storeSettings: { get: vi.fn() },
    homeBanners: { getAll: vi.fn() },
    categories: { getAll: vi.fn() },
  },
}))

const settings = {
  storeName: 'Tienda de prueba',
  primaryColor: '#111111',
  secondaryColor: '#222222',
  accentColor: '#333333',
  darkColor: '#000000',
} as PublicStoreSettings

describe('useStoreSettingsStore', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    document.documentElement.style.removeProperty('--color-primary')
    const { useStoreSettingsStore } = await import('./useStoreSettingsStore')
    useStoreSettingsStore.setState({ settings: null, banners: [], categories: [], loading: false, loaded: false, error: null })
  })

  it('loads settings, banners and categories, and applies the theme colors', async () => {
    const api = (await import('@/services/api')).default
    vi.mocked(api.storeSettings.get).mockResolvedValue(settings)
    vi.mocked(api.homeBanners.getAll).mockResolvedValue([])
    vi.mocked(api.categories.getAll).mockResolvedValue([])
    const { useStoreSettingsStore } = await import('./useStoreSettingsStore')

    await useStoreSettingsStore.getState().fetchAll()

    expect(useStoreSettingsStore.getState().settings?.storeName).toBe('Tienda de prueba')
    expect(useStoreSettingsStore.getState().loaded).toBe(true)
    expect(document.documentElement.style.getPropertyValue('--color-primary')).toBe('#111111')
  })

  it('does not fetch again once already loaded', async () => {
    const api = (await import('@/services/api')).default
    vi.mocked(api.storeSettings.get).mockResolvedValue(settings)
    vi.mocked(api.homeBanners.getAll).mockResolvedValue([])
    vi.mocked(api.categories.getAll).mockResolvedValue([])
    const { useStoreSettingsStore } = await import('./useStoreSettingsStore')

    await useStoreSettingsStore.getState().fetchAll()
    await useStoreSettingsStore.getState().fetchAll()

    expect(api.storeSettings.get).toHaveBeenCalledTimes(1)
  })

  it('sets an error message when loading fails', async () => {
    const api = (await import('@/services/api')).default
    vi.mocked(api.storeSettings.get).mockRejectedValue({ message: 'No se pudo cargar la tienda' })
    vi.mocked(api.homeBanners.getAll).mockResolvedValue([])
    vi.mocked(api.categories.getAll).mockResolvedValue([])
    const { useStoreSettingsStore } = await import('./useStoreSettingsStore')

    await useStoreSettingsStore.getState().fetchAll()

    expect(useStoreSettingsStore.getState().error).toBe('No se pudo cargar la tienda')
    expect(useStoreSettingsStore.getState().loaded).toBe(false)
  })
})
