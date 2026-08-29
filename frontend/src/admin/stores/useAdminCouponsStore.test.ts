import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Page } from '@/types/api'
import type { CouponDto } from '@/admin/types/admin'

vi.mock('@/admin/services/adminApi', () => ({
  default: {
    coupons: {
      list: vi.fn(),
      delete: vi.fn(),
      toggleStatus: vi.fn(),
    },
  },
}))

function coupon(overrides: Partial<CouponDto> = {}): CouponDto {
  return {
    id: 1,
    code: 'DESCUENTO10',
    name: '10% de descuento',
    description: null,
    type: 'percentage',
    value: 10,
    minimumPurchase: null,
    maximumDiscount: null,
    startsAt: null,
    expiresAt: null,
    usageLimit: null,
    usageLimitPerCustomer: null,
    timesUsed: 0,
    active: true,
    createdAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

function page(content: CouponDto[]): Page<CouponDto> {
  return { content, totalElements: content.length, totalPages: 1, number: 0, size: 15 }
}

describe('useAdminCouponsStore', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    const { useAdminCouponsStore } = await import('./useAdminCouponsStore')
    useAdminCouponsStore.setState({ data: null, loading: false })
  })

  it('loads a page of coupons', async () => {
    const adminApi = (await import('@/admin/services/adminApi')).default
    vi.mocked(adminApi.coupons.list).mockResolvedValue(page([coupon()]))
    const { useAdminCouponsStore } = await import('./useAdminCouponsStore')

    await useAdminCouponsStore.getState().fetchList({ page: 1 })

    expect(useAdminCouponsStore.getState().data?.content).toHaveLength(1)
    expect(useAdminCouponsStore.getState().loading).toBe(false)
  })

  it('resets loading and rethrows when the list request fails', async () => {
    const adminApi = (await import('@/admin/services/adminApi')).default
    vi.mocked(adminApi.coupons.list).mockRejectedValue(new Error('network error'))
    const { useAdminCouponsStore } = await import('./useAdminCouponsStore')

    await expect(useAdminCouponsStore.getState().fetchList({ page: 1 })).rejects.toThrow('network error')
    expect(useAdminCouponsStore.getState().loading).toBe(false)
  })

  it('removes a coupon from the current page after deleting it', async () => {
    const adminApi = (await import('@/admin/services/adminApi')).default
    vi.mocked(adminApi.coupons.list).mockResolvedValue(page([coupon({ id: 1 }), coupon({ id: 2, code: 'OTRO' })]))
    vi.mocked(adminApi.coupons.delete).mockResolvedValue(undefined)
    const { useAdminCouponsStore } = await import('./useAdminCouponsStore')
    await useAdminCouponsStore.getState().fetchList({ page: 1 })

    await useAdminCouponsStore.getState().remove(1)

    expect(useAdminCouponsStore.getState().data?.content.map((c) => c.id)).toEqual([2])
  })

  it('replaces a coupon in place after toggling its status', async () => {
    const adminApi = (await import('@/admin/services/adminApi')).default
    vi.mocked(adminApi.coupons.list).mockResolvedValue(page([coupon({ id: 1, active: true })]))
    vi.mocked(adminApi.coupons.toggleStatus).mockResolvedValue(coupon({ id: 1, active: false }))
    const { useAdminCouponsStore } = await import('./useAdminCouponsStore')
    await useAdminCouponsStore.getState().fetchList({ page: 1 })

    await useAdminCouponsStore.getState().toggleStatus(1)

    expect(useAdminCouponsStore.getState().data?.content[0].active).toBe(false)
  })
})
