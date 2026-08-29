import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { CartCalculationResult, Product } from '@/types/api'

vi.mock('@/services/api', () => ({
  default: {
    cart: {
      calculate: vi.fn(),
      validate: vi.fn(),
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
  price: 1000,
  formattedPrice: '$1.000',
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

const calculationResult: CartCalculationResult = {
  subtotalWithoutTax: 1000,
  subtotalWithTax: 1190,
  shippingCost: 0,
  taxAmount: 190,
  discountAmount: 0,
  total: 1190,
  pricesIncludeTax: false,
  taxRate: 19,
  freeShippingThreshold: null,
  shippingEnabled: false,
}

describe('useCartStore', () => {
  beforeEach(async () => {
    localStorage.clear()
    vi.clearAllMocks()
    const api = (await import('@/services/api')).default
    vi.mocked(api.cart.calculate).mockResolvedValue(calculationResult)
    const { useCartStore } = await import('./useCartStore')
    useCartStore.setState({ items: [], totals: useCartStore.getInitialState().totals })
  })

  it('adds a new product to an empty cart', async () => {
    const { useCartStore } = await import('./useCartStore')
    useCartStore.getState().addItem(product)

    expect(useCartStore.getState().items).toHaveLength(1)
    expect(useCartStore.getState().items[0]).toMatchObject({ id: 1, type: 'product', quantity: 1 })
  })

  it('increments the quantity when the same product is added again', async () => {
    const { useCartStore } = await import('./useCartStore')
    useCartStore.getState().addItem(product)
    useCartStore.getState().addItem(product, 2)

    expect(useCartStore.getState().items).toHaveLength(1)
    expect(useCartStore.getState().items[0].quantity).toBe(3)
  })

  it('updates the quantity of an existing line', async () => {
    const { useCartStore } = await import('./useCartStore')
    useCartStore.getState().addItem(product)
    useCartStore.getState().updateQuantity(product.id, 'product', 5)

    expect(useCartStore.getState().items[0].quantity).toBe(5)
  })

  it('removes a line from the cart', async () => {
    const { useCartStore } = await import('./useCartStore')
    useCartStore.getState().addItem(product)
    useCartStore.getState().removeItem(product.id, 'product')

    expect(useCartStore.getState().items).toHaveLength(0)
  })

  it('clears the cart and resets totals', async () => {
    const { useCartStore, selectItemCount } = await import('./useCartStore')
    useCartStore.getState().addItem(product, 3)
    useCartStore.getState().clearCart()

    expect(useCartStore.getState().items).toHaveLength(0)
    expect(selectItemCount(useCartStore.getState())).toBe(0)
  })

  it('persists items to localStorage on every mutation', async () => {
    const { useCartStore } = await import('./useCartStore')
    useCartStore.getState().addItem(product, 2)

    const stored = JSON.parse(localStorage.getItem('uvostore_cart') ?? '[]')
    expect(stored).toHaveLength(1)
    expect(stored[0].quantity).toBe(2)
  })

  it('recalculates totals from the API after adding an item', async () => {
    const api = (await import('@/services/api')).default
    const { useCartStore } = await import('./useCartStore')

    useCartStore.getState().addItem(product)
    await vi.waitFor(() => expect(api.cart.calculate).toHaveBeenCalled())

    expect(useCartStore.getState().totals.total).toBe(1190)
  })

  it('selectItemCount sums quantities across all lines', async () => {
    const { useCartStore, selectItemCount } = await import('./useCartStore')
    useCartStore.getState().addItem(product, 2)
    useCartStore.setState((state) => ({
      items: [...state.items, { id: 2, type: 'product', product: { ...product, id: 2 }, variation: null, quantity: 4 }],
    }))

    expect(selectItemCount(useCartStore.getState())).toBe(6)
  })
})
