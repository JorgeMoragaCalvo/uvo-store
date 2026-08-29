import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import ProductCard from './ProductCard'
import { useCartStore } from '@/stores/useCartStore'
import { useNotificationStore } from '@/stores/useNotificationStore'
import type { Product } from '@/types/api'

vi.mock('@/services/api', () => ({
  default: {
    cart: {
      calculate: vi.fn().mockResolvedValue({
        subtotalWithoutTax: 0,
        subtotalWithTax: 0,
        shippingCost: 0,
        taxAmount: 0,
        discountAmount: 0,
        total: 0,
        pricesIncludeTax: false,
        taxRate: 0,
        freeShippingThreshold: null,
        shippingEnabled: false,
      }),
    },
  },
}))

const baseProduct: Product = {
  id: 1,
  name: 'Producto simple',
  slug: 'producto-simple',
  shortDescription: 'Una breve descripción',
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

function renderCard(product: Product) {
  return render(
    <MemoryRouter>
      <ProductCard product={product} />
    </MemoryRouter>,
  )
}

describe('ProductCard', () => {
  beforeEach(() => {
    localStorage.clear()
    useCartStore.setState({ items: [], totals: useCartStore.getInitialState().totals })
    useNotificationStore.setState({ notifications: [] })
  })

  it('adds a simple product to the cart and shows a notification', async () => {
    const user = userEvent.setup()
    renderCard(baseProduct)

    await user.click(screen.getByRole('button', { name: /agregar/i }))

    expect(useCartStore.getState().items).toHaveLength(1)
    expect(useNotificationStore.getState().notifications.some((n) => n.message.includes(baseProduct.name))).toBe(true)
  })

  it('links to the product page instead of adding to cart for variable products', () => {
    renderCard({ ...baseProduct, productType: 'variable' })

    expect(screen.getByRole('link', { name: /ver opciones/i })).toHaveAttribute('href', '/product/producto-simple')
    expect(screen.queryByRole('button', { name: /agregar/i })).not.toBeInTheDocument()
  })

  it('shows "Sin stock" and no add-to-cart action when out of stock', () => {
    renderCard({ ...baseProduct, inStock: false })

    expect(screen.getByText('Sin stock')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /agregar/i })).not.toBeInTheDocument()
  })
})
