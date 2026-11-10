import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import type { ReactNode } from 'react'
import ShippingPolicy from './ShippingPolicy'
import ReturnsPolicy from './ReturnsPolicy'
import Privacy from './Privacy'
import Terms from './Terms'
import api from '@/services/api'
import { useStoreSettingsStore } from '@/stores/useStoreSettingsStore'
import type { CheckoutConfig, PublicStoreSettings } from '@/types/api'

/**
 * GAP-18. Las páginas legales tenían texto fijo, igual para todas las tiendas: tarifas y envío gratis inventados,
 * cookies y datos que el sitio no usa, una garantía legal de 3 meses y ningún dato del vendedor. Estas pruebas
 * fijan que lo que varía sale de la configuración de la tienda y que esas promesas no vuelven.
 */

vi.mock('@/services/api', () => ({
  default: {
    shipping: { coverage: vi.fn(), methods: vi.fn() },
    checkout: { getConfig: vi.fn() },
  },
}))

function setSettings(overrides: Partial<PublicStoreSettings> = {}) {
  useStoreSettingsStore.setState({
    settings: {
      storeName: 'Tienda Uno',
      contactEmail: 'hola@tiendauno.cl',
      contactPhone: '+56 9 1234 5678',
      shippingEnabled: true,
      freeShippingEnabled: false,
      freeShippingThreshold: '0',
      pricesIncludeTax: true,
      legalName: null,
      legalTaxId: null,
      legalAddress: null,
      withdrawalRightExcluded: false,
      ...overrides,
    } as PublicStoreSettings,
  })
}

function renderPage(page: ReactNode) {
  return render(<MemoryRouter>{page}</MemoryRouter>)
}

beforeEach(() => {
  vi.clearAllMocks()
  useStoreSettingsStore.setState(useStoreSettingsStore.getInitialState())
  vi.mocked(api.shipping.coverage).mockResolvedValue([
    { region: 'Metropolitana de Santiago', communes: ['Santiago'] },
    { region: 'Valparaíso', communes: ['Viña del Mar'] },
  ])
  vi.mocked(api.shipping.methods).mockResolvedValue([
    { name: 'Despacho a domicilio', description: 'Entrega en su puerta', minDeliveryDays: 2, maxDeliveryDays: 4 },
    { name: 'Retiro en bodega', description: null, minDeliveryDays: 1, maxDeliveryDays: 1 },
  ])
  vi.mocked(api.checkout.getConfig).mockResolvedValue({
    webpayEnabled: true,
    mercadopagoEnabled: false,
    stripeEnabled: true,
  } as CheckoutConfig)
})

describe('LegalLayout (responsable)', () => {
  it('identifies the seller with the configured legal data and the formatted RUT', () => {
    setSettings({ legalName: 'Comercial Uno SpA', legalTaxId: '76123456-0', legalAddress: 'Av. Uno 123, Santiago' })
    renderPage(<ReturnsPolicy />)

    expect(screen.getByRole('heading', { name: 'Responsable y contacto' })).toBeInTheDocument()
    expect(screen.getByText('Comercial Uno SpA')).toBeInTheDocument()
    expect(screen.getByText('RUT: 76.123.456-0')).toBeInTheDocument()
    expect(screen.getByText('Domicilio: Av. Uno 123, Santiago')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'hola@tiendauno.cl' })).toHaveAttribute('href', 'mailto:hola@tiendauno.cl')
  })

  it('falls back to the store name and hides the lines the store did not fill in', () => {
    setSettings()
    renderPage(<ReturnsPolicy />)

    expect(screen.getAllByText('Tienda Uno').length).toBeGreaterThan(0)
    expect(screen.queryByText(/^RUT:/)).not.toBeInTheDocument()
    expect(screen.queryByText(/^Domicilio:/)).not.toBeInTheDocument()
  })
})

describe('ShippingPolicy', () => {
  it('lists the store coverage and the methods with their delivery time', async () => {
    setSettings()
    renderPage(<ShippingPolicy />)

    expect(await screen.findByText('Metropolitana de Santiago')).toBeInTheDocument()
    expect(screen.getByText('Valparaíso')).toBeInTheDocument()
    expect(screen.getByText('Despacho a domicilio').closest('li')).toHaveTextContent('Despacho a domicilio — 2-4 días hábiles. Entrega en su puerta')
    expect(screen.getByText('Retiro en bodega').closest('li')).toHaveTextContent('Retiro en bodega — 1 día hábil')
  })

  it('announces free shipping with the configured threshold only when it is on', async () => {
    setSettings({ freeShippingEnabled: true, freeShippingThreshold: '30000' })
    renderPage(<ShippingPolicy />)

    expect(await screen.findByText(/en compras desde \$30\.000/)).toBeInTheDocument()
  })

  it('does not announce free shipping while it is off', async () => {
    setSettings({ freeShippingEnabled: false, freeShippingThreshold: '30000' })
    renderPage(<ShippingPolicy />)

    await screen.findByText('Metropolitana de Santiago')
    expect(screen.queryByText(/Envío gratis/)).not.toBeInTheDocument()
  })

  it('no longer carries the hard-coded rates, zones or tracking email', async () => {
    setSettings()
    const { container } = renderPage(<ShippingPolicy />)

    await screen.findByText('Metropolitana de Santiago')
    for (const stale of ['5.990', '3.990', '50.000', 'Isla de Pascua', 'Zona Extrema', 'email con el número de tracking']) {
      expect(container).not.toHaveTextContent(stale)
    }
  })

  it('says so when the store has no shipping zones yet', async () => {
    setSettings()
    vi.mocked(api.shipping.coverage).mockResolvedValue([])
    vi.mocked(api.shipping.methods).mockResolvedValue([])
    renderPage(<ShippingPolicy />)

    expect(await screen.findByText('La tienda aún no tiene zonas de despacho configuradas.')).toBeInTheDocument()
  })

  it('does not describe zones or methods when the store does not charge shipping', () => {
    setSettings({ shippingEnabled: false })
    renderPage(<ShippingPolicy />)

    expect(screen.getByText(/no cobra el envío en el checkout/)).toBeInTheDocument()
    expect(screen.queryByText('1. Cobertura')).not.toBeInTheDocument()
  })

  it('shows a message instead of the sections when loading fails', async () => {
    setSettings()
    vi.mocked(api.shipping.methods).mockRejectedValue(new Error('network'))
    renderPage(<ShippingPolicy />)

    expect(await screen.findByText(/No pudimos cargar la información de envíos/)).toBeInTheDocument()
  })
})

describe('ReturnsPolicy', () => {
  it('grants the 10-day withdrawal right and the 6-month legal warranty by default', () => {
    setSettings()
    const { container } = renderPage(<ReturnsPolicy />)

    expect(container).toHaveTextContent('10 días corridos')
    expect(container).toHaveTextContent('garantía legal de 6 meses')
    for (const stale of ['3 meses', 'RMA', '48 horas', 'Cambio por talla']) {
      expect(container).not.toHaveTextContent(stale)
    }
  })

  it('states expressly that there is no withdrawal right when the store excludes it', () => {
    setSettings({ withdrawalRightExcluded: true })
    const { container } = renderPage(<ReturnsPolicy />)

    expect(container).toHaveTextContent('no ofrece derecho a retracto')
    expect(container).not.toHaveTextContent('10 días corridos')
    expect(container).toHaveTextContent('garantía legal de 6 meses')
  })
})

describe('Privacy', () => {
  it('names the payment gateways the store has on', async () => {
    setSettings()
    renderPage(<Privacy />)

    expect(await screen.findByText('Pasarelas de pago: Webpay (Transbank), Stripe')).toBeInTheDocument()
  })

  it('does not claim data or cookies the site does not use', async () => {
    setSettings()
    const { container } = renderPage(<Privacy />)

    await screen.findByText(/Pasarelas de pago/)
    expect(container).toHaveTextContent('no usa cookies de analítica ni de publicidad')
    for (const stale of ['RUT', 'Portabilidad', 'promociones', '7 años']) {
      expect(container).not.toHaveTextContent(stale)
    }
  })
})

describe('Terms', () => {
  it('follows the store tax setting', () => {
    setSettings({ pricesIncludeTax: false })
    const { container } = renderPage(<Terms />)

    expect(container).toHaveTextContent('el IVA se suma en el checkout')
  })

  it('follows the store withdrawal setting', () => {
    setSettings({ withdrawalRightExcluded: true })
    const { container } = renderPage(<Terms />)

    expect(container).toHaveTextContent('no ofrece derecho a retracto')
    expect(container).not.toHaveTextContent('10 días corridos')
  })
})
