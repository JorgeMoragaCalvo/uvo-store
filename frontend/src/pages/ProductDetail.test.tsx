import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import ProductDetail from './ProductDetail'
import { useCartStore } from '@/stores/useCartStore'
import type { Product, ProductVariation } from '@/types/api'

const navigateMock = vi.fn()

vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>()
  return { ...actual, useNavigate: () => navigateMock }
})

vi.mock('@/services/api', () => ({
  default: {
    products: {
      getBySlug: vi.fn(),
      getRelated: vi.fn(),
    },
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

const simpleProduct: Product = {
  id: 1,
  name: 'Producto simple',
  slug: 'producto-simple',
  shortDescription: null,
  description: 'Descripción larga',
  productType: 'simple',
  sku: 'SKU-1',
  price: 5000,
  formattedPrice: '$5.000',
  stock: 3,
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

const variation: ProductVariation = {
  id: 10,
  productId: 2,
  sku: 'SKU-2-ROJO',
  price: 6000,
  compareAtPrice: null,
  formattedPrice: '$6.000',
  stock: 5,
  inStock: true,
  weight: null,
  image: null,
  active: true,
  attributes: { Color: 'Rojo' },
  attributeIds: { Color: 1 },
  createdAt: '2026-01-01T00:00:00Z',
}

const variableProduct: Product = {
  ...simpleProduct,
  id: 2,
  slug: 'producto-variable',
  name: 'Producto variable',
  productType: 'variable',
  variations: [variation],
}

function renderAt(slug: string) {
  return render(
    <MemoryRouter initialEntries={[`/product/${slug}`]}>
      <Routes>
        <Route path="/product/:slug" element={<ProductDetail />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('ProductDetail page', () => {
  beforeEach(() => {
    localStorage.clear()
    navigateMock.mockClear()
    useCartStore.setState({ items: [], totals: useCartStore.getInitialState().totals })
  })

  it('shows a not-found message when the product fails to load', async () => {
    const api = (await import('@/services/api')).default
    vi.mocked(api.products.getBySlug).mockRejectedValue(new Error('404'))
    vi.mocked(api.products.getRelated).mockResolvedValue([])

    renderAt('no-existe')

    expect(await screen.findByText('Producto no encontrado.')).toBeInTheDocument()
  })

  it('adds a simple product to the cart with the selected quantity', async () => {
    const api = (await import('@/services/api')).default
    vi.mocked(api.products.getBySlug).mockResolvedValue(simpleProduct)
    vi.mocked(api.products.getRelated).mockResolvedValue([])

    const user = userEvent.setup()
    renderAt(simpleProduct.slug)

    await screen.findByText(simpleProduct.name)
    await user.click(screen.getByRole('button', { name: '+' }))
    await user.click(screen.getByRole('button', { name: /agregar al carrito/i }))

    expect(useCartStore.getState().items).toHaveLength(1)
    expect(useCartStore.getState().items[0].quantity).toBe(2)
  })

  it('navigates to checkout after "Comprar Ahora"', async () => {
    const api = (await import('@/services/api')).default
    vi.mocked(api.products.getBySlug).mockResolvedValue(simpleProduct)
    vi.mocked(api.products.getRelated).mockResolvedValue([])

    const user = userEvent.setup()
    renderAt(simpleProduct.slug)

    await screen.findByText(simpleProduct.name)
    await user.click(screen.getByRole('button', { name: /comprar ahora/i }))

    expect(useCartStore.getState().items).toHaveLength(1)
    expect(navigateMock).toHaveBeenCalledWith('/checkout')
  })

  it('requires selecting a matching attribute before a variable product can be added', async () => {
    const api = (await import('@/services/api')).default
    vi.mocked(api.products.getBySlug).mockResolvedValue(variableProduct)
    vi.mocked(api.products.getRelated).mockResolvedValue([])

    const user = userEvent.setup()
    renderAt(variableProduct.slug)

    await screen.findByText(variableProduct.name)
    const addButton = screen.getByRole('button', { name: /agregar al carrito/i })
    expect(addButton).toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Rojo' }))
    await waitFor(() => expect(addButton).toBeEnabled())

    await user.click(addButton)
    expect(useCartStore.getState().items[0].variation?.id).toBe(variation.id)
  })
})
