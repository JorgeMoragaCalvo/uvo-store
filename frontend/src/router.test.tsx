import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { routes } from './router'

vi.mock('@/services/api', () => ({
  default: {
    storeSettings: { get: vi.fn().mockResolvedValue({ storeName: 'Tienda Demo', metaTitle: null }) },
    homeBanners: { getAll: vi.fn().mockResolvedValue([]) },
    categories: { getAll: vi.fn().mockResolvedValue([]) },
    cart: { calculate: vi.fn().mockResolvedValue({}) },
  },
}))

// PROD-24b. Una URL desconocida mostraba la página de error de React Router, en inglés y sin la tienda.
describe('storefront routes', () => {
  it('answers an unknown URL with a 404 page inside the store layout', async () => {
    const router = createMemoryRouter(routes, { initialEntries: ['/esta-pagina-no-existe'] })

    render(<RouterProvider router={router} />)

    expect(await screen.findByRole('heading', { name: 'Página no encontrada' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ver productos' })).toHaveAttribute('href', '/shop')
    // Con el header de la tienda alrededor, no a página completa.
    expect(screen.getByRole('button', { name: /abrir carrito/i })).toBeInTheDocument()
    expect(document.title).toBe('Página no encontrada · Tienda Demo')
  })
})

// Las páginas reales no importan aquí, solo lo que se ve mientras baja su chunk.
vi.mock('./pages/Checkout', () => ({ default: () => <h1>Checkout cargado</h1> }))
vi.mock('./admin/pages/Login', () => ({ default: () => <h1>Login cargado</h1> }))

// GAP-20. Al entrar directo a una ruta con carga diferida la página quedaba en blanco, y React Router avisaba
// «No `HydrateFallback` element provided to render during initial hydration» en la consola.
describe('first load of a lazy route', () => {
  const hydrateWarnings = (warn: { mock: { calls: unknown[][] } }) =>
    warn.mock.calls.filter((args) => args.some((arg) => String(arg).includes('HydrateFallback')))

  it('shows a loading indicator inside the store layout while the checkout chunk loads', async () => {
    const warn = vi.spyOn(console, 'warn')
    const router = createMemoryRouter(routes, { initialEntries: ['/checkout'] })

    render(<RouterProvider router={router} />)

    // El primer render, antes de que resuelva `lazy`.
    expect(screen.getByRole('status')).toHaveTextContent('Cargando…')
    expect(screen.getByRole('button', { name: /abrir carrito/i })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Checkout cargado' })).toBeInTheDocument()
    expect(hydrateWarnings(warn)).toEqual([])
    warn.mockRestore()
  })

  it('shows a full-page loading indicator while an admin page loads', async () => {
    const warn = vi.spyOn(console, 'warn')
    const router = createMemoryRouter(routes, { initialEntries: ['/admin/login'] })

    render(<RouterProvider router={router} />)

    expect(screen.getByRole('status')).toHaveTextContent('Cargando…')
    expect(screen.queryByRole('button', { name: /abrir carrito/i })).not.toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Login cargado' })).toBeInTheDocument()
    expect(hydrateWarnings(warn)).toEqual([])
    warn.mockRestore()
  })
})
