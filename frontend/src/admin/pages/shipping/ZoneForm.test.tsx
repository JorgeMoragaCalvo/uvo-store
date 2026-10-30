import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import ZoneForm from './ZoneForm'
import adminApi from '@/admin/services/adminApi'
import api from '@/services/api'
import type { ShippingZoneDto } from '@/admin/types/admin'
import type { GeoRegion } from '@/types/api'

const params = vi.hoisted(() => ({ value: {} as Record<string, string> }))

vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>()
  return { ...actual, useNavigate: () => vi.fn(), useParams: () => params.value }
})

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

vi.mock('@/admin/services/adminApi', () => ({
  default: {
    shippingZones: { getById: vi.fn(), create: vi.fn(), update: vi.fn() },
  },
}))

vi.mock('@/services/api', () => ({
  default: { geo: { regions: vi.fn() } },
}))

const catalog: GeoRegion[] = [
  {
    code: '05',
    name: 'Valparaíso',
    communes: [
      { code: '05101', name: 'Valparaíso' },
      { code: '05109', name: 'Viña del Mar' },
    ],
  },
  {
    code: '13',
    name: 'Metropolitana de Santiago',
    communes: [
      { code: '13101', name: 'Santiago' },
      { code: '13120', name: 'Ñuñoa' },
      { code: '13201', name: 'Puente Alto' },
    ],
  },
]

/**
 * Catálogo de comunas. Las zonas se arman eligiendo de la lista oficial: antes eran dos cuadros de texto
 * libre, lo escrito tenía que coincidir letra por letra con el checkout, y una sola lista de comunas valía
 * para todas las regiones de la zona.
 */
function renderForm() {
  return render(
    <MemoryRouter>
      <ZoneForm />
    </MemoryRouter>,
  )
}

describe('ZoneForm: regiones y comunas del catálogo', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    params.value = {}
    vi.mocked(api.geo.regions).mockResolvedValue(catalog)
  })

  it('sends a whole region with no communes', async () => {
    const user = userEvent.setup()
    renderForm()

    await user.type(await screen.findByLabelText('Nombre'), 'Todo Valparaíso')
    await user.click(screen.getByRole('switch', { name: 'Valparaíso' }))
    await user.click(screen.getByRole('button', { name: 'Guardar' }))

    expect(adminApi.shippingZones.create).toHaveBeenCalledWith(
      expect.objectContaining({ regions: ['Valparaíso'], communes: [] }),
    )
  })

  it('sends only the communes picked when «Toda la región» is turned off', async () => {
    const user = userEvent.setup()
    renderForm()

    await user.type(await screen.findByLabelText('Nombre'), 'Centro')
    await user.click(screen.getByRole('switch', { name: 'Metropolitana de Santiago' }))
    await user.click(screen.getByRole('switch', { name: 'Toda la región Metropolitana de Santiago' }))
    await user.click(screen.getByRole('checkbox', { name: 'Ñuñoa' }))
    await user.click(screen.getByRole('checkbox', { name: 'Santiago' }))
    await user.click(screen.getByRole('button', { name: 'Guardar' }))

    // En el orden del catálogo, no en el que se marcaron.
    expect(adminApi.shippingZones.create).toHaveBeenCalledWith(
      expect.objectContaining({ regions: ['Metropolitana de Santiago'], communes: ['Santiago', 'Ñuñoa'] }),
    )
  })

  it('refuses a region with «Toda la región» off and no commune picked', async () => {
    const user = userEvent.setup()
    renderForm()

    await user.type(await screen.findByLabelText('Nombre'), 'Vacía')
    await user.click(screen.getByRole('switch', { name: 'Valparaíso' }))
    await user.click(screen.getByRole('switch', { name: 'Toda la región Valparaíso' }))
    await user.click(screen.getByRole('button', { name: 'Guardar' }))

    expect(adminApi.shippingZones.create).not.toHaveBeenCalled()
    // PROD-24b. Junto a la cobertura, y no en un toast que desaparece.
    expect(screen.getByRole('alert')).toHaveTextContent('Elige al menos una comuna de Valparaíso, o marca «Toda la región»')
    expect(screen.getByLabelText('Buscar comuna en Valparaíso')).toHaveAccessibleDescription(
      'Elige al menos una comuna de Valparaíso, o marca «Toda la región»',
    )
  })

  it('marks what an old hand-written zone has and warns about what the catalogue does not know', async () => {
    params.value = { id: '7' }
    const legacy: ShippingZoneDto = {
      id: 7,
      name: 'Escrita a mano',
      description: null,
      regions: ['Metropolitana de Santiago', 'Gran Santiago'],
      communes: ['Santiago', 'Santiago Centro'],
      active: true,
      sortOrder: 0,
      unrecognized: ['Gran Santiago', 'Santiago Centro'],
    }
    vi.mocked(adminApi.shippingZones.getById).mockResolvedValue(legacy)
    renderForm()

    expect(await screen.findByText(/no están en el catálogo/i)).toHaveTextContent('Gran Santiago, Santiago Centro')
    expect(screen.getByRole('switch', { name: 'Metropolitana de Santiago' })).toBeChecked()
    expect(screen.getByRole('switch', { name: 'Toda la región Metropolitana de Santiago' })).not.toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'Santiago' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'Puente Alto' })).not.toBeChecked()
  })
})
