import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { toast } from 'sonner'
import OrderDetail from './OrderDetail'
import adminApi from '@/admin/services/adminApi'
import { useAdminAuthStore } from '@/admin/stores/useAdminAuthStore'
import { useAdminOrdersStore } from '@/admin/stores/useAdminOrdersStore'
import type { AdminOrderDetail, AdminOrderItem, AdminOrderRefund } from '@/admin/types/admin'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

vi.mock('@/admin/services/adminApi', () => ({
  default: {
    orders: { getById: vi.fn(), resolveRefund: vi.fn(), retryStock: vi.fn(), releaseStockShortage: vi.fn() },
  },
}))

function refund(overrides: Partial<AdminOrderRefund> = {}): AdminOrderRefund {
  return {
    id: 70,
    amount: 4000,
    type: 'PARTIAL',
    status: 'COMPLETED',
    gatewayReference: null,
    reason: null,
    userName: null,
    createdAt: '2026-11-01T00:00:00Z',
    ...overrides,
  }
}

function item(overrides: Partial<AdminOrderItem> = {}): AdminOrderItem {
  return {
    id: 1,
    productId: 5,
    productName: 'Polera',
    productSku: 'POL-1',
    variationId: null,
    quantity: 1,
    price: 10000,
    subtotal: 10000,
    stockMissing: false,
    ...overrides,
  }
}

function order(refunds: AdminOrderRefund[], overrides: Partial<AdminOrderDetail> = {}): AdminOrderDetail {
  return {
    id: 10,
    orderNumber: 'ORD-10',
    customerId: null,
    customerEmail: 'comprador@test.local',
    customerFirstName: 'Test',
    customerLastName: 'Comprador',
    customerPhone: null,
    status: 'PROCESSING',
    paymentStatus: 'PAID',
    fulfillmentStatus: 'UNFULFILLED',
    subtotal: 10000,
    discountAmount: 0,
    shippingCost: 0,
    taxAmount: 0,
    total: 10000,
    paymentMethod: 'WEBPAY',
    trackingNumber: null,
    trackingUrl: null,
    shippingAddress: null,
    billingAddress: null,
    shippingRegion: null,
    shippingCommune: null,
    shippingPostalCode: null,
    customerNotes: null,
    notes: null,
    items: [],
    createdAt: '2026-11-01T00:00:00Z',
    shippedAt: null,
    deliveredAt: null,
    refundedAmount: refunds.filter((r) => r.status !== 'FAILED').reduce((sum, r) => sum + r.amount, 0),
    refunds,
    stockShortage: false,
    ...overrides,
  }
}

/**
 * GAP-02. Un reembolso con resultado incierto queda PENDING y reserva su importe. Sin el estado a la
 * vista no se distinguía de uno hecho, y la única forma de cerrarlo era SQL.
 */
describe('OrderDetail: estado y resolución de reembolsos', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useAdminOrdersStore.setState({ detail: null, detailLoading: false })
    useAdminAuthStore.setState({
      token: 'jwt',
      user: { id: 1, name: 'Admin', email: 'admin@test.local', permissions: ['orders.view', 'orders.refund'] },
    })
  })

  async function renderOrder(detail: AdminOrderDetail) {
    vi.mocked(adminApi.orders.getById).mockResolvedValue(detail)
    render(
      <MemoryRouter initialEntries={['/admin/orders/10']}>
        <Routes>
          <Route path="/admin/orders/:id" element={<OrderDetail />} />
        </Routes>
      </MemoryRouter>,
    )
    await screen.findByText('Reembolsos')
  }

  it('muestra el estado de cada reembolso y avisa lo que falta confirmar', async () => {
    await renderOrder(
      order([
        refund({ id: 1, status: 'COMPLETED', gatewayReference: 'NULLIFIED' }),
        refund({ id: 2, amount: 3000, status: 'PENDING' }),
        refund({ id: 3, amount: 2000, status: 'FAILED' }),
      ]),
    )

    expect(screen.getByText('Hecho')).toBeInTheDocument()
    expect(screen.getByText('Pendiente')).toBeInTheDocument()
    expect(screen.getByText('Rechazado')).toBeInTheDocument()
    expect(screen.getByText(/Incluye .*3\.000 sin confirmar/)).toBeInTheDocument()
    // Solo el pendiente se puede resolver.
    expect(screen.getAllByRole('button', { name: 'Sí se hizo' })).toHaveLength(1)
  })

  it('confirmar como hecho exige nota y referencia, y no mueve dinero: solo resuelve', async () => {
    const user = userEvent.setup()
    const pending = refund({ status: 'PENDING' })
    await renderOrder(order([pending]))
    vi.mocked(adminApi.orders.resolveRefund).mockResolvedValue(
      order([{ ...pending, status: 'COMPLETED', gatewayReference: 'REF-77' }]),
    )

    const done = screen.getByRole('button', { name: 'Sí se hizo' })
    const notDone = screen.getByRole('button', { name: 'No se hizo' })
    expect(done).toBeDisabled()
    expect(notDone).toBeDisabled()

    await user.type(screen.getByLabelText('Nota'), 'Visto en Transbank')
    // Con la nota basta para decir que no se hizo; para decir que sí, falta la referencia.
    expect(notDone).toBeEnabled()
    expect(done).toBeDisabled()

    await user.type(screen.getByLabelText('Referencia de la pasarela'), 'REF-77')
    await user.click(done)

    expect(adminApi.orders.resolveRefund).toHaveBeenCalledWith(10, 70, 'COMPLETED', 'REF-77', 'Visto en Transbank')
    expect(await screen.findByText('Hecho')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Sí se hizo' })).not.toBeInTheDocument()
  })

  it('marcar como no hecho manda FAILED', async () => {
    const user = userEvent.setup()
    const pending = refund({ status: 'PENDING' })
    await renderOrder(order([pending]))
    vi.mocked(adminApi.orders.resolveRefund).mockResolvedValue(order([{ ...pending, status: 'FAILED' }]))

    await user.type(screen.getByLabelText('Nota'), 'No aparece en Transbank')
    await user.click(screen.getByRole('button', { name: 'No se hizo' }))

    expect(adminApi.orders.resolveRefund).toHaveBeenCalledWith(10, 70, 'FAILED', '', 'No aparece en Transbank')
    expect(await screen.findByText('Rechazado')).toBeInTheDocument()
  })
})

/**
 * GAP-03. Una orden cobrada sin stock para alguna línea quedaba con una nota en un historial que el panel no
 * muestra, y se podía marcar enviada como cualquier otra.
 */
describe('OrderDetail: orden cobrada sin stock', () => {
  const shortOrder = () =>
    order([], {
      stockShortage: true,
      items: [
        item({ id: 1, productName: 'Polera', stockMissing: true }),
        item({ id: 2, productName: 'Gorro', stockMissing: false }),
      ],
    })

  beforeEach(() => {
    vi.clearAllMocks()
    useAdminOrdersStore.setState({ detail: null, detailLoading: false })
    useAdminAuthStore.setState({
      token: 'jwt',
      user: { id: 1, name: 'Admin', email: 'admin@test.local', permissions: ['orders.view', 'orders.manage'] },
    })
  })

  async function renderOrder(detail: AdminOrderDetail) {
    vi.mocked(adminApi.orders.getById).mockResolvedValue(detail)
    render(
      <MemoryRouter initialEntries={['/admin/orders/10']}>
        <Routes>
          <Route path="/admin/orders/:id" element={<OrderDetail />} />
        </Routes>
      </MemoryRouter>,
    )
    await screen.findByText('Productos')
  }

  it('avisa qué falta y no deja despachar', async () => {
    await renderOrder(shortOrder())

    expect(screen.getByText('Cobrada sin stock')).toBeInTheDocument()
    expect(screen.getByText(/no había stock para Polera \(×1\)\./)).toBeInTheDocument()
    // Una insignia en la cabecera y otra en la línea que falta; la otra línea no lleva.
    expect(screen.getAllByText('Sin stock')).toHaveLength(2)
    expect(screen.getByRole('button', { name: 'Marcar enviada' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Marcar entregada' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Guardar' })).toBeDisabled()
    // Preparar sí.
    expect(screen.getByRole('button', { name: 'Marcar en proceso' })).toBeEnabled()
  })

  it('una orden con stock no muestra nada de esto', async () => {
    await renderOrder(order([], { items: [item()] }))

    expect(screen.queryByText('Cobrada sin stock')).not.toBeInTheDocument()
    expect(screen.queryByText('Sin stock')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Marcar enviada' })).toBeEnabled()
  })

  it('reintentar el descuento con reposición desbloquea el despacho', async () => {
    const user = userEvent.setup()
    await renderOrder(shortOrder())
    vi.mocked(adminApi.orders.retryStock).mockResolvedValue(order([], { items: [item()] }))

    await user.click(screen.getByRole('button', { name: 'Reintentar descuento' }))

    expect(adminApi.orders.retryStock).toHaveBeenCalledWith(10)
    expect(toast.success).toHaveBeenCalledWith('Stock descontado: la orden ya se puede despachar')
    expect(screen.queryByText('Cobrada sin stock')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Marcar enviada' })).toBeEnabled()
  })

  it('si el reintento no alcanza, lo dice y la orden sigue bloqueada', async () => {
    const user = userEvent.setup()
    await renderOrder(shortOrder())
    vi.mocked(adminApi.orders.retryStock).mockResolvedValue(shortOrder())

    await user.click(screen.getByRole('button', { name: 'Reintentar descuento' }))

    expect(toast.success).toHaveBeenCalledWith('Todavía falta stock para algunas líneas')
    expect(screen.getByRole('button', { name: 'Marcar enviada' })).toBeDisabled()
  })

  it('liberar el bloqueo exige una nota', async () => {
    const user = userEvent.setup()
    await renderOrder(shortOrder())
    vi.mocked(adminApi.orders.releaseStockShortage).mockResolvedValue(order([], { items: [item()] }))

    const release = screen.getByRole('button', { name: 'Liberar bloqueo' })
    expect(release).toBeDisabled()

    await user.type(screen.getByLabelText('Nota'), 'Conseguido en la otra sucursal')
    await user.click(release)

    expect(adminApi.orders.releaseStockShortage).toHaveBeenCalledWith(10, 'Conseguido en la otra sucursal')
    expect(screen.queryByText('Cobrada sin stock')).not.toBeInTheDocument()
  })
})
