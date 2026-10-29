import { beforeEach, describe, expect, it } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import Header from './Header'
import { useCartStore } from '@/stores/useCartStore'
import { useStoreSettingsStore } from '@/stores/useStoreSettingsStore'

/**
 * PROD-07. El storefront no ofrece ninguna entrada a una cuenta de comprador.
 *
 * Aquí había un 👤 «Cuenta (próximamente)»: un icono inerte que no llevaba a ninguna parte, porque la
 * SPA no tiene ruta /cuenta/* ni sesión de comprador. El plan de producción pide que una función
 * diferida se oculte en sus puntos de entrada, y este era el único del storefront.
 *
 * Los dos vecinos se afirman en el mismo test a propósito: lo que se quitó es el acceso a la cuenta, no
 * media barra.
 */
function renderHeader() {
  return render(
    <MemoryRouter>
      <Header />
    </MemoryRouter>,
  )
}

describe('Header', () => {
  beforeEach(() => {
    useStoreSettingsStore.setState(useStoreSettingsStore.getInitialState())
    useCartStore.setState({ items: [] })
  })

  it('does not offer any customer account entry point', () => {
    renderHeader()

    expect(screen.queryByText('👤')).not.toBeInTheDocument()
    expect(screen.queryByTitle(/cuenta/i)).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /cuenta/i })).not.toBeInTheDocument()
  })

  it('keeps the cart and the search box', () => {
    renderHeader()

    expect(screen.getByRole('button', { name: /abrir carrito/i })).toBeInTheDocument()
    expect(screen.getByPlaceholderText(/buscar productos/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /tienda/i })).toBeInTheDocument()
  })

  // Bajo lg la navegación de escritorio se oculta; el menú es la única forma de llegar a la tienda,
  // las categorías y el buscador desde el celular.
  it('offers the navigation and the search box in the mobile menu', async () => {
    const user = userEvent.setup()
    renderHeader()

    await user.click(screen.getByRole('button', { name: /abrir menú/i }))

    const menu = screen.getByRole('dialog')
    expect(within(menu).getByRole('link', { name: 'Tienda' })).toHaveAttribute('href', '/shop')
    expect(within(menu).getByRole('link', { name: 'Ofertas' })).toBeInTheDocument()
    expect(within(menu).getByRole('searchbox', { name: /buscar productos/i })).toBeInTheDocument()
  })

  it('closes the mobile menu when a link is followed', async () => {
    const user = userEvent.setup()
    renderHeader()

    await user.click(screen.getByRole('button', { name: /abrir menú/i }))
    await user.click(within(screen.getByRole('dialog')).getByRole('link', { name: 'Tienda' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})
