import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ShippingZoneDto } from '@/admin/types/admin'

vi.mock('@/admin/services/adminApi', () => ({
  default: {
    shippingZones: {
      list: vi.fn(),
      delete: vi.fn(),
      toggleStatus: vi.fn(),
    },
  },
}))

function zone(overrides: Partial<ShippingZoneDto> = {}): ShippingZoneDto {
  return {
    id: 1,
    name: 'Región Metropolitana',
    description: null,
    regions: ['Región Metropolitana'],
    communes: [],
    active: true,
    sortOrder: 0,
    ...overrides,
  }
}

describe('useAdminShippingZonesStore', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    const { useAdminShippingZonesStore } = await import('./useAdminShippingZonesStore')
    useAdminShippingZonesStore.setState({ zones: [], loading: false })
  })

  it('loads zones, optionally filtered by search', async () => {
    const adminApi = (await import('@/admin/services/adminApi')).default
    vi.mocked(adminApi.shippingZones.list).mockResolvedValue([zone()])
    const { useAdminShippingZonesStore } = await import('./useAdminShippingZonesStore')

    await useAdminShippingZonesStore.getState().fetch('metropolitana')

    expect(adminApi.shippingZones.list).toHaveBeenCalledWith('metropolitana')
    expect(useAdminShippingZonesStore.getState().zones).toHaveLength(1)
  })

  it('removes a zone from the list after deleting it', async () => {
    const adminApi = (await import('@/admin/services/adminApi')).default
    vi.mocked(adminApi.shippingZones.list).mockResolvedValue([zone({ id: 1 }), zone({ id: 2, name: 'Valparaíso' })])
    vi.mocked(adminApi.shippingZones.delete).mockResolvedValue(undefined)
    const { useAdminShippingZonesStore } = await import('./useAdminShippingZonesStore')
    await useAdminShippingZonesStore.getState().fetch()

    await useAdminShippingZonesStore.getState().remove(1)

    expect(useAdminShippingZonesStore.getState().zones.map((z) => z.id)).toEqual([2])
  })

  it('replaces a zone in place after toggling its status', async () => {
    const adminApi = (await import('@/admin/services/adminApi')).default
    vi.mocked(adminApi.shippingZones.list).mockResolvedValue([zone({ id: 1, active: true })])
    vi.mocked(adminApi.shippingZones.toggleStatus).mockResolvedValue(zone({ id: 1, active: false }))
    const { useAdminShippingZonesStore } = await import('./useAdminShippingZonesStore')
    await useAdminShippingZonesStore.getState().fetch()

    await useAdminShippingZonesStore.getState().toggleStatus(1)

    expect(useAdminShippingZonesStore.getState().zones[0].active).toBe(false)
  })

  it('resets loading and rethrows when the list request fails', async () => {
    const adminApi = (await import('@/admin/services/adminApi')).default
    vi.mocked(adminApi.shippingZones.list).mockRejectedValue(new Error('network error'))
    const { useAdminShippingZonesStore } = await import('./useAdminShippingZonesStore')

    await expect(useAdminShippingZonesStore.getState().fetch()).rejects.toThrow('network error')
    expect(useAdminShippingZonesStore.getState().loading).toBe(false)
  })
})
