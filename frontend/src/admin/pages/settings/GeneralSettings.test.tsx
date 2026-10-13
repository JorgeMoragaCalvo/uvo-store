import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import GeneralSettings from './GeneralSettings'
import { useAdminGeneralSettingsStore } from '@/admin/stores/useAdminGeneralSettingsStore'
import type { GeneralSettingsDto } from '@/admin/types/admin'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

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
