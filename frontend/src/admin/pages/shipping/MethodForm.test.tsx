import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import MethodForm from './MethodForm'
import adminApi from '@/admin/services/adminApi'

vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>()
  return { ...actual, useNavigate: () => vi.fn(), useParams: () => ({}) }
})

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

vi.mock('@/admin/services/adminApi', () => ({
  default: {
    shippingMethods: { getById: vi.fn(), create: vi.fn(), update: vi.fn(), updateCredentials: vi.fn() },
    shippingCarriers: { available: vi.fn() },
  },
}))

/**
 * PROD-12. El formulario no puede ofrecer un transportista que no se puede usar.
 *
 * El desplegable eran dos <SelectItem> escritos a mano, «Chilexpress» y «Correos de Chile», y Correos
 * es un placeholder que siempre devuelve vacío: se podía seleccionar, guardar y activar el método, y el
 * checkout no lo mostraba nunca. El comerciante creía haber habilitado un despacho que no existe. Es la
 * misma forma del fallo que F11 encontró con `isOnSale='false'` y PROD-06 con `sendInvitation='false'`:
 * un control que no hace lo que dice.
 */
function renderForm() {
  return render(
    <MemoryRouter>
      <MethodForm />
    </MemoryRouter>,
  )
}

describe('MethodForm: transportistas', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('disables the carrier switch when no carrier is available', async () => {
    vi.mocked(adminApi.shippingCarriers.available).mockResolvedValue([])
    renderForm()

    await waitFor(() => {
      expect(screen.getByRole('switch', { name: /cotizar automáticamente/i })).toBeDisabled()
    })
    expect(screen.getByText(/no hay transportistas habilitados/i)).toBeInTheDocument()
  })

  it('offers only the carriers the backend reports as available', async () => {
    vi.mocked(adminApi.shippingCarriers.available).mockResolvedValue(['CHILEXPRESS'])
    renderForm()

    await waitFor(() => {
      expect(screen.getByRole('switch', { name: /cotizar automáticamente/i })).toBeEnabled()
    })
    expect(screen.queryByText(/no hay transportistas habilitados/i)).not.toBeInTheDocument()
    // Correos de Chile no aparece por ninguna parte del formulario, ni como opción ni como etiqueta.
    expect(screen.queryByText(/correos de chile/i)).not.toBeInTheDocument()
  })

  it('offers nothing when the carrier list cannot be fetched', async () => {
    // El lado seguro ante un fallo de red: no se ofrece un transportista que no se pudo confirmar.
    vi.mocked(adminApi.shippingCarriers.available).mockRejectedValue(new Error('red caída'))
    renderForm()

    await waitFor(() => {
      expect(screen.getByRole('switch', { name: /cotizar automáticamente/i })).toBeDisabled()
    })
  })
})
