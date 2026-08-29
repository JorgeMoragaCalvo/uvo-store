import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import Cart from './Cart'
import { useCartStore } from '@/stores/useCartStore'
import type { CartLine } from '@/stores/useCartStore'
import type { CartCalculationResult, Product } from '@/types/api'

const { calculationResult } = vi.hoisted(() => ({
  calculationResult: {
    subtotalWithoutTax: 10000,
    subtotalWithTax: 10000,
    shippingCost: 0,
    taxAmount: 0,
    discountAmount: 0,
    total: 10000,
    pricesIncludeTax: false,
    taxRate: 0,
    freeShippingThreshold: null,
    shippingEnabled: false,
  } satisfies CartCalculationResult,
}))

vi.mock('@/services/api', () => ({
  default: {
    cart: {
      calculate: vi.fn().mockResolvedValue(calculationResult),
    },
  },
}))

const product: Product = {
  id: 1,
  name: 'Producto de prueba',
  slug: 'producto-de-prueba',
  shortDescription: null,
  description: null,
  productType: 'simple',
  sku: 'SKU-1',
  price: 5000,
  formattedPrice: '$5.000',
  stock: 10,
  inStock: true,
  manageStock: true,
  featuredImage: null,
  images: [],
  active: true,
  featured: false,
  metaTitle: null,
  metaDescription: null,
  category: null,
  variations: [],
  variationsCount: null,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

const line: CartLine = { id: 1, type: 'product', product, variation: null, quantity: 2 }

function renderCart() {
  return render(
    <MemoryRouter>
      <Cart />
    </MemoryRouter>,
  )
}

describe('Cart page', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('shows an empty-cart message with a link back to the shop', () => {
    useCartStore.setState({ items: [], totals: useCartStore.getInitialState().totals })

    renderCart()

    expect(screen.getByText('Tu carrito está vacío')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /ir a la tienda/i })).toHaveAttribute('href', '/shop')
  })

  it('lists cart lines and the order summary totals', () => {
    useCartStore.setState({ items: [line], totals: { ...useCartStore.getInitialState().totals, total: 10000 } })

    renderCart()

    expect(screen.getByText(product.name)).toBeInTheDocument()
    expect(screen.getAllByText('$10.000')).toHaveLength(2) // line subtotal + order total
    expect(screen.getByRole('link', { name: /finalizar compra/i })).toHaveAttribute('href', '/checkout')
  })

  it('removing the only line brings back the empty-cart state', async () => {
    useCartStore.setState({ items: [line], totals: { ...useCartStore.getInitialState().totals, total: 10000 } })
    const user = userEvent.setup()

    renderCart()
    await user.click(screen.getByTitle('Eliminar'))

    expect(screen.getByText('Tu carrito está vacío')).toBeInTheDocument()
  })
})
