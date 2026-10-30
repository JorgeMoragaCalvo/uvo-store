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
    sessionStorage.clear()
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
    // PROD-04: solo el id del pedido. Las URLs de retorno las construye el backend desde la tienda de la
    // orden; mandarlas desde aquí era un redirect abierto, porque llegaban a Stripe sin validar.
    expect(api.payment.createCheckoutSession).toHaveBeenCalledWith(confirmation.orderId)
    // Stripe redirects away from the SPA, so the cart is cleared server-side on payment confirmation, not here.
    expect(clearCart).not.toHaveBeenCalled()
  })

  it('creates a Webpay transaction and returns the auto-submit form data', async () => {
    const { api, useCheckoutStore } = await setup()
    useCheckoutStore.setState({ paymentMethod: 'webpay' })
    vi.mocked(api.webpay.create).mockResolvedValue({ token: 'tok_123', url: 'https://webpay.test/init' })

    const result = await useCheckoutStore.getState().processCheckout()

    expect(result).toEqual({ success: true, webpayForm: { url: 'https://webpay.test/init', token: 'tok_123' } })
    // El backend arma el returnUrl con el origen de la API de esa tienda, no con el del frontend.
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
    // PROD-04: ídem — las tres URLs de retorno las decide el servidor.
    expect(api.mercadopago.createPreference).toHaveBeenCalledWith(confirmation.orderId)
  })

  it('sets an error and does not clear the cart when order creation fails', async () => {
    const { api, useCheckoutStore } = await setup()
    vi.mocked(api.checkout.createOrder).mockRejectedValue({ status: 400, message: 'Cupón vencido' })

    const result = await useCheckoutStore.getState().processCheckout()

    expect(result).toEqual({ success: false })
    expect(useCheckoutStore.getState().error).toBe('Cupón vencido')
    expect(useCheckoutStore.getState().loading).toBe(false)
    expect(clearCart).not.toHaveBeenCalled()
  })

  // PROD-24b. Sin respuesta, el mensaje era el de axios, en inglés.
  it('explains a network failure in Spanish instead of showing the axios message', async () => {
    const { api, useCheckoutStore } = await setup()
    vi.mocked(api.checkout.createOrder).mockRejectedValue({ message: 'Network Error' })

    await useCheckoutStore.getState().processCheckout()

    expect(useCheckoutStore.getState().error).toMatch(/no pudimos conectar/i)
  })

  it('hands the per-line stock problems of a 409 to the cart', async () => {
    const { api, useCheckoutStore } = await setup()
    const applyStockErrors = vi.fn()
    const { useCartStore } = await import('@/stores/useCartStore')
    vi.mocked(useCartStore.getState).mockReturnValue({ items: [cartLine], clearCart, applyStockErrors } as never)
    vi.mocked(api.checkout.createOrder).mockRejectedValue({
      status: 409,
      message: 'Uno o más productos no tienen stock suficiente',
      fieldErrors: { 'items.0': 'Stock insuficiente. Solo hay 1 disponibles' },
    })

    await useCheckoutStore.getState().processCheckout()

    expect(applyStockErrors).toHaveBeenCalledWith({ 'items.0': 'Stock insuficiente. Solo hay 1 disponibles' })
    expect(useCheckoutStore.getState().error).toBe('Uno o más productos no tienen stock suficiente')
  })

  // El botón no puede volver a activarse mientras el navegador sale hacia la pasarela.
  it('keeps loading while the browser leaves for the gateway', async () => {
    const { api, useCheckoutStore } = await setup()
    useCheckoutStore.setState({ paymentMethod: 'stripe' })
    vi.mocked(api.payment.createCheckoutSession).mockResolvedValue({ sessionId: 'sess_1', url: 'https://stripe.test/pay' })

    await useCheckoutStore.getState().processCheckout()

    expect(useCheckoutStore.getState().loading).toBe(true)
  })
})

// PROD-24b. Volver de la pasarela sin pagar y reintentar paga la MISMA orden: una nueva retenía otra vez
// stock y cupón durante 24 h.
describe('useCheckoutStore — retrying a payment', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  it('pays the same order again instead of creating another one', async () => {
    const { api, useCheckoutStore } = await setup()
    useCheckoutStore.setState({ paymentMethod: 'stripe' })
    vi.mocked(api.payment.createCheckoutSession).mockResolvedValue({ sessionId: 'sess_1', url: 'https://stripe.test/pay' })

    await useCheckoutStore.getState().processCheckout()
    await useCheckoutStore.getState().processCheckout()

    expect(api.checkout.createOrder).toHaveBeenCalledTimes(1)
    expect(api.payment.createCheckoutSession).toHaveBeenCalledTimes(2)
    expect(api.payment.createCheckoutSession).toHaveBeenLastCalledWith(confirmation.orderId)
  })

  it('creates a new order when the payment method changed', async () => {
    const { api, useCheckoutStore } = await setup()
    useCheckoutStore.setState({ paymentMethod: 'stripe' })
    vi.mocked(api.payment.createCheckoutSession).mockResolvedValue({ sessionId: 'sess_1', url: 'https://stripe.test/pay' })
    vi.mocked(api.webpay.create).mockResolvedValue({ token: 'tok', url: 'https://webpay.test/init' })

    await useCheckoutStore.getState().processCheckout()
    useCheckoutStore.setState({ paymentMethod: 'webpay' })
    await useCheckoutStore.getState().processCheckout()

    expect(api.checkout.createOrder).toHaveBeenCalledTimes(2)
  })

  it('creates a new order when the cart changed', async () => {
    const { api, useCheckoutStore } = await setup()
    useCheckoutStore.setState({ paymentMethod: 'stripe' })
    vi.mocked(api.payment.createCheckoutSession).mockResolvedValue({ sessionId: 'sess_1', url: 'https://stripe.test/pay' })

    await useCheckoutStore.getState().processCheckout()
    const { useCartStore } = await import('@/stores/useCartStore')
    vi.mocked(useCartStore.getState).mockReturnValue({ items: [{ ...cartLine, quantity: 3 }], clearCart } as never)
    await useCheckoutStore.getState().processCheckout()

    expect(api.checkout.createOrder).toHaveBeenCalledTimes(2)
  })

  // Webpay marca la orden fallida al rechazar el pago, y el backend responde 400 a un pago nuevo sobre ella.
  it('falls back to a new order when the saved one is no longer payable', async () => {
    const { api, useCheckoutStore } = await setup()
    useCheckoutStore.setState({ paymentMethod: 'webpay' })
    vi.mocked(api.webpay.create)
      .mockResolvedValueOnce({ token: 'tok_1', url: 'https://webpay.test/init' })
      .mockRejectedValueOnce({ status: 400, message: 'Esta orden ya fue procesada' })
      .mockResolvedValueOnce({ token: 'tok_2', url: 'https://webpay.test/init' })
    vi.mocked(api.checkout.createOrder)
      .mockResolvedValueOnce(confirmation)
      .mockResolvedValueOnce({ orderId: 43, orderNumber: 'ORD-43', total: 2000 })

    await useCheckoutStore.getState().processCheckout()
    const result = await useCheckoutStore.getState().processCheckout()

    expect(api.checkout.createOrder).toHaveBeenCalledTimes(2)
    expect(api.webpay.create).toHaveBeenLastCalledWith(43)
    expect(result).toEqual({ success: true, webpayForm: { url: 'https://webpay.test/init', token: 'tok_2' } })
  })

  it('never reuses an order for a cash-on-delivery purchase', async () => {
    const { api, useCheckoutStore } = await setup()
    useCheckoutStore.setState({ paymentMethod: 'stripe' })
    vi.mocked(api.payment.createCheckoutSession).mockResolvedValue({ sessionId: 'sess_1', url: 'https://stripe.test/pay' })

    await useCheckoutStore.getState().processCheckout()
    useCheckoutStore.setState({ paymentMethod: 'manual' })
    await useCheckoutStore.getState().processCheckout()

    expect(api.checkout.createOrder).toHaveBeenCalledTimes(2)
    expect(sessionStorage.getItem('uvostore_pending_payment')).toBeNull()
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
