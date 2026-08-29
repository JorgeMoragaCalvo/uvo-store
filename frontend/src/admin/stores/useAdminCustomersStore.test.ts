import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Page } from '@/types/api'
import type { AdminCustomerDetailDto, AdminCustomerSummaryDto, AdminOrderSummary } from '@/admin/types/admin'

vi.mock('@/admin/services/adminApi', () => ({
  default: {
    customers: {
      list: vi.fn(),
      getById: vi.fn(),
      getOrders: vi.fn(),
      delete: vi.fn(),
    },
  },
}))

function summary(overrides: Partial<AdminCustomerSummaryDto> = {}): AdminCustomerSummaryDto {
  return {
    id: 1,
    email: 'cliente@test.local',
    firstName: 'Juan',
    lastName: 'Pérez',
    phone: null,
    accountStatus: 'ACTIVE',
    ordersCount: 2,
    createdAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

function detail(overrides: Partial<AdminCustomerDetailDto> = {}): AdminCustomerDetailDto {
  return {
    id: 1,
    email: 'cliente@test.local',
    firstName: 'Juan',
    lastName: 'Pérez',
    phone: null,
    accountStatus: 'ACTIVE',
    addresses: [],
    stats: { totalOrders: 2, totalSpent: 20000, averageOrder: 10000, completedOrders: 1 },
    createdAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

function page<T>(content: T[]): Page<T> {
  return { content, totalElements: content.length, totalPages: 1, number: 0, size: 20 }
}

describe('useAdminCustomersStore', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    const { useAdminCustomersStore } = await import('./useAdminCustomersStore')
    useAdminCustomersStore.setState({ data: null, loading: false, detail: null, detailLoading: false, orders: null, ordersLoading: false })
  })

  it('loads a page of customers', async () => {
    const adminApi = (await import('@/admin/services/adminApi')).default
    vi.mocked(adminApi.customers.list).mockResolvedValue(page([summary()]))
    const { useAdminCustomersStore } = await import('./useAdminCustomersStore')

    await useAdminCustomersStore.getState().fetchList({ page: 1 })

    expect(useAdminCustomersStore.getState().data?.content).toHaveLength(1)
  })

  it('loads a customer detail', async () => {
    const adminApi = (await import('@/admin/services/adminApi')).default
    vi.mocked(adminApi.customers.getById).mockResolvedValue(detail())
    const { useAdminCustomersStore } = await import('./useAdminCustomersStore')

    await useAdminCustomersStore.getState().fetchDetail(1)

    expect(useAdminCustomersStore.getState().detail?.email).toBe('cliente@test.local')
  })

  it('loads a customer order history page', async () => {
    const adminApi = (await import('@/admin/services/adminApi')).default
    const order = { id: 1, orderNumber: 'ORD-1' } as AdminOrderSummary
    vi.mocked(adminApi.customers.getOrders).mockResolvedValue(page([order]))
    const { useAdminCustomersStore } = await import('./useAdminCustomersStore')

    await useAdminCustomersStore.getState().fetchOrders(1, 1)

    expect(useAdminCustomersStore.getState().orders?.content).toHaveLength(1)
  })

  it('removes a customer from the current page after deleting it', async () => {
    const adminApi = (await import('@/admin/services/adminApi')).default
    vi.mocked(adminApi.customers.list).mockResolvedValue(page([summary({ id: 1 }), summary({ id: 2 })]))
    vi.mocked(adminApi.customers.delete).mockResolvedValue(undefined)
    const { useAdminCustomersStore } = await import('./useAdminCustomersStore')
    await useAdminCustomersStore.getState().fetchList({ page: 1 })

    await useAdminCustomersStore.getState().remove(1)

    expect(useAdminCustomersStore.getState().data?.content.map((c) => c.id)).toEqual([2])
  })

  it('re-fetches the detail in place when refreshing', async () => {
    const adminApi = (await import('@/admin/services/adminApi')).default
    vi.mocked(adminApi.customers.getById).mockResolvedValueOnce(detail()).mockResolvedValueOnce(detail({ phone: '+56911111111' }))
    const { useAdminCustomersStore } = await import('./useAdminCustomersStore')
    await useAdminCustomersStore.getState().fetchDetail(1)

    await useAdminCustomersStore.getState().refreshDetail()

    expect(useAdminCustomersStore.getState().detail?.phone).toBe('+56911111111')
  })

  it('does nothing when refreshing without a loaded detail', async () => {
    const adminApi = (await import('@/admin/services/adminApi')).default
    const { useAdminCustomersStore } = await import('./useAdminCustomersStore')

    await useAdminCustomersStore.getState().refreshDetail()

    expect(adminApi.customers.getById).not.toHaveBeenCalled()
  })
})
