import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { toast } from 'sonner'
import ProductForm from './ProductForm'
import adminApi from '@/admin/services/adminApi'
import type { PosOwnedField, ProductDto, ProductImageDto } from '@/admin/types/admin'

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
      posOwnership: vi.fn(),
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
    vi.mocked(adminApi.products.posOwnership).mockResolvedValue({ lockedFields: [] })
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

// POS-2. Lo que gestiona UvoPOS sale bloqueado y no se envía: el backend lo conserva. Reenviar el valor leído
// al cargar la página haría que un stock movido por una venta se rechazara como un cambio que nadie hizo.
describe('ProductForm with a product synced with UvoPOS', () => {
  beforeEach(() => {
    navigateMock.mockClear()
    vi.clearAllMocks()
    vi.mocked(adminApi.categories.list).mockResolvedValue([])
    vi.mocked(adminApi.products.getById).mockResolvedValue(product({ description: 'Del POS' }))
    vi.mocked(adminApi.products.update).mockResolvedValue(product())
  })

  function lock(...fields: PosOwnedField[]) {
    vi.mocked(adminApi.products.posOwnership).mockResolvedValue({ lockedFields: fields })
  }

  async function save() {
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: /^guardar$/i }))
    return vi.mocked(adminApi.products.update).mock.calls[0][1]
  }

  it('locks the fields UvoPOS manages and leaves them out of the update', async () => {
    lock('sku', 'price', 'stock', 'manageStock')

    const user = userEvent.setup()
    renderForm('/admin/products/7/edit')

    const shortDescription = await screen.findByLabelText(/^descripción corta$/i)
    expect(screen.getByLabelText(/^sku$/i)).toBeDisabled()
    expect(screen.getByLabelText(/^precio$/i)).toBeDisabled()
    expect(screen.getByLabelText(/^stock$/i)).toBeDisabled()
    expect(screen.getByRole('switch', { name: /gestionar stock/i })).toBeDisabled()
    expect(screen.getAllByText('Lo gestiona UvoPOS')).toHaveLength(4)
    expect(screen.getByLabelText(/^nombre$/i)).toBeEnabled()

    await user.type(shortDescription, 'Texto comercial')
    const formData = await save()

    for (const field of ['sku', 'price', 'stock', 'manageStock']) {
      expect(formData.has(field), field).toBe(false)
    }
    expect(formData.get('shortDescription')).toBe('Texto comercial')
    expect(formData.get('isFeatured')).toBe('false')
    expect(formData.get('name')).toBe('Producto con foto')
  })

  it('locks the name and the description only when their flags are on', async () => {
    lock('name', 'description', 'sku')

    renderForm('/admin/products/7/edit')

    expect(await screen.findByLabelText(/^nombre$/i)).toBeDisabled()
    expect(screen.getByLabelText(/^descripción$/i)).toBeDisabled()
    expect(screen.getByLabelText(/^precio$/i)).toBeEnabled()

    const formData = await save()
    expect(formData.has('name')).toBe(false)
    expect(formData.has('description')).toBe(false)
    expect(formData.get('price')).toBe('19990')
  })

  it('leaves a product without a mapping fully editable', async () => {
    lock()

    renderForm('/admin/products/7/edit')

    expect(await screen.findByLabelText(/^precio$/i)).toBeEnabled()
    expect(screen.queryByText('Lo gestiona UvoPOS')).not.toBeInTheDocument()

    const formData = await save()
    for (const field of ['name', 'description', 'sku', 'price', 'stock', 'manageStock']) {
      expect(formData.has(field), field).toBe(true)
    }
  })

  it('does not ask about UvoPOS when creating a product', async () => {
    renderForm('/admin/products/new')

    expect(await screen.findByLabelText(/^precio$/i)).toBeEnabled()
    expect(adminApi.products.posOwnership).not.toHaveBeenCalled()
  })

  it('shows the backend message when the update is rejected', async () => {
    lock('price')
    const message = 'Este producto se sincroniza con UvoPOS: el precio se cambia en el POS, no en el panel.'
    vi.mocked(adminApi.products.update).mockRejectedValue({ message })

    renderForm('/admin/products/7/edit')
    await screen.findByLabelText(/^precio$/i)
    await save()

    await vi.waitFor(() => expect(toast.error).toHaveBeenCalledWith(message))
  })

  it('falls back to no locks if it cannot ask, and says so', async () => {
    // El backend sigue rechazando los cambios a lo que es del POS; el formulario solo pierde el aviso previo.
    vi.mocked(adminApi.products.posOwnership).mockRejectedValue({ message: 'boom' })

    renderForm('/admin/products/7/edit')

    expect(await screen.findByLabelText(/^precio$/i)).toBeEnabled()
    expect(toast.error).toHaveBeenCalledWith('No se pudo saber qué campos gestiona UvoPOS')
  })
})
