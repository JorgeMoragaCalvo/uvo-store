import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ShippingMethodDto } from '@/admin/types/admin'

vi.mock('@/admin/services/adminApi', () => ({
  default: {
    shippingMethods: {
      list: vi.fn(),
      delete: vi.fn(),
      toggleStatus: vi.fn(),
    },
  },
}))

function method(overrides: Partial<ShippingMethodDto> = {}): ShippingMethodDto {
  return {
    id: 1,
    name: 'Courier',
    code: 'courier',
    description: null,
    type: 'COURIER',
    hasApiIntegration: false,
    carrier: null,
    credentialsSet: {},
    minDeliveryDays: 1,
    maxDeliveryDays: 3,
    active: true,
    sortOrder: 0,
    ratesCount: 0,
    ...overrides,
  }
}

describe('useAdminShippingMethodsStore', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    const { useAdminShippingMethodsStore } = await import('./useAdminShippingMethodsStore')
    useAdminShippingMethodsStore.setState({ methods: [], loading: false })
  })

  it('loads all methods', async () => {
    const adminApi = (await import('@/admin/services/adminApi')).default
    vi.mocked(adminApi.shippingMethods.list).mockResolvedValue([method()])
    const { useAdminShippingMethodsStore } = await import('./useAdminShippingMethodsStore')

    await useAdminShippingMethodsStore.getState().fetch()

    expect(useAdminShippingMethodsStore.getState().methods).toHaveLength(1)
  })

  it('removes a method from the list after deleting it', async () => {
    const adminApi = (await import('@/admin/services/adminApi')).default
    vi.mocked(adminApi.shippingMethods.list).mockResolvedValue([method({ id: 1 }), method({ id: 2, code: 'pickup' })])
    vi.mocked(adminApi.shippingMethods.delete).mockResolvedValue(undefined)
    const { useAdminShippingMethodsStore } = await import('./useAdminShippingMethodsStore')
    await useAdminShippingMethodsStore.getState().fetch()

    await useAdminShippingMethodsStore.getState().remove(1)

    expect(useAdminShippingMethodsStore.getState().methods.map((m) => m.id)).toEqual([2])
  })

  it('replaces a method in place after toggling its status', async () => {
    const adminApi = (await import('@/admin/services/adminApi')).default
    vi.mocked(adminApi.shippingMethods.list).mockResolvedValue([method({ id: 1, active: true })])
    vi.mocked(adminApi.shippingMethods.toggleStatus).mockResolvedValue(method({ id: 1, active: false }))
    const { useAdminShippingMethodsStore } = await import('./useAdminShippingMethodsStore')
    await useAdminShippingMethodsStore.getState().fetch()

    await useAdminShippingMethodsStore.getState().toggleStatus(1)

    expect(useAdminShippingMethodsStore.getState().methods[0].active).toBe(false)
  })
})
