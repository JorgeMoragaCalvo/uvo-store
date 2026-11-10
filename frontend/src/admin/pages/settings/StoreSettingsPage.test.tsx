import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import StoreSettingsPage from './StoreSettingsPage'
import { useAdminStoreSettingsStore } from '@/admin/stores/useAdminStoreSettingsStore'
import type { StoreSettingsDto } from '@/admin/types/admin'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

/**
 * GAP-19. Cada tienda elige sus colores, y el storefront pone texto blanco sobre el primario. El rosa que venía por
 * defecto (#E91E63) daba 4,34:1 y axe lo marcó en todas las páginas: el panel avisa cuando un color no llega a 4,5:1.
 */
const BASE: StoreSettingsDto = {
  id: 1,
  storeName: 'Tienda',
  storeDescription: null,
  storeLogo: null,
  storeFavicon: null,
  primaryColor: '#d81b60',
  secondaryColor: '#9c27b0',
  accentColor: '#ffd700',
  darkColor: '#1f2937',
  showHero: true,
  heroAutoplaySpeed: 5000,
  showCategories: true,
  categoriesTitle: null,
  categoriesLimit: 6,
  showNewProducts: true,
  newProductsTitle: null,
  newProductsLimit: 8,
  newProductsDays: 30,
  showFeaturedProducts: true,
  featuredProductsTitle: null,
  featuredProductsLimit: 8,
  showDeals: true,
  dealsTitle: null,
  dealsLimit: 8,
  showBenefits: true,
  benefit1Icon: null,
  benefit1Title: null,
  benefit1Description: null,
  benefit2Icon: null,
  benefit2Title: null,
  benefit2Description: null,
  benefit3Icon: null,
  benefit3Title: null,
  benefit3Description: null,
  benefit4Icon: null,
  benefit4Title: null,
  benefit4Description: null,
  contactEmail: null,
  contactPhone: null,
  whatsappNumber: null,
  facebookUrl: null,
  instagramUrl: null,
  twitterUrl: null,
  tiktokUrl: null,
  metaTitle: null,
  metaDescription: null,
  metaKeywords: null,
  legalName: null,
  legalTaxId: null,
  legalAddress: null,
  withdrawalRightExcluded: false,
}

function renderWith(settings: StoreSettingsDto) {
  useAdminStoreSettingsStore.setState({ settings, loading: false, fetch: async () => {} })
  return render(<StoreSettingsPage />)
}

describe('StoreSettingsPage: contraste de los colores de marca', () => {
  it('does not warn with the default colors', () => {
    renderWith(BASE)
    expect(screen.queryByText(/Contraste bajo/)).not.toBeInTheDocument()
  })

  it('warns under the primary color, tied to its input, when it falls short of 4.5:1 against white', () => {
    renderWith({ ...BASE, primaryColor: '#e91e63' })

    const primary = screen.getByLabelText('Primario')
    expect(primary).toHaveAccessibleDescription(/Contraste bajo \(4,34:1; el mínimo es 4,5:1\).*botones/)
  })

  it('checks the accent against the dark color, which is the badge text', () => {
    renderWith(BASE)
    fireEvent.change(screen.getByLabelText('Oscuro'), { target: { value: '#999999' } })

    expect(screen.getByLabelText('Acento')).toHaveAccessibleDescription(/Destacado/)
    expect(screen.getByLabelText('Oscuro')).toHaveAccessibleDescription(/títulos y precios/)
  })

  it('removes the warning once the color is fixed', () => {
    renderWith({ ...BASE, primaryColor: '#e91e63' })
    fireEvent.change(screen.getByLabelText('Primario'), { target: { value: '#d81b60' } })

    expect(screen.queryByText(/Contraste bajo/)).not.toBeInTheDocument()
  })
})
