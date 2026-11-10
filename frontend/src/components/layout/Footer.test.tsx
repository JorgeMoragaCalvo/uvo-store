import { beforeEach, describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import Footer from './Footer'
import { useStoreSettingsStore } from '@/stores/useStoreSettingsStore'
import type { PublicStoreSettings } from '@/types/api'

/** GAP-18. El pie identifica al vendedor en todas las páginas con lo que la tienda configuró. */
function renderFooter(overrides: Partial<PublicStoreSettings>) {
  useStoreSettingsStore.setState({
    settings: { storeName: 'Tienda Uno', legalName: null, legalTaxId: null, ...overrides } as PublicStoreSettings,
  })
  return render(
    <MemoryRouter>
      <Footer />
    </MemoryRouter>,
  )
}

describe('Footer', () => {
  beforeEach(() => {
    useStoreSettingsStore.setState(useStoreSettingsStore.getInitialState())
  })

  it('shows the legal name and the formatted RUT', () => {
    renderFooter({ legalName: 'Comercial Uno SpA', legalTaxId: '76123456-0' })

    expect(screen.getByText('Comercial Uno SpA · RUT 76.123.456-0')).toBeInTheDocument()
  })

  it('shows no legal line while the store has not filled it in', () => {
    renderFooter({})

    expect(screen.queryByText(/RUT/)).not.toBeInTheDocument()
  })
})
