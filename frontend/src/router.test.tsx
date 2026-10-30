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
