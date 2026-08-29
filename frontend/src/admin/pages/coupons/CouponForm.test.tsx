import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { toast } from 'sonner'
import CouponForm from './CouponForm'
import adminApi from '@/admin/services/adminApi'
import type { CouponDto } from '@/admin/types/admin'

const navigateMock = vi.fn()

vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>()
  return { ...actual, useNavigate: () => navigateMock }
})

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

vi.mock('@/admin/services/adminApi', () => ({
  default: {
    coupons: {
      getById: vi.fn(),
      create: vi.fn(),
      update: vi.fn(),
    },
  },
}))

function coupon(overrides: Partial<CouponDto> = {}): CouponDto {
  return {
    id: 1,
    code: 'VIEJO10',
    name: 'Cupón viejo',
    description: null,
    type: 'percentage',
    value: 10,
    minimumPurchase: null,
    maximumDiscount: null,
    startsAt: null,
    expiresAt: null,
    usageLimit: null,
    usageLimitPerCustomer: null,
    timesUsed: 0,
    active: true,
    createdAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

function renderForm(initialEntry: string) {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <Routes>
        <Route path="/admin/coupons/new" element={<CouponForm />} />
        <Route path="/admin/coupons/:id/edit" element={<CouponForm />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('CouponForm', () => {
  beforeEach(() => {
    navigateMock.mockClear()
    vi.clearAllMocks()
  })

  it('creates a coupon with the code uppercased and navigates back to the list', async () => {
    vi.mocked(adminApi.coupons.create).mockResolvedValue(coupon())

    const user = userEvent.setup()
    renderForm('/admin/coupons/new')

    await user.type(screen.getByLabelText(/código/i), 'nuevo10')
    await user.type(screen.getByLabelText(/nombre/i), 'Nuevo cupón')
    await user.clear(screen.getByLabelText(/porcentaje/i))
    await user.type(screen.getByLabelText(/porcentaje/i), '15')
    await user.click(screen.getByRole('button', { name: /^guardar$/i }))

    expect(adminApi.coupons.create).toHaveBeenCalledWith(
      expect.objectContaining({ code: 'NUEVO10', name: 'Nuevo cupón', value: 15, type: 'percentage' }),
    )
    expect(navigateMock).toHaveBeenCalledWith('/admin/coupons')
    expect(toast.success).toHaveBeenCalled()
  })

  it('shows the backend error message and does not navigate when saving fails', async () => {
    vi.mocked(adminApi.coupons.create).mockRejectedValue({ message: 'Este código ya está en uso.' })

    const user = userEvent.setup()
    renderForm('/admin/coupons/new')

    await user.type(screen.getByLabelText(/código/i), 'repetido')
    await user.type(screen.getByLabelText(/nombre/i), 'Cupón repetido')
    await user.clear(screen.getByLabelText(/porcentaje/i))
    await user.type(screen.getByLabelText(/porcentaje/i), '5')
    await user.click(screen.getByRole('button', { name: /^guardar$/i }))

    await vi.waitFor(() => expect(toast.error).toHaveBeenCalledWith('Este código ya está en uso.'))
    expect(navigateMock).not.toHaveBeenCalled()
  })

  it('loads an existing coupon and submits an update', async () => {
    vi.mocked(adminApi.coupons.getById).mockResolvedValue(coupon())
    vi.mocked(adminApi.coupons.update).mockResolvedValue(coupon({ name: 'Cupón editado' }))

    const user = userEvent.setup()
    renderForm('/admin/coupons/1/edit')

    const nameInput = await screen.findByDisplayValue('Cupón viejo')
    await user.clear(nameInput)
    await user.type(nameInput, 'Cupón editado')
    await user.click(screen.getByRole('button', { name: /^guardar$/i }))

    expect(adminApi.coupons.update).toHaveBeenCalledWith(1, expect.objectContaining({ name: 'Cupón editado', code: 'VIEJO10' }))
    expect(navigateMock).toHaveBeenCalledWith('/admin/coupons')
  })
})
