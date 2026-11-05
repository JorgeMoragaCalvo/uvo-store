import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Page } from '@/types/api'
import type { OutboxFailure } from '@/admin/types/admin'

vi.mock('@/admin/services/adminApi', () => ({
  default: {
    outbox: { failed: vi.fn(), retry: vi.fn(), resolve: vi.fn() },
  },
}))

function failure(id: number): OutboxFailure {
  return {
    id,
    type: 'ORDER_CONFIRMATION_EMAIL',
    channel: 'MAIL',
    orderId: 10,
    orderNumber: 'ORD-10',
    companyId: null,
    attempts: 1,
    lastError: 'Rechazado: dirección inválida',
    createdAt: '2026-11-01T00:00:00Z',
    updatedAt: null,
    uncertain: false,
    legacy: false,
  }
}

function page(content: OutboxFailure[]): Page<OutboxFailure> {
  return { content, totalElements: content.length, totalPages: 1, number: 0, size: 20 }
}

describe('useAdminOutboxStore', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    const { useAdminOutboxStore } = await import('./useAdminOutboxStore')
    useAdminOutboxStore.setState({ data: null, loading: false })
  })

  it('loads a page of failed deliveries', async () => {
    const adminApi = (await import('@/admin/services/adminApi')).default
    vi.mocked(adminApi.outbox.failed).mockResolvedValue(page([failure(1)]))
    const { useAdminOutboxStore } = await import('./useAdminOutboxStore')

    await useAdminOutboxStore.getState().fetchFailed(2)

    expect(adminApi.outbox.failed).toHaveBeenCalledWith(2)
    expect(useAdminOutboxStore.getState().data?.content).toHaveLength(1)
    expect(useAdminOutboxStore.getState().loading).toBe(false)
  })

  it('drops a row once it is retried or resolved', async () => {
    const adminApi = (await import('@/admin/services/adminApi')).default
    vi.mocked(adminApi.outbox.failed).mockResolvedValue(page([failure(1), failure(2), failure(3)]))
    vi.mocked(adminApi.outbox.retry).mockResolvedValue(undefined)
    vi.mocked(adminApi.outbox.resolve).mockResolvedValue(undefined)
    const { useAdminOutboxStore } = await import('./useAdminOutboxStore')
    await useAdminOutboxStore.getState().fetchFailed(1)

    await useAdminOutboxStore.getState().retry(1, 'nota', false)
    await useAdminOutboxStore.getState().resolve(3, 'nota')

    expect(adminApi.outbox.retry).toHaveBeenCalledWith(1, 'nota', false)
    expect(adminApi.outbox.resolve).toHaveBeenCalledWith(3, 'nota')
    expect(useAdminOutboxStore.getState().data?.content.map((f) => f.id)).toEqual([2])
    expect(useAdminOutboxStore.getState().data?.totalElements).toBe(1)
  })

  it('keeps the row when the server rejects the action', async () => {
    const adminApi = (await import('@/admin/services/adminApi')).default
    vi.mocked(adminApi.outbox.failed).mockResolvedValue(page([failure(1)]))
    vi.mocked(adminApi.outbox.retry).mockRejectedValue({ message: 'Este envío ya no está fallido' })
    const { useAdminOutboxStore } = await import('./useAdminOutboxStore')
    await useAdminOutboxStore.getState().fetchFailed(1)

    await expect(useAdminOutboxStore.getState().retry(1, 'nota', false)).rejects.toEqual({
      message: 'Este envío ya no está fallido',
    })
    expect(useAdminOutboxStore.getState().data?.content).toHaveLength(1)
  })
})
