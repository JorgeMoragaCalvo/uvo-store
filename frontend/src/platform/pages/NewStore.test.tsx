import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { toast } from 'sonner'
import NewStore from './NewStore'
import platformApi from '@/platform/services/platformApi'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

vi.mock('@/platform/services/platformApi', () => ({
  default: { createStore: vi.fn() },
}))

/**
 * POS-6. El formulario de operador que crea una tienda.
 *
 * Antes pedía la contraseña del cliente, y el operador la conocía para siempre. Ahora el dueño nace invitado y
 * la elige él: lo que se afirma es que el formulario ya no la pide ni la manda, y que dice a dónde fue la
 * invitación y qué hacer si no llega. Y que un 404 se explica como lo que es: la API de plataforma solo
 * responde desde la red privada.
 */
describe('NewStore', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  async function fillAndSubmit() {
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('API key de plataforma'), 'clave-plataforma')
    await user.type(screen.getByLabelText('Nick (subdominio interno)'), 'tiendadejuan')
    await user.type(screen.getByLabelText('Nombre de la tienda'), 'Tienda de Juan')
    await user.type(screen.getByLabelText('Nombre del cliente'), 'Juan')
    await user.type(screen.getByLabelText('Email del cliente'), 'juan@tienda.cl')
    await user.click(screen.getByRole('button', { name: 'Crear tienda' }))
  }

  it('no pide la contraseña del cliente ni la manda', async () => {
    vi.mocked(platformApi.createStore).mockResolvedValue({
      storeId: 1, storeName: 'Tienda de Juan', slug: 'tiendadejuan', domain: null, adminUserId: 2,
      adminEmail: 'juan@tienda.cl', domainVerifiedAt: null, storefrontUrl: 'https://tiendadejuan.uvostore.cl',
      adminUrl: 'https://tiendadejuan.uvostore.cl/admin/login', created: true,
    })
    render(<NewStore />)

    expect(screen.queryByLabelText(/contraseña/i)).not.toBeInTheDocument()
    await fillAndSubmit()

    await waitFor(() => expect(platformApi.createStore).toHaveBeenCalledTimes(1))
    const [key, body] = vi.mocked(platformApi.createStore).mock.calls[0]
    expect(key).toBe('clave-plataforma')
    expect(body).not.toHaveProperty('adminPassword')
    expect(body).toEqual({
      slug: 'tiendadejuan', domain: '', storeName: 'Tienda de Juan', adminName: 'Juan', adminEmail: 'juan@tienda.cl',
    })
  })

  it('al terminar dice a dónde fue la invitación y qué hacer si no llega', async () => {
    vi.mocked(platformApi.createStore).mockResolvedValue({
      storeId: 1, storeName: 'Tienda de Juan', slug: 'tiendadejuan', domain: null, adminUserId: 2,
      adminEmail: 'juan@tienda.cl', domainVerifiedAt: null, storefrontUrl: 'https://tiendadejuan.uvostore.cl',
      adminUrl: 'https://tiendadejuan.uvostore.cl/admin/login', created: true,
    })
    render(<NewStore />)

    await fillAndSubmit()

    expect(await screen.findByText(/Le enviamos una invitación a/)).toBeInTheDocument()
    expect(screen.getByText(/¿Olvidaste tu contraseña\?/)).toBeInTheDocument()
  })

  it('un 404 se explica como acceso desde fuera de la red privada', async () => {
    vi.mocked(platformApi.createStore).mockRejectedValue({ status: 404, message: 'El recurso solicitado no existe.' })
    render(<NewStore />)

    await fillAndSubmit()

    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith('La API de plataforma no es accesible desde esta red'))
  })

  it('otro error muestra el mensaje del backend', async () => {
    vi.mocked(platformApi.createStore).mockRejectedValue({ status: 400, message: 'Ese nick ya está en uso' })
    render(<NewStore />)

    await fillAndSubmit()

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Ese nick ya está en uso'))
  })
})
