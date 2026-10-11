import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import UsersList from './UsersList'
import adminApi from '@/admin/services/adminApi'
import type { AdminUserDto } from '@/admin/types/admin'
import type { Page } from '@/types/api'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

vi.mock('@/admin/services/adminApi', () => ({
  default: {
    users: { list: vi.fn(), delete: vi.fn(), toggleStatus: vi.fn(), resendInvitation: vi.fn() },
  },
}))

function user(overrides: Partial<AdminUserDto> = {}): AdminUserDto {
  return {
    id: 1,
    name: 'Admin Demo',
    email: 'admin@demo.local',
    phone: null,
    avatar: null,
    active: true,
    lastLoginAt: null,
    notes: null,
    roles: [],
    createdAt: '2026-01-01T00:00:00Z',
    invitationPending: false,
    invitationExpiresAt: null,
    ...overrides,
  }
}

function page(content: AdminUserDto[]): Page<AdminUserDto> {
  return { content, totalElements: content.length, totalPages: 1, number: 0, size: 15 }
}

/**
 * PROD-06. El reenvío tiene que ser alcanzable desde el panel, y solo para quien lo necesita.
 *
 * Sin la insignia no hay forma de distinguir a quien nunca ha entrado de quien simplemente no entra hace
 * tiempo — y entonces el botón de reenviar no tendría a quién ofrecerse. Es la misma forma del fallo que
 * F11 encontró en el formulario de productos y que corrigió el de usuarios: una capacidad que existe en la
 * API y que el panel no deja pedir.
 */
describe('UsersList: invitación pendiente', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  function renderList() {
    return render(
      <MemoryRouter>
        <UsersList />
      </MemoryRouter>,
    )
  }

  it('un administrador normal no muestra insignia ni botón de reenvío', async () => {
    vi.mocked(adminApi.users.list).mockResolvedValue(page([user()]))
    renderList()

    await screen.findByText('Admin Demo')
    expect(screen.queryByText('Invitación pendiente')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Reenviar invitación/ })).not.toBeInTheDocument()
  })

  it('un invitado pendiente muestra la insignia y reenvía con su id', async () => {
    const pending = user({
      id: 9,
      email: 'invitado@demo.local',
      invitationPending: true,
      // En el futuro: todavía no ha caducado.
      invitationExpiresAt: new Date(Date.now() + 86_400_000).toISOString(),
    })
    vi.mocked(adminApi.users.list).mockResolvedValue(page([pending]))
    vi.mocked(adminApi.users.resendInvitation).mockResolvedValue(pending)
    renderList()

    expect(await screen.findByText('Invitación pendiente')).toBeInTheDocument()

    await userEvent.setup().click(screen.getByRole('button', { name: 'Reenviar invitación a invitado@demo.local' }))

    await waitFor(() => expect(adminApi.users.resendInvitation).toHaveBeenCalledWith(9))
  })

  it('una invitación pasada de plazo se lee como caducada', async () => {
    // La fecha la calcula el servidor con su propio TTL; aquí solo se compara, para que el panel no lleve
    // una segunda copia del plazo.
    vi.mocked(adminApi.users.list).mockResolvedValue(
      page([user({ invitationPending: true, invitationExpiresAt: '2020-01-01T00:00:00Z' })]),
    )
    renderList()

    expect(await screen.findByText('Invitación caducada')).toBeInTheDocument()
    // Y sigue siendo reenviable: es justamente el caso de uso principal.
    expect(screen.getByRole('button', { name: /Reenviar invitación/ })).toBeEnabled()
  })

  it('un reenvío rechazado muestra el mensaje del servidor', async () => {
    const { toast } = await import('sonner')
    vi.mocked(adminApi.users.list).mockResolvedValue(
      page([user({ id: 9, invitationPending: true, invitationExpiresAt: '2030-01-01T00:00:00Z' })]),
    )
    vi.mocked(adminApi.users.resendInvitation).mockRejectedValue({
      message: 'La invitación se envió hace poco: espera 4 minutos antes de reenviarla',
    })
    renderList()

    await screen.findByText('Invitación pendiente')
    await userEvent.setup().click(screen.getByRole('button', { name: /Reenviar invitación/ }))

    // El mensaje del servidor es el que sirve: dice cuántos minutos faltan.
    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith('La invitación se envió hace poco: espera 4 minutos antes de reenviarla'),
    )
  })
})
