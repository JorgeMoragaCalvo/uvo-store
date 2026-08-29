import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Page, Product } from '@/types/api'

vi.mock('@/services/api', () => ({
  default: {
    products: {
      getAll: vi.fn(),
    },
  },
}))

function product(overrides: Partial<Product> = {}): Product {
  return {
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
    ...overrides,
  }
}

function page(content: Product[], number = 0, totalPages = 1): Page<Product> {
  return { content, totalElements: content.length, totalPages, number, size: 12 }
}

describe('useProductsStore', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    const { useProductsStore } = await import('./useProductsStore')
    useProductsStore.setState({ products: [], totalElements: 0, totalPages: 0, page: 1, loading: false, error: null })
  })

  it('stores the results and converts the 0-based page to 1-based', async () => {
    const api = (await import('@/services/api')).default
    vi.mocked(api.products.getAll).mockResolvedValue(page([product()], 2, 5))
    const { useProductsStore } = await import('./useProductsStore')

    await useProductsStore.getState().search({ page: 3 })

    expect(useProductsStore.getState().products).toHaveLength(1)
    expect(useProductsStore.getState().page).toBe(3)
    expect(useProductsStore.getState().totalPages).toBe(5)
    expect(useProductsStore.getState().loading).toBe(false)
  })

  it('sets an error message and stops loading when the search fails', async () => {
    const api = (await import('@/services/api')).default
    vi.mocked(api.products.getAll).mockRejectedValue({ message: 'No se pudo buscar' })
    const { useProductsStore } = await import('./useProductsStore')

    await useProductsStore.getState().search({})

    expect(useProductsStore.getState().error).toBe('No se pudo buscar')
    expect(useProductsStore.getState().loading).toBe(false)
  })
})
