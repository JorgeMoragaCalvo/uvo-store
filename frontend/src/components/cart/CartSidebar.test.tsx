import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import CartSidebar from './CartSidebar'
import { useCartStore } from '@/stores/useCartStore'
import type { CartLine } from '@/stores/useCartStore'
import type { Product } from '@/types/api'

vi.mock('@/services/api', () => ({
  default: {
    cart: {
      calculate: vi.fn().mockResolvedValue({
        subtotalWithoutTax: 5000,
        subtotalWithTax: 5000,
        shippingCost: 0,
        taxAmount: 0,
        discountAmount: 0,
        total: 5000,
        pricesIncludeTax: false,
        taxRate: 0,
        freeShippingThreshold: null,
        shippingEnabled: false,
        shippingAvailable: true,
        couponApplied: false,
      }),
    },
  },
}))

const product = {
  id: 1,
  name: 'Producto de prueba',
  slug: 'producto-de-prueba',
  productType: 'simple',
  price: 5000,
  featuredImage: null,
  images: [],
  variations: [],
} as unknown as Product

const line: CartLine = { id: 1, type: 'product', product, variation: null, quantity: 1 }

// PROD-24b. Era un <aside> sobre un <div> clickeable: sin rol de diálogo, sin Escape y sin devolver el foco.
describe('CartSidebar', () => {
  beforeEach(() => {
    localStorage.clear()
    useCartStore.setState({ ...useCartStore.getInitialState(), items: [line], isSidebarOpen: false })
  })

  function renderWithOpener() {
    return render(
      <MemoryRouter>
        <button type="button" onClick={() => useCartStore.getState().openSidebar()}>
          Abrir carrito
        </button>
        <CartSidebar />
      </MemoryRouter>,
    )
  }

  it('opens as a named dialog with the cart lines', async () => {
    const user = userEvent.setup()
    renderWithOpener()

    await user.click(screen.getByRole('button', { name: 'Abrir carrito' }))

    const dialog = await screen.findByRole('dialog', { name: 'Tu carrito' })
    expect(dialog).toHaveTextContent('Producto de prueba')
  })

  it('closes with Escape and gives the focus back to the button that opened it', async () => {
    const user = userEvent.setup()
    renderWithOpener()
    const opener = screen.getByRole('button', { name: 'Abrir carrito' })

    await user.click(opener)
    await screen.findByRole('dialog')
    await user.keyboard('{Escape}')

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(useCartStore.getState().isSidebarOpen).toBe(false)
    expect(opener).toHaveFocus()
  })
})
