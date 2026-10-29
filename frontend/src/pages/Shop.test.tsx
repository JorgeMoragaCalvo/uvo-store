import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import Shop from './Shop'
import { useProductsStore } from '@/stores/useProductsStore'
import { useStoreSettingsStore } from '@/stores/useStoreSettingsStore'

/**
 * Bajo lg los filtros van encima de los productos. Cerrados por defecto: abiertos empujaban el catálogo
 * más de una pantalla hacia abajo en el celular. En escritorio la clase lg:block los muestra siempre
 * (jsdom no aplica media queries, así que aquí se afirma solo el estado del botón y la clase base).
 */
function renderShop() {
  return render(
    <MemoryRouter initialEntries={['/shop']}>
      <Shop />
    </MemoryRouter>,
  )
}

describe('Shop filters', () => {
  beforeEach(() => {
    useStoreSettingsStore.setState(useStoreSettingsStore.getInitialState())
    useProductsStore.setState({ products: [], totalPages: 0, page: 1, loading: false, search: vi.fn() })
  })

  it('starts with the filters collapsed on small screens', () => {
    renderShop()

    expect(screen.getByRole('button', { name: 'Filtros' })).toHaveAttribute('aria-expanded', 'false')
    expect(document.getElementById('shop-filters')).toHaveClass('hidden', 'lg:block')
  })

  it('shows and hides the filters with the button', async () => {
    const user = userEvent.setup()
    renderShop()

    await user.click(screen.getByRole('button', { name: 'Filtros' }))
    expect(screen.getByRole('button', { name: 'Ocultar filtros' })).toHaveAttribute('aria-expanded', 'true')
    expect(document.getElementById('shop-filters')).not.toHaveClass('hidden')

    await user.click(screen.getByRole('button', { name: 'Ocultar filtros' }))
    expect(document.getElementById('shop-filters')).toHaveClass('hidden')
  })
})
