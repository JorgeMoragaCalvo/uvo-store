import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import Checkout from './Checkout'
import { useCartStore } from '@/stores/useCartStore'
import { useCheckoutStore } from '@/stores/useCheckoutStore'
import type { CartLine } from '@/stores/useCartStore'
import type { CheckoutConfig, OrderConfirmation, Product, WebpayCreateResult } from '@/types/api'

const navigateMock = vi.fn()

vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>()
  return { ...actual, useNavigate: () => navigateMock }
})

vi.mock('@/services/api', () => ({
  default: {
    checkout: {
      getConfig: vi.fn(),
      createOrder: vi.fn(),
    },
    webpay: {
      create: vi.fn(),
    },
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
  price: 5000,
  formattedPrice: '$5.000',
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

const line: CartLine = { id: 1, type: 'product', product, variation: null, quantity: 1 }

const baseConfig: CheckoutConfig = {
  stripePublicKey: null,
  stripeEnabled: false,
  webpayEnabled: false,
  mercadopagoEnabled: false,
  shippingEnabled: false,
  defaultShippingCost: 0,
  freeShippingEnabled: false,
  freeShippingThreshold: null,
  allowGuestCheckout: true,
  requirePhone: true,
} as CheckoutConfig

function renderCheckout() {
  return render(
    <MemoryRouter>
      <Checkout />
    </MemoryRouter>,
  )
}

async function fillContactStep(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByPlaceholderText('Email'), 'cliente@test.local')
  await user.type(screen.getByPlaceholderText('Nombre'), 'Juan')
  await user.type(screen.getByPlaceholderText('Apellido'), 'Pérez')
  await user.type(screen.getByPlaceholderText('Teléfono'), '+56911111111')
  await user.click(screen.getByRole('button', { name: /continuar/i }))
}

async function fillAddressStep(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByPlaceholderText('Dirección'), 'Calle Falsa 123')
  await user.type(screen.getByPlaceholderText('Ciudad'), 'Santiago')
  await user.type(screen.getByPlaceholderText('Región'), 'Metropolitana')
  await user.type(screen.getByPlaceholderText('Código postal'), '8320000')
  await user.click(screen.getByRole('button', { name: /continuar/i }))
}

describe('Checkout page', () => {
  beforeEach(async () => {
    localStorage.clear()
    navigateMock.mockClear()
    const api = (await import('@/services/api')).default
    vi.mocked(api.checkout.getConfig).mockResolvedValue(baseConfig)
    useCartStore.setState({ items: [line], totals: useCartStore.getInitialState().totals })
    useCheckoutStore.setState(useCheckoutStore.getInitialState())
  })

  it('shows an empty-cart message and skips the wizard when the cart is empty', () => {
    useCartStore.setState({ items: [] })

    renderCheckout()

    expect(screen.getByText('Tu carrito está vacío')).toBeInTheDocument()
    expect(screen.queryByPlaceholderText('Email')).not.toBeInTheDocument()
  })

  it('keeps "Continuar" disabled on the contact step until all fields are filled', async () => {
    const user = userEvent.setup()
    renderCheckout()

    const continueButton = screen.getByRole('button', { name: /continuar/i })
    expect(continueButton).toBeDisabled()

    await user.type(screen.getByPlaceholderText('Email'), 'cliente@test.local')
    expect(continueButton).toBeDisabled()

    await user.type(screen.getByPlaceholderText('Nombre'), 'Juan')
    await user.type(screen.getByPlaceholderText('Apellido'), 'Pérez')
    await user.type(screen.getByPlaceholderText('Teléfono'), '+56911111111')
    expect(continueButton).toBeEnabled()
  })

  it('advances to the address step and back to contact', async () => {
    const user = userEvent.setup()
    renderCheckout()

    await fillContactStep(user)
    expect(screen.getByPlaceholderText('Dirección')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /atrás/i }))
    expect(screen.getByPlaceholderText('Email')).toBeInTheDocument()
  })

  it('only shows payment options enabled in the store config', async () => {
    const api = (await import('@/services/api')).default
    vi.mocked(api.checkout.getConfig).mockResolvedValue({ ...baseConfig, webpayEnabled: true })

    const user = userEvent.setup()
    renderCheckout()
    await fillContactStep(user)
    await fillAddressStep(user)

    expect(screen.getByLabelText(/webpay/i)).toBeInTheDocument()
    expect(screen.queryByLabelText(/tarjeta \(stripe\)/i)).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/mercadopago/i)).not.toBeInTheDocument()
    expect(screen.getByLabelText(/pago contra entrega/i)).toBeInTheDocument()
  })

  it('confirms a manual order and navigates to the success page', async () => {
    const api = (await import('@/services/api')).default
    const confirmation: OrderConfirmation = { orderId: 1, orderNumber: 'ORD-1', total: 5000 }
    vi.mocked(api.checkout.createOrder).mockResolvedValue(confirmation)

    const user = userEvent.setup()
    renderCheckout()
    await fillContactStep(user)
    await fillAddressStep(user)
    await user.click(screen.getByRole('button', { name: /confirmar pedido/i }))

    await vi.waitFor(() => expect(navigateMock).toHaveBeenCalledWith('/order-success?order=ORD-1'))
  })

  it('builds and submits the hidden Webpay form when the webpay method is chosen', async () => {
    const api = (await import('@/services/api')).default
    vi.mocked(api.checkout.getConfig).mockResolvedValue({ ...baseConfig, webpayEnabled: true })
    const confirmation: OrderConfirmation = { orderId: 1, orderNumber: 'ORD-1', total: 5000 }
    vi.mocked(api.checkout.createOrder).mockResolvedValue(confirmation)
    const webpayResult: WebpayCreateResult = { token: 'tok_123', url: 'https://webpay.test/init' }
    vi.mocked(api.webpay.create).mockResolvedValue(webpayResult)
    const submitSpy = vi.spyOn(HTMLFormElement.prototype, 'submit').mockImplementation(() => {})

    const user = userEvent.setup()
    renderCheckout()
    await fillContactStep(user)
    await fillAddressStep(user)
    await user.click(screen.getByLabelText(/webpay/i))
    await user.click(screen.getByRole('button', { name: /confirmar pedido/i }))

    await vi.waitFor(() => expect(submitSpy).toHaveBeenCalled())
    const form = submitSpy.mock.contexts[0] as HTMLFormElement
    expect(form.action).toBe('https://webpay.test/init')
    expect((form.elements.namedItem('token_ws') as HTMLInputElement).value).toBe('tok_123')
    expect(navigateMock).not.toHaveBeenCalled()

    submitSpy.mockRestore()
  })
})
