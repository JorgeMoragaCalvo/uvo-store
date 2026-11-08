import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import AdminLayout from './AdminLayout'
import { useAdminAuthStore } from '@/admin/stores/useAdminAuthStore'
import { adminApi } from '@/admin/services/adminApi'

vi.mock('@/components/ui/sonner', () => ({ Toaster: () => null }))
vi.mock('@/admin/services/adminApi', () => ({ adminApi: { auth: { logout: vi.fn() } } }))

// A1: the panel used to render all 16 nav entries for anyone holding a token, and typing a URL got
// you the page regardless of role. Cosmetic on its own — the endpoints are enforced with
// @PreAuthorize — but without it a restricted admin sees a menu full of sections that 403.
function renderAt(path: string, permissions: string[]) {
  useAdminAuthStore.setState({
    token: 'jwt',
    user: { id: 1, name: 'Admin', email: 'admin@test.local', permissions },
  })

  const router = createMemoryRouter(
    [
      {
        path: '/admin',
        element: <AdminLayout />,
        children: [
          { index: true, element: <p>Dashboard</p> },
          { path: 'products', element: <p>Listado de productos</p>, handle: { permission: 'products.view' } },
          { path: 'users', element: <p>Listado de usuarios</p>, handle: { permission: 'users.view' } },
        ],
      },
      { path: '/admin/login', element: <p>Pantalla de login</p> },
    ],
    { initialEntries: [path] },
  )
  return render(<RouterProvider router={router} />)
}

describe('AdminLayout permissions', () => {
  beforeEach(() => {
    useAdminAuthStore.setState({ token: null, user: null })
  })

  it('only shows the sections the role grants', () => {
    renderAt('/admin', ['products.view'])

    expect(screen.getByRole('link', { name: /productos/i })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /^usuarios$/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /roles/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /pasarelas/i })).not.toBeInTheDocument()
  })

  it('always shows the dashboard, whatever the role', () => {
    renderAt('/admin', [])

    expect(screen.getByRole('link', { name: /dashboard/i })).toBeInTheDocument()
  })

  it('renders the section when the permission is held', () => {
    renderAt('/admin/products', ['products.view'])

    expect(screen.getByText('Listado de productos')).toBeInTheDocument()
  })

  it('refuses a section reached by typing its URL without the permission', () => {
    renderAt('/admin/users', ['products.view'])

    expect(screen.queryByText('Listado de usuarios')).not.toBeInTheDocument()
    expect(screen.getByText(/no tienes permiso/i)).toBeInTheDocument()
  })

  it('shows a full-access admin everything', () => {
    renderAt('/admin', ['products.view', 'users.view', 'roles.view', 'payments.view'])

    expect(screen.getByRole('link', { name: /productos/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /^usuarios$/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /roles/i })).toBeInTheDocument()
  })
})

// PROD-24b.
describe('AdminLayout accessibility', () => {
  it('titles the tab after the current section', () => {
    renderAt('/admin/products', ['products.view'])

    expect(document.title).toBe('Productos · Panel de administración')
  })

  it('offers a skip link to the content and names the mobile menu button', () => {
    renderAt('/admin', [])

    expect(screen.getByRole('link', { name: 'Saltar al contenido' })).toHaveAttribute('href', '#admin-main')
    expect(screen.getByRole('button', { name: 'Abrir menú' })).toBeInTheDocument()
  })

  it('closes the mobile menu when a section is picked', async () => {
    const user = userEvent.setup()
    renderAt('/admin', ['products.view'])

    await user.click(screen.getByRole('button', { name: 'Abrir menú' }))
    const menu = await screen.findByRole('dialog', { name: 'UvoStore Admin' })
    await user.click(within(menu).getByRole('link', { name: /productos/i }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(screen.getByText('Listado de productos')).toBeInTheDocument()
  })
})

// GAP-13. «Salir» revoca el token en el servidor antes de borrarlo del navegador.
describe('AdminLayout logout', () => {
  it('waits for the server to revoke the token before clearing the session', async () => {
    let revoke: () => void = () => {}
    vi.mocked(adminApi.auth.logout).mockReturnValue(new Promise<void>((resolve) => { revoke = resolve }))
    const user = userEvent.setup()
    renderAt('/admin', [])

    await user.click(screen.getByRole('button', { name: 'Salir' }))

    // Mientras el servidor no contesta, la sesión sigue: un login nuevo antes de la revocación sería revocado.
    expect(adminApi.auth.logout).toHaveBeenCalledTimes(1)
    expect(useAdminAuthStore.getState().token).toBe('jwt')
    expect(screen.queryByText('Pantalla de login')).not.toBeInTheDocument()

    revoke()

    expect(await screen.findByText('Pantalla de login')).toBeInTheDocument()
    expect(useAdminAuthStore.getState().token).toBeNull()
  })

  it('still logs out locally when the server call fails', async () => {
    vi.mocked(adminApi.auth.logout).mockRejectedValue({ message: 'Sin conexión' })
    const user = userEvent.setup()
    renderAt('/admin', [])

    await user.click(screen.getByRole('button', { name: 'Salir' }))

    expect(await screen.findByText('Pantalla de login')).toBeInTheDocument()
    expect(useAdminAuthStore.getState().token).toBeNull()
    expect(useAdminAuthStore.getState().user).toBeNull()
  })
})
