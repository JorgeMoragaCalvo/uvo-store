import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import ProductForm from './ProductForm'
import adminApi from '@/admin/services/adminApi'
import type { ProductDto, ProductImageDto } from '@/admin/types/admin'

const navigateMock = vi.fn()

vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>()
  return { ...actual, useNavigate: () => navigateMock }
})

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

vi.mock('@/admin/services/adminApi', () => ({
  default: {
    products: {
      getById: vi.fn(),
      create: vi.fn(),
      update: vi.fn(),
      removeImage: vi.fn(),
    },
    categories: {
      list: vi.fn(),
    },
  },
}))

function image(overrides: Partial<ProductImageDto> = {}): ProductImageDto {
  return {
    id: 10,
    url: 'http://demo.localhost/uploads/products/portada.png',
    thumbnail: 'http://demo.localhost/uploads/products/portada.png',
    alt: 'Producto',
    isFeatured: true,
    ...overrides,
  }
}

function product(overrides: Partial<ProductDto> = {}): ProductDto {
  return {
    id: 7,
    name: 'Producto con foto',
    slug: 'producto-con-foto',
    shortDescription: null,
    description: null,
    productType: 'simple',
    sku: 'SKU-7',
    price: 19990,
    formattedPrice: '$19.990',
    stock: 5,
    inStock: true,
    manageStock: true,
    featuredImage: 'http://demo.localhost/uploads/products/portada.png',
    images: [image()],
    active: true,
    featured: false,
    metaTitle: null,
    metaDescription: null,
    category: null,
    variations: [],
    variationsCount: 0,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

function renderForm(initialEntry: string) {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <Routes>
        <Route path="/admin/products/new" element={<ProductForm />} />
        <Route path="/admin/products/:id/edit" element={<ProductForm />} />
      </Routes>
    </MemoryRouter>,
  )
}

// The regression this file exists for: editing a product used to render the gallery read-only, with
// no file inputs at all, and buildFormData dropped the files on the update path — so a wrong photo
// could only be fixed by deleting the product and creating it again.
describe('ProductForm', () => {
  beforeEach(() => {
    navigateMock.mockClear()
    vi.clearAllMocks()
    vi.mocked(adminApi.categories.list).mockResolvedValue([])
  })

  it('lets you upload a photo while editing, and sends it in the update', async () => {
    vi.mocked(adminApi.products.getById).mockResolvedValue(product())
    vi.mocked(adminApi.products.update).mockResolvedValue(product())

    const user = userEvent.setup()
    renderForm('/admin/products/7/edit')

    const galleryInput = await screen.findByLabelText(/añadir a galería/i)
    const file = new File(['bytes'], 'nueva.png', { type: 'image/png' })
    await user.upload(galleryInput, file)
    await user.click(screen.getByRole('button', { name: /^guardar$/i }))

    expect(adminApi.products.update).toHaveBeenCalledTimes(1)
    const [id, formData] = vi.mocked(adminApi.products.update).mock.calls[0]
    expect(id).toBe(7)
    expect(formData.getAll('images')).toHaveLength(1)
    expect((formData.getAll('images')[0] as File).name).toBe('nueva.png')
  })

  it('offers to replace the featured image when the product already has one', async () => {
    vi.mocked(adminApi.products.getById).mockResolvedValue(product())

    renderForm('/admin/products/7/edit')

    expect(await screen.findByLabelText(/reemplazar destacada/i)).toBeInTheDocument()
    // Exact match: a loose /destacada/i would also hit the "Reemplazar destacada" label.
    expect(screen.getByText('Destacada')).toBeInTheDocument()
  })

  it('removes an image and re-renders the gallery from the response', async () => {
    vi.mocked(adminApi.products.getById).mockResolvedValue(
      product({ images: [image(), image({ id: 11, isFeatured: false })] }),
    )
    vi.mocked(adminApi.products.removeImage).mockResolvedValue(
      product({ images: [image()] }),
    )

    const user = userEvent.setup()
    renderForm('/admin/products/7/edit')

    const removeButtons = await screen.findAllByRole('button', { name: /quitar/i })
    expect(removeButtons).toHaveLength(2)
    await user.click(removeButtons[1])

    expect(adminApi.products.removeImage).toHaveBeenCalledWith(7, 11)
    await vi.waitFor(() =>
      expect(screen.getAllByRole('button', { name: /quitar/i })).toHaveLength(1),
    )
  })

  it('still sends the files when creating', async () => {
    vi.mocked(adminApi.products.create).mockResolvedValue(product())

    const user = userEvent.setup()
    renderForm('/admin/products/new')

    await user.type(screen.getByLabelText(/^nombre$/i), 'Producto nuevo')
    const featuredInput = screen.getByLabelText(/imagen destacada/i)
    await user.upload(featuredInput, new File(['bytes'], 'portada.png', { type: 'image/png' }))
    await user.click(screen.getByRole('button', { name: /^guardar$/i }))

    const [formData] = vi.mocked(adminApi.products.create).mock.calls[0]
    expect((formData.get('featuredImage') as File).name).toBe('portada.png')
  })
})
