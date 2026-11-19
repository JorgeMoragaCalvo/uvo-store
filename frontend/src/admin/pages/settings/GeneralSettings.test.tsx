import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import GeneralSettings from './GeneralSettings'
import { toast } from 'sonner'
import adminApi from '@/admin/services/adminApi'
import { useAdminGeneralSettingsStore } from '@/admin/stores/useAdminGeneralSettingsStore'
import type { GeneralSettingsDto, GeneralSettingsUpdateRequest, PosResyncSummary } from '@/admin/types/admin'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))
vi.mock('@/admin/services/adminApi', () => ({
  default: {
    pos: { resync: vi.fn() },
    // IMP-07. Lo que la plataforma deja activar; por defecto, todo.
    paymentGateways: { availability: vi.fn(() => Promise.resolve([])) },
  },
}))

/**
 * PROD-12. La pestaña del POS no puede ofrecer un interruptor que no decide nada.
 *
 * `posSyncEnabled` existía desde el principio en este formulario y **ningún código del backend lo
 * leía**: apagarlo no apagaba nada. Ahora decide de verdad, pero por encima hay un interruptor de
 * plataforma —notificar una orden al POS emite un documento tributario— y cuando está apagado lo que
 * corresponde es decirlo, no dejar un formulario que parece hacer algo.
 */
const BASE: GeneralSettingsDto = {
  storeName: 'Tienda',
  storeEmail: '',
  storePhone: '',
  adminEmail: '',
  currency: 'CLP',
  currencySymbol: '$',
  taxRate: '19',
  pricesIncludeTax: true,
  shippingEnabled: true,
  defaultShippingCost: '0',
  freeShippingEnabled: false,
  freeShippingThreshold: '0',
  allowGuestCheckout: true,
  requirePhone: false,
  requireCompany: false,
  stripePublicKey: '',
  stripeSecretKeyConfigured: false,
  stripeEnabled: false,
  posApiUrl: '',
  posApiTokenConfigured: false,
  posWebhookSecretConfigured: false,
  posSyncEnabled: false,
  posIntegrationAvailable: false,
  metaTitle: '',
  metaDescription: '',
  metaKeywords: '',
  facebookUrl: '',
  instagramUrl: '',
  twitterUrl: '',
}

function renderWith(settings: GeneralSettingsDto) {
  useAdminGeneralSettingsStore.setState({
    ...useAdminGeneralSettingsStore.getInitialState(),
    settings,
    loading: false,
    fetch: async () => {},
  })
  return render(<GeneralSettings />)
}

async function openPosTab() {
  await userEvent.click(screen.getByRole('tab', { name: /pos/i }))
}

describe('GeneralSettings: pestaña POS', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('says the integration is disabled and locks the fields', async () => {
    renderWith(BASE)
    await openPosTab()

    await waitFor(() => {
      expect(screen.getByText(/integración con uvopos está deshabilitada/i)).toBeInTheDocument()
    })
    expect(screen.getByRole('switch', { name: /sincronización con pos/i })).toBeDisabled()
    expect(screen.getByLabelText(/url de la api del pos/i)).toBeDisabled()
    expect(screen.getByLabelText(/secreto de webhook/i)).toBeDisabled()
  })

  it('leaves the fields usable when the platform allows it', async () => {
    renderWith({ ...BASE, posIntegrationAvailable: true })
    await openPosTab()

    await waitFor(() => {
      expect(screen.getByRole('switch', { name: /sincronización con pos/i })).toBeEnabled()
    })
    expect(screen.queryByText(/integración con uvopos está deshabilitada/i)).not.toBeInTheDocument()
    expect(screen.getByLabelText(/url de la api del pos/i)).toBeEnabled()
  })
})

/**
 * POS-4. La resincronización concilia ahora los productos sincronizados con UvoPOS. El backend decide con lo
 * guardado (integración de plataforma, token y sincronización de la tienda), así que el botón también: con
 * algo de eso faltando queda deshabilitado y dice qué falta, en vez de ofrecer un botón que siempre falla.
 */
describe('GeneralSettings: resincronizar con UvoPOS', () => {
  const READY: GeneralSettingsDto = {
    ...BASE,
    posIntegrationAvailable: true,
    posApiTokenConfigured: true,
    posSyncEnabled: true,
  }
  const SUMMARY: PosResyncSummary = {
    checked: 3,
    stockCorrected: 1,
    catalogCorrected: 1,
    pendingInFlight: 1,
    changedMeanwhile: 0,
    missingInPos: 0,
    failedConnections: 0,
    errors: [],
  }

  beforeEach(() => {
    vi.clearAllMocks()
  })

  function resyncButton() {
    return screen.getByRole('button', { name: /resincronizar ahora/i })
  }

  it.each([
    ['sin integración', BASE, /se habilita junto con la integración/i],
    ['sin token', { ...READY, posApiTokenConfigured: false }, /configura y guarda el token/i],
    ['con la sincronización apagada', { ...READY, posSyncEnabled: false }, /activa y guarda la sincronización/i],
  ])('stays disabled %s and says why', async (_case, settings, reason) => {
    renderWith(settings)
    await openPosTab()

    await waitFor(() => expect(resyncButton()).toBeDisabled())
    expect(screen.getByText(reason)).toBeInTheDocument()
  })

  it('resyncs and shows the summary', async () => {
    vi.mocked(adminApi.pos.resync).mockResolvedValue(SUMMARY)
    renderWith(READY)
    await openPosTab()

    await waitFor(() => expect(resyncButton()).toBeEnabled())
    await userEvent.click(resyncButton())

    await waitFor(() => expect(toast.success).toHaveBeenCalled())
    expect(adminApi.pos.resync).toHaveBeenCalledTimes(1)
    const message = vi.mocked(toast.success).mock.calls[0][0] as string
    expect(message).toMatch(/3 productos revisados/)
    expect(message).toMatch(/1 con stock corregido/)
    expect(message).toMatch(/1 pendientes por ventas web en curso/)
  })

  it('shows the backend message when the resync is rejected', async () => {
    vi.mocked(adminApi.pos.resync).mockRejectedValue({ message: 'Esta tienda no tiene una conexión activa con UvoPOS' })
    renderWith(READY)
    await openPosTab()

    await waitFor(() => expect(resyncButton()).toBeEnabled())
    await userEvent.click(resyncButton())

    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith('Esta tienda no tiene una conexión activa con UvoPOS'),
    )
  })

  it('reports a failed POS read as an error, not as a success', async () => {
    vi.mocked(adminApi.pos.resync).mockResolvedValue({
      ...SUMMARY,
      checked: 0,
      failedConnections: 1,
      errors: ['El POS respondió HTTP 503 a la lectura del catálogo'],
    })
    renderWith(READY)
    await openPosTab()

    await waitFor(() => expect(resyncButton()).toBeEnabled())
    await userEvent.click(resyncButton())

    await waitFor(() => expect(toast.error).toHaveBeenCalled())
    expect(vi.mocked(toast.error).mock.calls[0][0]).toMatch(/HTTP 503/)
    expect(toast.success).not.toHaveBeenCalled()
  })
})

/**
 * IMP-07. Con STRIPE_ENABLED=false el backend rechaza activar Stripe y rechazaría el formulario entero: el interruptor
 * queda bloqueado, el motivo se ve, y una tienda que lo tenía activado de antes guarda con Stripe apagado.
 */
describe('GeneralSettings: Stripe sin ofrecer en la plataforma', () => {
  const REASON = 'Stripe no está disponible en esta plataforma. Elige otro medio de pago o consulta a quien opera la tienda.'

  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(adminApi.paymentGateways.availability).mockResolvedValue([
      { gateway: 'STRIPE', available: false, reason: REASON },
    ])
  })

  it('locks the switch and says why', async () => {
    renderWith(BASE)
    await userEvent.click(screen.getByRole('tab', { name: /stripe/i }))

    await waitFor(() => expect(screen.getByRole('note')).toHaveTextContent(REASON))
    expect(screen.getByRole('switch', { name: /stripe habilitado/i })).toBeDisabled()
  })

  it('saves a store that had it on from before with Stripe off', async () => {
    const update = vi.fn(async (settings: GeneralSettingsUpdateRequest) => ({ ...BASE, ...settings }))
    renderWith({ ...BASE, stripeEnabled: true })
    useAdminGeneralSettingsStore.setState({ update: update as never })
    await userEvent.click(screen.getByRole('tab', { name: /stripe/i }))
    await waitFor(() => expect(screen.getByRole('note')).toHaveTextContent(/se desactiva al guardar/i))

    await userEvent.click(screen.getByRole('button', { name: /guardar/i }))

    await waitFor(() => expect(update).toHaveBeenCalledTimes(1))
    expect(update.mock.calls[0][0].stripeEnabled).toBe(false)
  })
})
