import { beforeEach, describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import RequireAdminAuth from './RequireAdminAuth'
import { useAdminAuthStore } from '@/admin/stores/useAdminAuthStore'

function renderGuarded(initialEntry = '/admin') {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <Routes>
        <Route path="/admin/login" element={<p>Login page</p>} />
        <Route
          path="/admin"
          element={
            <RequireAdminAuth>
              <p>Contenido protegido</p>
            </RequireAdminAuth>
          }
        />
      </Routes>
    </MemoryRouter>,
  )
}

describe('RequireAdminAuth', () => {
  beforeEach(() => {
    useAdminAuthStore.setState({ token: null, user: null })
  })

  it('redirects to the login page when there is no token', () => {
    renderGuarded()

    expect(screen.getByText('Login page')).toBeInTheDocument()
    expect(screen.queryByText('Contenido protegido')).not.toBeInTheDocument()
  })

  it('renders the protected content when a token is present', () => {
    useAdminAuthStore.setState({ token: 'jwt-token', user: { id: 1, name: 'Admin Demo', email: 'admin@demo.local', permissions: [] } })

    renderGuarded()

    expect(screen.getByText('Contenido protegido')).toBeInTheDocument()
  })
})
