import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import PaymentGateways from './PaymentGateways'
import adminApi from '@/admin/services/adminApi'
import { useAdminPaymentGatewaysStore } from '@/admin/stores/useAdminPaymentGatewaysStore'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

vi.mock('@/admin/services/adminApi', () => ({
  default: {
    paymentGateways: { list: vi.fn(), availability: vi.fn(), update: vi.fn() },
    // IMP-07. La tarjeta de transferencia carga lo suyo; aquí no interesa.
    bankTransfer: { get: vi.fn(() => new Promise(() => {})), update: vi.fn() },
  },
}))

const REASON = 'Webpay no está disponible en esta plataforma.'

/**
 * IMP-02. Con WEBPAY_ENABLED=false el backend rechaza activar Webpay; el panel no puede ofrecer un interruptor
 * que termine en error, y tiene que decir por qué. Webpay es la primera tarjeta de la página.
 */
describe('PaymentGateways: disponibilidad de la plataforma', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useAdminPaymentGatewaysStore.setState({ configs: [], availability: [], loading: false })
  })

  it('disables the Webpay switch and explains why when the platform does not offer it', async () => {
    vi.mocked(adminApi.paymentGateways.list).mockResolvedValue([])
    vi.mocked(adminApi.paymentGateways.availability).mockResolvedValue([
      { gateway: 'WEBPAY', available: false, reason: REASON },
      { gateway: 'MERCADOPAGO', available: true, reason: null },
    ])
    render(<PaymentGateways />)

    await waitFor(() => expect(screen.getByText(REASON)).toBeInTheDocument())
    const switches = screen.getAllByRole('switch')
    // MercadoPago sigue activable.
    expect(switches[0]).toBeDisabled()
    expect(switches[1]).toBeEnabled()
  })

  it('lets a store turn off a Webpay config enabled before the platform switched it off', async () => {
    vi.mocked(adminApi.paymentGateways.list).mockResolvedValue([
      { id: 1, gateway: 'WEBPAY', enabled: true, credentialsSet: { childCommerceCode: true } },
    ])
    vi.mocked(adminApi.paymentGateways.availability).mockResolvedValue([
      { gateway: 'WEBPAY', available: false, reason: REASON },
    ])
    render(<PaymentGateways />)

    await waitFor(() => expect(screen.getByText(/no se ofrece en el checkout/i)).toBeInTheDocument())
    // Apagarla sí: es lo único que ese interruptor puede hacer ahora.
    expect(screen.getAllByRole('switch')[0]).toBeEnabled()
  })

  it('shows no notice when Webpay is available', async () => {
    vi.mocked(adminApi.paymentGateways.list).mockResolvedValue([])
    vi.mocked(adminApi.paymentGateways.availability).mockResolvedValue([
      { gateway: 'WEBPAY', available: true, reason: null },
      { gateway: 'MERCADOPAGO', available: true, reason: null },
    ])
    render(<PaymentGateways />)

    await waitFor(() => expect(screen.getAllByRole('switch')).toHaveLength(2))
    screen.getAllByRole('switch').forEach((control) => expect(control).toBeEnabled())
    expect(screen.queryByRole('note')).not.toBeInTheDocument()
  })
})
