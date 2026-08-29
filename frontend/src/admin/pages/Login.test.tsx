import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import Login from './Login'
import { useAdminAuthStore } from '@/admin/stores/useAdminAuthStore'
import adminApi from '@/admin/services/adminApi'

vi.mock('@/admin/services/adminApi', () => ({
  default: {
    auth: {
      login: vi.fn(),
    },
  },
}))

describe('admin Login page', () => {
  beforeEach(() => {
    localStorage.clear()
    useAdminAuthStore.setState({ token: null, user: null })
    vi.clearAllMocks()
  })

  it('logs in and stores the session on successful submit', async () => {
    vi.mocked(adminApi.auth.login).mockResolvedValue({
      token: 'jwt-token',
      id: 1,
      name: 'Admin Demo',
      email: 'admin@demo.local',
      type: 'ADMIN',
    })

    const user = userEvent.setup()
    render(
      <MemoryRouter initialEntries={['/admin/login']}>
        <Login />
      </MemoryRouter>,
    )

    await user.type(screen.getByLabelText(/correo electrónico/i), 'admin@demo.local')
    await user.type(screen.getByLabelText(/contraseña/i), 'admin12345')
    await user.click(screen.getByRole('button', { name: /ingresar/i }))

    await waitFor(() => expect(adminApi.auth.login).toHaveBeenCalledWith('admin@demo.local', 'admin12345'))
    await waitFor(() => expect(useAdminAuthStore.getState().token).toBe('jwt-token'))
  })

  it('shows an error message when login fails', async () => {
    vi.mocked(adminApi.auth.login).mockRejectedValue({ message: 'Credenciales inválidas' })

    const user = userEvent.setup()
    render(
      <MemoryRouter initialEntries={['/admin/login']}>
        <Login />
      </MemoryRouter>,
    )

    await user.type(screen.getByLabelText(/correo electrónico/i), 'admin@demo.local')
    await user.type(screen.getByLabelText(/contraseña/i), 'wrong-password')
    await user.click(screen.getByRole('button', { name: /ingresar/i }))

    expect(await screen.findByText('Credenciales inválidas')).toBeInTheDocument()
    expect(useAdminAuthStore.getState().token).toBeNull()
  })
})
