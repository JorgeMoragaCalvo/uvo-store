import { beforeEach, describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
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
})
