import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { toast } from 'sonner'
import OutboxFailures from './OutboxFailures'
import adminApi from '@/admin/services/adminApi'
import { useAdminAuthStore } from '@/admin/stores/useAdminAuthStore'
import { useAdminOutboxStore } from '@/admin/stores/useAdminOutboxStore'
import type { OutboxFailure } from '@/admin/types/admin'
import type { Page } from '@/types/api'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

vi.mock('@/admin/services/adminApi', () => ({
  default: {
    outbox: { failed: vi.fn(), retry: vi.fn(), resolve: vi.fn() },
  },
}))

function failure(overrides: Partial<OutboxFailure> = {}): OutboxFailure {
  return {
    id: 1,
    type: 'POS_ORDER',
    channel: 'POS',
    orderId: 10,
    orderNumber: 'ORD-10',
    companyId: 77,
    attempts: 1,
    lastError: 'Resultado incierto, la venta pudo haber llegado al POS: Read timed out',
    createdAt: '2026-11-01T00:00:00Z',
    updatedAt: '2026-11-01T00:05:00Z',
    uncertain: true,
    legacy: false,
    ...overrides,
  }
}

function page(content: OutboxFailure[]): Page<OutboxFailure> {
  return { content, totalElements: content.length, totalPages: 1, number: 0, size: 20 }
}

function signIn(permissions: string[]) {
  useAdminAuthStore.setState({
    token: 'jwt',
    user: { id: 1, name: 'Admin', email: 'admin@test.local', permissions },
  })
}

async function renderPage(failures: OutboxFailure[]) {
  vi.mocked(adminApi.outbox.failed).mockResolvedValue(page(failures))
  render(
    <MemoryRouter>
      <OutboxFailures />
    </MemoryRouter>,
  )
  await screen.findAllByText(failures[0]?.orderNumber ?? 'No hay envíos fallidos.')
}

/**
 * GAP-04. Las entregas del outbox que fallaron solo se veían en Sentry y se resolvían con SQL. Ahora se ven aquí y
 * se reintentan o se cierran con una nota; la venta incierta pide confirmar antes que se revisó el POS.
 */
describe('OutboxFailures', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useAdminOutboxStore.setState({ data: null, loading: false })
    signIn(['orders.view', 'orders.manage'])
  })

  it('lista las fallidas con su orden y marca la que pudo haber llegado al POS', async () => {
    await renderPage([failure(), failure({ id: 2, type: 'ORDER_CONFIRMATION_EMAIL', channel: 'MAIL', uncertain: false, lastError: 'Rechazado: dirección inválida' })])

    expect(screen.getByText('Venta al POS')).toBeInTheDocument()
    expect(screen.getByText('Correo de confirmación de pago')).toBeInTheDocument()
    expect(screen.getAllByText('Pudo haber llegado')).toHaveLength(1)
    expect(screen.getAllByRole('link', { name: 'ORD-10' })[0]).toHaveAttribute('href', '/admin/orders/10')
  })

  it('una venta incierta no se reintenta sin nota ni sin confirmar que se revisó el POS', async () => {
    const user = userEvent.setup()
    await renderPage([failure()])
    vi.mocked(adminApi.outbox.retry).mockResolvedValue(undefined)

    await user.click(screen.getByRole('button', { name: 'Revisar' }))
    const retry = screen.getByRole('button', { name: 'Reintentar' })
    expect(retry).toBeDisabled()

    await user.type(screen.getByLabelText('Nota'), 'No está en el POS')
    expect(retry).toBeDisabled()
    await user.click(screen.getByRole('checkbox', { name: 'Revisé en el POS que la venta no está' }))
    expect(retry).toBeEnabled()

    await user.click(retry)

    expect(adminApi.outbox.retry).toHaveBeenCalledWith(1, 'No está en el POS', true)
    expect(toast.success).toHaveBeenCalledWith('Envío devuelto a la cola')
    expect(await screen.findByText('No hay envíos fallidos.')).toBeInTheDocument()
  })

  it('una venta anterior al outbox solo se puede marcar resuelta', async () => {
    const user = userEvent.setup()
    await renderPage([failure({ companyId: null, legacy: true })])
    vi.mocked(adminApi.outbox.resolve).mockResolvedValue(undefined)

    await user.click(screen.getByRole('button', { name: 'Revisar' }))
    expect(screen.queryByRole('button', { name: 'Reintentar' })).not.toBeInTheDocument()
    await user.type(screen.getByLabelText('Nota'), 'Está en el POS desde marzo')
    await user.click(screen.getByRole('button', { name: 'Marcar resuelta' }))

    expect(adminApi.outbox.resolve).toHaveBeenCalledWith(1, 'Está en el POS desde marzo')
  })

  it('si el servidor rechaza la acción, la fila sigue y se muestra su mensaje', async () => {
    const user = userEvent.setup()
    await renderPage([failure({ uncertain: false, lastError: 'Rechazado: producto sin mapeo' })])
    vi.mocked(adminApi.outbox.retry).mockRejectedValue({ message: 'Este envío ya no está fallido' })

    await user.click(screen.getByRole('button', { name: 'Revisar' }))
    await user.type(screen.getByLabelText('Nota'), 'Producto mapeado')
    await user.click(screen.getByRole('button', { name: 'Reintentar' }))

    expect(toast.error).toHaveBeenCalledWith('Este envío ya no está fallido')
    expect(screen.getByText('ORD-10')).toBeInTheDocument()
  })

  it('sin orders.manage solo se mira', async () => {
    signIn(['orders.view'])
    await renderPage([failure()])

    expect(screen.queryByRole('button', { name: 'Revisar' })).not.toBeInTheDocument()
  })
})
