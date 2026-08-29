import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { CartLine } from '@/stores/useCartStore'
import type { OrderConfirmation, Product } from '@/types/api'

vi.mock('@/services/api', () => ({
  default: {
    checkout: {
      getConfig: vi.fn(),
      createOrder: vi.fn(),
    },
    payment: {
      createCheckoutSession: vi.fn(),
    },
    webpay: {
      create: vi.fn(),
    },
    mercadopago: {
      createPreference: vi.fn(),
    },
  },
}))

const clearCart = vi.fn()

vi.mock('@/stores/useCartStore', () => ({
  useCartStore: {
    getState: vi.fn(),
  },
}))

const product: Product = {
  id: 1,
  name: 'Producto de prueba',
  slug: 'producto-de-prueba',
  shortDescription: null,
  description: null,
  productType: 'simple',
  sku: 'SKU-1',
  price: 1000,
  formattedPrice: '$1.000',
  stock: 10,
  inStock: true,
  manageStock: true,
  featuredImage: null,
  images: [],
  active: true,
  featured: false,
  metaTitle: null,
  metaDescription: null,
  category: null,
  variations: [],
  variationsCount: null,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

const cartLine: CartLine = { id: 1, type: 'product', product, variation: null, quantity: 2 }

const confirmation: OrderConfirmation = { orderId: 42, orderNumber: 'ORD-42', total: 2000 }

async function setup() {
  vi.clearAllMocks()
  const { useCartStore } = await import('@/stores/useCartStore')
  vi.mocked(useCartStore.getState).mockReturnValue({
    items: [cartLine],
    clearCart,
  } as never)

  const api = (await import('@/services/api')).default
  vi.mocked(api.checkout.createOrder).mockResolvedValue(confirmation)

  const { useCheckoutStore } = await import('./useCheckoutStore')
  useCheckoutStore.setState({
    paymentMethod: 'manual',
    customer: { email: '', firstName: '', lastName: '', phone: '' },
    customerNotes: '',
    error: null,
    loading: false,
  })

  return { api, useCheckoutStore }
}

describe('useCheckoutStore.processCheckout', () => {
  beforeEach(async () => {
    localStorage.clear()
  })

  it('does nothing when the cart is empty', async () => {
    const { useCheckoutStore } = await setup()
    const { useCartStore } = await import('@/stores/useCartStore')
    vi.mocked(useCartStore.getState).mockReturnValue({ items: [], clearCart } as never)

    const result = await useCheckoutStore.getState().processCheckout()

    expect(result).toEqual({ success: false })
  })

  it('creates the order and clears the cart for a manual payment', async () => {
    const { useCheckoutStore } = await setup()
    useCheckoutStore.setState({ paymentMethod: 'manual' })

    const result = await useCheckoutStore.getState().processCheckout()

    expect(result).toEqual({ success: true, orderNumber: 'ORD-42' })
    expect(clearCart).toHaveBeenCalled()
    expect(useCheckoutStore.getState().loading).toBe(false)
  })

  it('creates a Stripe checkout session and returns its redirect URL', async () => {
    const { api, useCheckoutStore } = await setup()
    useCheckoutStore.setState({ paymentMethod: 'stripe' })
    vi.mocked(api.payment.createCheckoutSession).mockResolvedValue({ sessionId: 'sess_1', url: 'https://stripe.test/pay' })

    const result = await useCheckoutStore.getState().processCheckout()

    expect(result).toEqual({ success: true, redirectUrl: 'https://stripe.test/pay' })
    expect(api.payment.createCheckoutSession).toHaveBeenCalledWith(
      confirmation.orderId,
      expect.stringContaining('/order-success?session_id='),
      expect.stringContaining('/checkout?canceled=1'),
    )
    // Stripe redirects away from the SPA, so the cart is cleared server-side on payment confirmation, not here.
    expect(clearCart).not.toHaveBeenCalled()
  })

  it('creates a Webpay transaction and returns the auto-submit form data', async () => {
    const { api, useCheckoutStore } = await setup()
    useCheckoutStore.setState({ paymentMethod: 'webpay' })
    vi.mocked(api.webpay.create).mockResolvedValue({ token: 'tok_123', url: 'https://webpay.test/init' })

    const result = await useCheckoutStore.getState().processCheckout()

    expect(result).toEqual({ success: true, webpayForm: { url: 'https://webpay.test/init', token: 'tok_123' } })
    // No returnUrl override — the backend must default it to its own host, not the frontend's.
    expect(api.webpay.create).toHaveBeenCalledWith(confirmation.orderId)
  })

  it('creates a MercadoPago preference and returns its init point', async () => {
    const { api, useCheckoutStore } = await setup()
    useCheckoutStore.setState({ paymentMethod: 'mercadopago' })
    vi.mocked(api.mercadopago.createPreference).mockResolvedValue({
      preferenceId: 'pref_1',
      initPoint: 'https://mercadopago.test/checkout',
    })

    const result = await useCheckoutStore.getState().processCheckout()

    expect(result).toEqual({ success: true, redirectUrl: 'https://mercadopago.test/checkout' })
    expect(api.mercadopago.createPreference).toHaveBeenCalledWith(
      confirmation.orderId,
      expect.stringContaining(`/order-success?order=${confirmation.orderNumber}`),
      expect.stringContaining('/checkout?error=mercadopago'),
      expect.stringContaining('/checkout?pending=1'),
    )
  })

  it('sets an error and does not clear the cart when order creation fails', async () => {
    const { api, useCheckoutStore } = await setup()
    vi.mocked(api.checkout.createOrder).mockRejectedValue({ message: 'Stock insuficiente' })

    const result = await useCheckoutStore.getState().processCheckout()

    expect(result).toEqual({ success: false })
    expect(useCheckoutStore.getState().error).toBe('Stock insuficiente')
    expect(useCheckoutStore.getState().loading).toBe(false)
    expect(clearCart).not.toHaveBeenCalled()
  })
})

describe('useCheckoutStore.fetchConfig', () => {
  it('defaults the payment method to stripe when it is enabled', async () => {
    const { api, useCheckoutStore } = await setup()
    vi.mocked(api.checkout.getConfig).mockResolvedValue({
      stripePublicKey: 'pk_test',
      stripeEnabled: true,
      webpayEnabled: false,
      mercadopagoEnabled: false,
      shippingEnabled: false,
      defaultShippingCost: 0,
      freeShippingEnabled: false,
      freeShippingThreshold: null,
      allowGuestCheckout: true,
      requirePhone: false,
    } as never)

    await useCheckoutStore.getState().fetchConfig()

    expect(useCheckoutStore.getState().paymentMethod).toBe('stripe')
  })

  it('defaults the payment method to manual when stripe is disabled', async () => {
    const { api, useCheckoutStore } = await setup()
    vi.mocked(api.checkout.getConfig).mockResolvedValue({
      stripePublicKey: null,
      stripeEnabled: false,
      webpayEnabled: true,
      mercadopagoEnabled: false,
      shippingEnabled: false,
      defaultShippingCost: 0,
      freeShippingEnabled: false,
      freeShippingThreshold: null,
      allowGuestCheckout: true,
      requirePhone: false,
    } as never)

    await useCheckoutStore.getState().fetchConfig()

    expect(useCheckoutStore.getState().paymentMethod).toBe('manual')
  })
})
