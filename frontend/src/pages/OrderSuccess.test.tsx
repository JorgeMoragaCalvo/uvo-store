import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import OrderSuccess from './OrderSuccess'
import api from '@/services/api'
import { useCartStore } from '@/stores/useCartStore'
import type { OrderTracking, PaymentVerificationResult } from '@/types/api'

const navigateMock = vi.fn()

vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>()
  return { ...actual, useNavigate: () => navigateMock }
})

vi.mock('@/services/api', () => ({
  default: {
    payment: { verify: vi.fn() },
    orders: { track: vi.fn() },
  },
}))

const PENDING_PAYMENT_KEY = 'uvostore_pending_payment'

function verification(orderPaymentStatus: string): PaymentVerificationResult {
  return { sessionPaymentStatus: orderPaymentStatus === 'PAID' ? 'paid' : 'unpaid', orderId: 7, orderNumber: 'ORD-7', orderPaymentStatus }
}

/** Como lo devuelve /orders/track: los estados en minúscula. */
function tracking(paymentStatus: string): OrderTracking {
  return {
    orderNumber: 'ORD-7',
    status: 'pending',
    paymentStatus: paymentStatus.toLowerCase(),
    fulfillmentStatus: 'unfulfilled',
    trackingNumber: null,
    trackingUrl: null,
    total: 9990,
    itemsCount: 1,
    createdAt: '2026-11-06T00:00:00Z',
    shippedAt: null,
    deliveredAt: null,
  }
}

function renderAt(url: string) {
  render(
    <MemoryRouter initialEntries={[url]}>
      <OrderSuccess />
    </MemoryRouter>,
  )
}

/**
 * GAP-08. La página de éxito daba las gracias sin saber si el pago se había confirmado: una sesión de Stripe que
 * volvía sin pagar, o MercadoPago antes de su webhook. Ahora confirma el estado, y el carrito solo se vacía cuando
 * ya no hay nada que reintentar.
 */
describe('OrderSuccess', () => {
  const clearCart = vi.fn()

  beforeEach(() => {
    vi.clearAllMocks()
    useCartStore.setState({ clearCart })
    sessionStorage.setItem(PENDING_PAYMENT_KEY, JSON.stringify({ orderId: 7, orderNumber: 'ORD-7' }))
  })

  afterEach(() => {
    vi.useRealTimers()
    sessionStorage.clear()
  })

  describe('Stripe (session_id)', () => {
    it('con el pago confirmado da las gracias y vacía el carrito', async () => {
      vi.mocked(api.payment.verify).mockResolvedValue(verification('PAID'))
      renderAt('/order-success?session_id=cs_1')

      expect(await screen.findByText('¡Gracias por tu compra!')).toBeInTheDocument()
      expect(screen.getByText('ORD-7')).toBeInTheDocument()
      expect(clearCart).toHaveBeenCalled()
      expect(sessionStorage.getItem(PENDING_PAYMENT_KEY)).toBeNull()
    })

    it('con la sesión de vuelta sin pagar muestra «en proceso», no las gracias', async () => {
      vi.mocked(api.payment.verify).mockResolvedValue(verification('PENDING'))
      renderAt('/order-success?session_id=cs_1')

      expect(await screen.findByText('Tu pago está en proceso')).toBeInTheDocument()
      expect(screen.queryByText('¡Gracias por tu compra!')).not.toBeInTheDocument()
    })

    it('con el pago rechazado vuelve al checkout sin tocar el carrito', async () => {
      vi.mocked(api.payment.verify).mockResolvedValue(verification('FAILED'))
      renderAt('/order-success?session_id=cs_1')

      await vi.waitFor(() => expect(navigateMock).toHaveBeenCalledWith('/checkout?error=stripe', { replace: true }))
      expect(clearCart).not.toHaveBeenCalled()
      expect(sessionStorage.getItem(PENDING_PAYMENT_KEY)).not.toBeNull()
    })
  })

  describe('MercadoPago (gateway=mercadopago)', () => {
    it('espera al webhook y da las gracias cuando el pago queda confirmado', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true })
      vi.mocked(api.orders.track)
        .mockResolvedValueOnce(tracking('PENDING'))
        .mockResolvedValueOnce(tracking('PENDING'))
        .mockResolvedValue(tracking('PAID'))
      renderAt('/order-success?order=ORD-7&gateway=mercadopago')

      expect(await screen.findByText('Confirmando tu pago...')).toBeInTheDocument()
      expect(clearCart).not.toHaveBeenCalled()

      await act(() => vi.advanceTimersByTimeAsync(4000))

      expect(await screen.findByText('¡Gracias por tu compra!')).toBeInTheDocument()
      expect(api.orders.track).toHaveBeenCalledTimes(3)
      expect(api.orders.track).toHaveBeenCalledWith('ORD-7')
      expect(clearCart).toHaveBeenCalled()
    })

    it('si el webhook no llega a tiempo, muestra «en proceso»', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true })
      vi.mocked(api.orders.track).mockResolvedValue(tracking('PENDING'))
      renderAt('/order-success?order=ORD-7&gateway=mercadopago')

      await act(() => vi.advanceTimersByTimeAsync(20_000))

      expect(await screen.findByText('Tu pago está en proceso')).toBeInTheDocument()
      expect(api.orders.track).toHaveBeenCalledTimes(10)
      expect(clearCart).toHaveBeenCalled()
    })

    it('con el pago rechazado vuelve al checkout sin tocar el carrito', async () => {
      vi.mocked(api.orders.track).mockResolvedValue(tracking('FAILED'))
      renderAt('/order-success?order=ORD-7&gateway=mercadopago')

      await vi.waitFor(() =>
        expect(navigateMock).toHaveBeenCalledWith('/checkout?error=mercadopago', { replace: true }),
      )
      expect(clearCart).not.toHaveBeenCalled()
    })
  })

  it('sin marca (pago manual, Webpay ya confirmado) da las gracias sin consultar nada', async () => {
    renderAt('/order-success?order=ORD-7')

    expect(await screen.findByText('¡Gracias por tu compra!')).toBeInTheDocument()
    expect(api.orders.track).not.toHaveBeenCalled()
    expect(api.payment.verify).not.toHaveBeenCalled()
    expect(clearCart).toHaveBeenCalled()
  })
})
