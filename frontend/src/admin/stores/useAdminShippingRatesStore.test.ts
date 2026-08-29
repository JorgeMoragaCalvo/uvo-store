import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ShippingRateDto } from '@/admin/types/admin'

vi.mock('@/admin/services/adminApi', () => ({
  default: {
    shippingRates: {
      list: vi.fn(),
      delete: vi.fn(),
      toggleStatus: vi.fn(),
    },
  },
}))

function rate(overrides: Partial<ShippingRateDto> = {}): ShippingRateDto {
  return {
    id: 1,
    methodId: 1,
    methodName: 'Courier',
    zoneId: 1,
    zoneName: 'Región Metropolitana',
    name: 'Tarifa estándar',
    rateType: 'FLAT',
    flatRate: 3000,
    weightRatePerKg: null,
    baseWeightRate: null,
    minOrderAmount: null,
    maxOrderAmount: null,
    minWeight: null,
    maxWeight: null,
    freeShippingThreshold: null,
    active: true,
    sortOrder: 0,
    ...overrides,
  }
}

describe('useAdminShippingRatesStore', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    const { useAdminShippingRatesStore } = await import('./useAdminShippingRatesStore')
    useAdminShippingRatesStore.setState({ rates: [], loading: false })
  })

  it('loads rates filtered by method and zone', async () => {
    const adminApi = (await import('@/admin/services/adminApi')).default
    vi.mocked(adminApi.shippingRates.list).mockResolvedValue([rate()])
    const { useAdminShippingRatesStore } = await import('./useAdminShippingRatesStore')

    await useAdminShippingRatesStore.getState().fetch({ methodId: 1, zoneId: 2 })

    expect(adminApi.shippingRates.list).toHaveBeenCalledWith({ methodId: 1, zoneId: 2 })
    expect(useAdminShippingRatesStore.getState().rates).toHaveLength(1)
  })

  it('removes a rate from the list after deleting it', async () => {
    const adminApi = (await import('@/admin/services/adminApi')).default
    vi.mocked(adminApi.shippingRates.list).mockResolvedValue([rate({ id: 1 }), rate({ id: 2, name: 'Otra tarifa' })])
    vi.mocked(adminApi.shippingRates.delete).mockResolvedValue(undefined)
    const { useAdminShippingRatesStore } = await import('./useAdminShippingRatesStore')
    await useAdminShippingRatesStore.getState().fetch({})

    await useAdminShippingRatesStore.getState().remove(1)

    expect(useAdminShippingRatesStore.getState().rates.map((r) => r.id)).toEqual([2])
  })

  it('replaces a rate in place after toggling its status', async () => {
    const adminApi = (await import('@/admin/services/adminApi')).default
    vi.mocked(adminApi.shippingRates.list).mockResolvedValue([rate({ id: 1, active: true })])
    vi.mocked(adminApi.shippingRates.toggleStatus).mockResolvedValue(rate({ id: 1, active: false }))
    const { useAdminShippingRatesStore } = await import('./useAdminShippingRatesStore')
    await useAdminShippingRatesStore.getState().fetch({})

    await useAdminShippingRatesStore.getState().toggleStatus(1)

    expect(useAdminShippingRatesStore.getState().rates[0].active).toBe(false)
  })

  it('resets loading and rethrows when the list request fails', async () => {
    const adminApi = (await import('@/admin/services/adminApi')).default
    vi.mocked(adminApi.shippingRates.list).mockRejectedValue(new Error('network error'))
    const { useAdminShippingRatesStore } = await import('./useAdminShippingRatesStore')

    await expect(useAdminShippingRatesStore.getState().fetch({})).rejects.toThrow('network error')
    expect(useAdminShippingRatesStore.getState().loading).toBe(false)
  })
})
