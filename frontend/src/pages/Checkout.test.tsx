import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import Checkout from './Checkout'
import { useCartStore } from '@/stores/useCartStore'
import { useCheckoutStore } from '@/stores/useCheckoutStore'
import type { CartLine } from '@/stores/useCartStore'
import type {
  CartCalculationResult,
  CheckoutConfig,
  OrderConfirmation,
  Product,
  ShippingCoverage,
  WebpayCreateResult,
} from '@/types/api'

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
    payment: {
      createCheckoutSession: vi.fn(),
    },
    cart: {
      calculate: vi.fn(),
      validate: vi.fn(),
    },
    shipping: {
      coverage: vi.fn(),
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

// Catálogo de comunas: la cobertura trae siempre las comunas elegibles, con su nombre canónico.
const coverage: ShippingCoverage[] = [
  { region: 'Metropolitana de Santiago', communes: ['Providencia', 'Santiago'] },
  { region: 'Valparaíso', communes: ['Quilpué', 'Viña del Mar'] },
]

function calculation(overrides: Partial<CartCalculationResult> = {}): CartCalculationResult {
  return {
    subtotalWithoutTax: 5000,
    subtotalWithTax: 5000,
    shippingCost: 3990,
    taxAmount: 0,
    discountAmount: 0,
    total: 8990,
    pricesIncludeTax: false,
    taxRate: 0,
    freeShippingThreshold: null,
    shippingEnabled: true,
    shippingAvailable: true,
    couponApplied: false,
    ...overrides,
  }
}

function renderCheckout(url = '/checkout') {
  return render(
    <MemoryRouter initialEntries={[url]}>
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

// Región and Comuna are pickers now, not free text: zone matching is an exact string compare
// against what the admin configured, so a typed value would essentially never match.
async function fillAddressStep(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByPlaceholderText('Dirección'), 'Calle Falsa 123')
  await user.type(screen.getByPlaceholderText('Ciudad'), 'Santiago')
  await user.type(screen.getByPlaceholderText('Código postal'), '8320000')
  await user.selectOptions(await screen.findByLabelText('Región'), 'Metropolitana de Santiago')
  await user.selectOptions(await screen.findByLabelText('Comuna'), 'Santiago')
  await user.click(screen.getByRole('button', { name: /continuar/i }))
}

describe('Checkout page', () => {
  beforeEach(async () => {
    localStorage.clear()
    navigateMock.mockClear()
    const api = (await import('@/services/api')).default
    vi.mocked(api.checkout.getConfig).mockResolvedValue(baseConfig)
    vi.mocked(api.shipping.coverage).mockResolvedValue(coverage)
    vi.mocked(api.cart.calculate).mockResolvedValue(calculation())
    vi.mocked(api.cart.validate).mockResolvedValue({ valid: true, items: [], errors: {} })
    useCartStore.setState({ ...useCartStore.getInitialState(), items: [line] })
    useCheckoutStore.setState(useCheckoutStore.getInitialState())
    sessionStorage.clear()
  })

  it('names every contact field with a visible label', () => {
    renderCheckout()

    expect(screen.getByLabelText('Email')).toBeInTheDocument()
    expect(screen.getByLabelText('Nombre')).toBeInTheDocument()
    expect(screen.getByLabelText('Apellido')).toBeInTheDocument()
    expect(screen.getByLabelText('Teléfono')).toBeInTheDocument()
    expect(screen.getByLabelText('Código de descuento')).toBeInTheDocument()
  })

  it('ties a validation error to its field', async () => {
    const user = userEvent.setup()
    renderCheckout()

    await user.type(screen.getByLabelText('Email'), 'malo')
    await user.click(screen.getByRole('button', { name: /continuar/i }))

    const email = screen.getByLabelText('Email')
    await vi.waitFor(() => expect(email).toHaveAttribute('aria-invalid', 'true'))
    expect(email).toHaveAccessibleDescription('Ingresa un email válido')
  })

  it('shows which cart line ran out of stock before the customer reaches «Confirmar»', async () => {
    const api = (await import('@/services/api')).default
    vi.mocked(api.cart.validate).mockResolvedValue({
      valid: false,
      items: [],
      errors: { 'items.0': 'Stock insuficiente. Solo hay 0 disponibles' },
    })

    renderCheckout()

    const alert = await screen.findByText(/necesitan atención/i)
    expect(alert.parentElement).toHaveTextContent('Producto de prueba: Stock insuficiente. Solo hay 0 disponibles')
    expect(screen.getByRole('link', { name: /revisar el carrito/i })).toHaveAttribute('href', '/cart')
  })

  // PROD-24b. Antes la vuelta de la pasarela caía en el paso 1, vacío y sin aviso.
  it('resumes at the payment step with the saved details after a cancelled payment', async () => {
    sessionStorage.setItem(
      'uvostore_pending_payment',
      JSON.stringify({
        orderId: 7,
        orderNumber: 'ORD-7',
        paymentMethod: 'stripe',
        cartSignature: JSON.stringify({
          items: [['product', 1, 1]],
          region: 'Metropolitana de Santiago',
          commune: 'Santiago',
          couponCode: '',
        }),
        region: 'Metropolitana de Santiago',
        commune: 'Santiago',
        couponCode: '',
        customer: { email: 'cliente@test.local', firstName: 'Juan', lastName: 'Pérez', phone: '+56911111111' },
        shippingAddress: {
          addressLine1: 'Calle Falsa 123',
          addressLine2: '',
          city: 'Santiago',
          state: 'Metropolitana de Santiago',
          postalCode: '8320000',
          country: 'CL',
        },
        customerNotes: '',
      }),
    )
    const api = (await import('@/services/api')).default
    vi.mocked(api.checkout.getConfig).mockResolvedValue({ ...baseConfig, stripeEnabled: true })
    vi.mocked(api.payment.createCheckoutSession).mockResolvedValue({ sessionId: 's', url: 'https://stripe.test/pay' })
    const assign = vi.spyOn(window, 'location', 'get').mockReturnValue({ ...window.location, href: '' } as Location)

    const user = userEvent.setup()
    renderCheckout('/checkout?canceled=1')

    expect(await screen.findByRole('alert')).toHaveTextContent(/no se completó el pago/i)
    await user.click(await screen.findByRole('button', { name: /confirmar pedido/i }))

    await vi.waitFor(() => expect(api.payment.createCheckoutSession).toHaveBeenCalledWith(7))
    expect(api.checkout.createOrder).not.toHaveBeenCalled()
    assign.mockRestore()
  })

  it('does not offer to pay again while the payment is still being processed', async () => {
    sessionStorage.setItem('uvostore_pending_payment', JSON.stringify({ orderId: 7, orderNumber: 'ORD-7' }))

    renderCheckout('/checkout?pending=1')

    expect(screen.getByRole('heading', { name: /tu pago está en proceso/i })).toBeInTheDocument()
    expect(screen.getByText('ORD-7')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /seguir mi pedido/i })).toHaveAttribute('href', '/track-order?order=ORD-7')
    expect(screen.queryByRole('button', { name: /confirmar pedido/i })).not.toBeInTheDocument()
  })

  it('shows an empty-cart message and skips the wizard when the cart is empty', () => {
    useCartStore.setState({ items: [] })

    renderCheckout()

    expect(screen.getByText('Tu carrito está vacío')).toBeInTheDocument()
    expect(screen.queryByPlaceholderText('Email')).not.toBeInTheDocument()
  })

  it('refuses to advance with a malformed email, and says why', async () => {
    const user = userEvent.setup()
    renderCheckout()

    await user.type(screen.getByPlaceholderText('Email'), 'esto-no-es-un-email')
    await user.type(screen.getByPlaceholderText('Nombre'), 'Juan')
    await user.type(screen.getByPlaceholderText('Apellido'), 'Pérez')
    await user.type(screen.getByPlaceholderText('Teléfono'), '+56911111111')
    await user.click(screen.getByRole('button', { name: /continuar/i }))

    expect(await screen.findByText('Ingresa un email válido')).toBeInTheDocument()
    // Still on the contact step — the old truthiness check let this through.
    expect(screen.queryByPlaceholderText('Dirección')).not.toBeInTheDocument()
  })

  it('refuses to advance without choosing a region', async () => {
    const user = userEvent.setup()
    renderCheckout()
    await fillContactStep(user)

    await user.type(screen.getByPlaceholderText('Dirección'), 'Calle Falsa 123')
    await user.type(screen.getByPlaceholderText('Ciudad'), 'Santiago')
    await user.type(screen.getByPlaceholderText('Código postal'), '8320000')
    await user.click(screen.getByRole('button', { name: /continuar/i }))

    expect(await screen.findByText('Selecciona una región')).toBeInTheDocument()
  })

  it('offers the communes of the chosen region', async () => {
    const user = userEvent.setup()
    renderCheckout()
    await fillContactStep(user)

    expect(screen.queryByLabelText('Comuna')).not.toBeInTheDocument()
    await user.selectOptions(await screen.findByLabelText('Región'), 'Valparaíso')
    const communes = within(await screen.findByLabelText('Comuna')).getAllByRole('option').map((o) => o.textContent)
    expect(communes).toEqual(['Elige una comuna', 'Quilpué', 'Viña del Mar'])
  })

  it('refuses to advance without choosing a commune — it decides the zone', async () => {
    const user = userEvent.setup()
    renderCheckout()
    await fillContactStep(user)

    await user.type(screen.getByPlaceholderText('Dirección'), 'Calle Falsa 123')
    await user.type(screen.getByPlaceholderText('Ciudad'), 'Santiago')
    await user.type(screen.getByPlaceholderText('Código postal'), '8320000')
    await user.selectOptions(await screen.findByLabelText('Región'), 'Metropolitana de Santiago')
    await user.click(screen.getByRole('button', { name: /continuar/i }))

    expect(await screen.findByText('Selecciona una comuna')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /confirmar pedido/i })).not.toBeInTheDocument()
  })

  it('clears the commune when the region changes, so it never travels with the wrong region', async () => {
    const user = userEvent.setup()
    renderCheckout()
    await fillContactStep(user)

    await user.selectOptions(await screen.findByLabelText('Región'), 'Metropolitana de Santiago')
    await user.selectOptions(await screen.findByLabelText('Comuna'), 'Santiago')
    await user.selectOptions(screen.getByLabelText('Región'), 'Valparaíso')

    expect(screen.getByLabelText('Comuna')).toHaveValue('')
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

  it('sends the destination with the order — the field that used to be missing', async () => {
    const api = (await import('@/services/api')).default
    const confirmation: OrderConfirmation = { orderId: 1, orderNumber: 'ORD-1', total: 5000 }
    vi.mocked(api.checkout.createOrder).mockResolvedValue(confirmation)

    const user = userEvent.setup()
    renderCheckout()
    await fillContactStep(user)
    await fillAddressStep(user)
    await user.click(screen.getByRole('button', { name: /confirmar pedido/i }))

    await vi.waitFor(() => expect(api.checkout.createOrder).toHaveBeenCalled())
    expect(vi.mocked(api.checkout.createOrder).mock.calls[0][0]).toMatchObject({
      region: 'Metropolitana de Santiago',
      commune: 'Santiago',
    })
  })

  it('blocks confirmation when the store does not deliver to the chosen address', async () => {
    const api = (await import('@/services/api')).default
    vi.mocked(api.cart.calculate).mockResolvedValue(calculation({ shippingAvailable: false, shippingCost: 0 }))

    const user = userEvent.setup()
    renderCheckout()
    await fillContactStep(user)
    await fillAddressStep(user)

    expect(await screen.findByText(/no hay envío disponible/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /confirmar pedido/i })).toBeDisabled()
  })

  it('tells the customer when a coupon code is rejected', async () => {
    const api = (await import('@/services/api')).default
    vi.mocked(api.cart.calculate).mockResolvedValue(calculation({ couponApplied: false }))

    const user = userEvent.setup()
    renderCheckout()

    await user.type(screen.getByPlaceholderText('Código de descuento'), 'NO-EXISTE')
    await user.click(screen.getByRole('button', { name: /aplicar/i }))

    expect(await screen.findByText('El código no es válido')).toBeInTheDocument()
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
