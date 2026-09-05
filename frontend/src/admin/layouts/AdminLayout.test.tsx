import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import AdminLayout from './AdminLayout'
import { useAdminAuthStore } from '@/admin/stores/useAdminAuthStore'

vi.mock('@/components/ui/sonner', () => ({ Toaster: () => null }))

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
