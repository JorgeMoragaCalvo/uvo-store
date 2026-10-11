import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import AcceptInvitation from './AcceptInvitation'
import adminApi from '@/admin/services/adminApi'
import { useAdminAuthStore } from '@/admin/stores/useAdminAuthStore'

const navigateMock = vi.fn()

vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>()
  return { ...actual, useNavigate: () => navigateMock }
})

vi.mock('@/admin/services/adminApi', () => ({
  default: { auth: { acceptInvitation: vi.fn() } },
}))

/**
 * PROD-06. La pantalla donde el administrador invitado elige su contraseña.
 *
 * El correo enlazaba aquí desde que existe el envío y esta ruta no existía: el invitado recibía un 404.
 * Lo que se afirma son los cuatro finales que pide el criterio, y sobre todo que **ninguno de los malos
 * pierde la contraseña ya escrita** — volver a teclearla por un enlace viejo es el «perder el estado de
 * forma confusa» que la §6.3 manda evitar.
 */
describe('AcceptInvitation', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useAdminAuthStore.setState({ token: null, user: null, sessionExpired: false })
  })

  function renderAt(search: string) {
    return render(
      <MemoryRouter initialEntries={[`/admin/aceptar-invitacion${search}`]}>
        <AcceptInvitation />
      </MemoryRouter>,
    )
  }

  async function fillAndSubmit(password = 'clave-elegida-1') {
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('Contraseña'), password)
    await user.type(screen.getByLabelText('Confirmar contraseña'), password)
    await user.click(screen.getByRole('button', { name: 'Entrar al panel' }))
    return user
  }

  it('sin token en la URL no muestra el formulario, sino cómo salir', () => {
    renderAt('')

    expect(screen.getByText('Enlace incompleto')).toBeInTheDocument()
    expect(screen.queryByLabelText('Contraseña')).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ir al inicio de sesión' })).toBeInTheDocument()
  })

  it('con éxito inicia la sesión con los permisos y entra al panel', async () => {
    vi.mocked(adminApi.auth.acceptInvitation).mockResolvedValue({
      token: 'jwt-nuevo', id: 7, name: 'Invitado', email: 'invitado@demo.local', type: 'ADMIN',
      permissions: ['products.view', 'orders.manage'],
    })
    renderAt('?token=tok-123')

    await fillAndSubmit()

    await waitFor(() => expect(adminApi.auth.acceptInvitation).toHaveBeenCalledWith('tok-123', 'clave-elegida-1'))
    // A1: sin los permisos el panel se dibuja vacío, que es el síntoma que describió F08.
    expect(useAdminAuthStore.getState().user?.permissions).toEqual(['products.view', 'orders.manage'])
    expect(useAdminAuthStore.getState().token).toBe('jwt-nuevo')
    expect(navigateMock).toHaveBeenCalledWith('/admin', { replace: true })
  })

  it('un token rechazado explica qué hacer y conserva la contraseña escrita', async () => {
    // El interceptor de adminApi rechaza con el cuerpo del ApiError esparcido, que lleva `status`.
    vi.mocked(adminApi.auth.acceptInvitation).mockRejectedValue({
      status: 400, message: 'La invitación no es válida o ha expirado',
    })
    renderAt('?token=tok-viejo')

    await fillAndSubmit()

    await waitFor(() => expect(screen.getByText('La invitación no es válida o ha expirado')).toBeInTheDocument())
    expect(screen.getByText(/te reenvíe la invitación/)).toBeInTheDocument()
    // Lo que no debe pasar: que el invitado tenga que volver a escribirlo todo.
    expect(screen.getByLabelText('Contraseña')).toHaveValue('clave-elegida-1')
    expect(navigateMock).not.toHaveBeenCalled()
  })

  it('un fallo de red dice otra cosa y no habla de la invitación', async () => {
    // Sin `status` no hubo respuesta: la petición no llegó, así que el enlace puede estar perfectamente
    // bien. Decirle que su invitación caducó sería mandarlo a pedir una nueva sin motivo.
    vi.mocked(adminApi.auth.acceptInvitation).mockRejectedValue({ message: 'Network Error' })
    renderAt('?token=tok-123')

    await fillAndSubmit()

    await waitFor(() => expect(screen.getByText(/No pudimos conectar/)).toBeInTheDocument())
    expect(screen.queryByText(/te reenvíe la invitación/)).not.toBeInTheDocument()
    expect(screen.getByLabelText('Contraseña')).toHaveValue('clave-elegida-1')
  })

  it('si las contraseñas no coinciden no se llama al servidor', async () => {
    renderAt('?token=tok-123')
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('Contraseña'), 'clave-elegida-1')
    await user.type(screen.getByLabelText('Confirmar contraseña'), 'otra-distinta-2')
    await user.click(screen.getByRole('button', { name: 'Entrar al panel' }))

    expect(await screen.findByText('Las contraseñas no coinciden')).toBeInTheDocument()
    expect(adminApi.auth.acceptInvitation).not.toHaveBeenCalled()
  })
})
