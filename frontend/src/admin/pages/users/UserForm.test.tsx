import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import UserForm from './UserForm'
import adminApi from '@/admin/services/adminApi'

const navigateMock = vi.fn()

vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>()
  return { ...actual, useNavigate: () => navigateMock }
})

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

vi.mock('@/admin/services/adminApi', () => ({
  default: {
    users: { getById: vi.fn(), create: vi.fn(), update: vi.fn() },
    roles: { list: vi.fn() },
  },
}))

/**
 * La invitación de administradores tiene que ser alcanzable desde el panel.
 *
 * El formulario mandaba `sendInvitation` **fijo en 'false'** y exigía una contraseña, así que la única
 * forma de crear un administrador era que quien lo creaba le inventara la clave y se la pasara por fuera —
 * y la invitación, que sí existía en la API, solo se podía usar llamándola a mano. Es la misma forma del
 * fallo que F11 encontró en el formulario de productos con `isOnSale='false'`.
 */
describe('UserForm: invitación', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(adminApi.roles.list).mockResolvedValue([])
    vi.mocked(adminApi.users.create).mockResolvedValue({
      id: 1, name: 'Invitado', email: 'invitado@demo.local', phone: null, active: true,
      roles: [], permissions: [], avatar: null, notes: null, lastLoginAt: null, createdAt: null,
    } as never)
  })

  function renderForm() {
    return render(
      <MemoryRouter initialEntries={['/admin/users/new']}>
        <UserForm />
      </MemoryRouter>,
    )
  }

  it('al crear, la invitación viene activada y no se pide contraseña', async () => {
    renderForm()

    await screen.findByLabelText('Enviar invitación por correo')
    // El valor por defecto es lo que hace el trabajo: el camino en el que nadie más conoce la clave es el
    // que ocurre salvo que alguien lo desactive a propósito.
    expect(screen.queryByLabelText('Contraseña')).not.toBeInTheDocument()
  })

  it('manda sendInvitation=true y ninguna contraseña', async () => {
    const user = userEvent.setup()
    renderForm()

    await user.type(await screen.findByLabelText('Nombre'), 'Invitado')
    await user.type(screen.getByLabelText('Correo electrónico'), 'invitado@demo.local')
    await user.click(screen.getByRole('button', { name: 'Guardar' }))

    await waitFor(() => expect(adminApi.users.create).toHaveBeenCalled())
    const data = vi.mocked(adminApi.users.create).mock.calls[0][0] as FormData
    // Antes esto era 'false' siempre.
    expect(data.get('sendInvitation')).toBe('true')
    expect(data.get('password')).toBeNull()
  })

  it('al desactivarla reaparece la contraseña y se manda', async () => {
    const user = userEvent.setup()
    renderForm()

    await user.click(await screen.findByLabelText('Enviar invitación por correo'))

    // La vía directa se conserva: hace falta para cuando el correo no funciona.
    const password = screen.getByLabelText('Contraseña')
    await user.type(await screen.findByLabelText('Nombre'), 'Directo')
    await user.type(screen.getByLabelText('Correo electrónico'), 'directo@demo.local')
    await user.type(password, 'clave-directa-123')
    await user.click(screen.getByRole('button', { name: 'Guardar' }))

    await waitFor(() => expect(adminApi.users.create).toHaveBeenCalled())
    const data = vi.mocked(adminApi.users.create).mock.calls[0][0] as FormData
    expect(data.get('sendInvitation')).toBe('false')
    expect(data.get('password')).toBe('clave-directa-123')
  })
})
