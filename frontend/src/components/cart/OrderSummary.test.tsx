import { beforeEach, describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import OrderSummary from './OrderSummary'
import { useCartStore } from '@/stores/useCartStore'
import type { CartTotals } from '@/stores/useCartStore'

// A store selling at 2 x $39.990 with Chilean VAT already inside the listed price: gross 79.980,
// net 67.210,08, tax 12.769,92. The numbers that prompted this — the summary listed the tax as if
// it were a sum, so 79.980 + 12.770 matched neither the total nor 19% of anything shown.
function totals(overrides: Partial<CartTotals> = {}): CartTotals {
  return {
    subtotal: 79980,
    shippingCost: 0,
    taxAmount: 12769.92,
    discountAmount: 0,
    total: 79980,
    pricesIncludeTax: true,
    taxRate: 19,
    freeShippingThreshold: null,
    shippingEnabled: true,
    shippingAvailable: false,
    couponApplied: false,
    ...overrides,
  }
}

function renderSummary(t: Partial<CartTotals> = {}, region = '') {
  useCartStore.setState({ ...useCartStore.getInitialState(), totals: totals(t), region })
  return render(<OrderSummary />)
}

describe('OrderSummary', () => {
  beforeEach(() => {
    useCartStore.setState(useCartStore.getInitialState())
  })

  describe('impuesto', () => {
    it('con IVA incluido lo muestra como nota, no como sumando', () => {
      renderSummary()

      expect(screen.getByText(/IVA incluido/)).toHaveTextContent('IVA incluido: $12.770')
      // The additive label must not appear: it's what made the figures look wrong.
      expect(screen.queryByText('Impuesto')).not.toBeInTheDocument()
      // Subtotal and Total match, because the tax is already inside both.
      expect(screen.getAllByText('$79.980')).toHaveLength(2)
    })

    it('sin IVA incluido lo muestra como línea sumable y las cuentas cuadran', () => {
      renderSummary({ pricesIncludeTax: false, subtotal: 67210, taxAmount: 12770, total: 79980 })

      expect(screen.getByText('Impuesto')).toBeInTheDocument()
      expect(screen.queryByText(/IVA incluido/)).not.toBeInTheDocument()
      expect(screen.getByText('$67.210')).toBeInTheDocument()
      expect(screen.getByText('$12.770')).toBeInTheDocument()
      expect(screen.getByText('$79.980')).toBeInTheDocument()
    })
  })

  describe('envío', () => {
    it('sin dirección elegida dice que se calculará, no que no hay envío', () => {
      renderSummary({ shippingAvailable: false }, '')

      expect(screen.getByText('Se calcula al elegir la dirección')).toBeInTheDocument()
      expect(screen.queryByText('No disponible')).not.toBeInTheDocument()
    })

    it('con dirección elegida y sin cobertura dice que no está disponible', () => {
      renderSummary({ shippingAvailable: false }, 'Magallanes')

      expect(screen.getByText('No disponible')).toBeInTheDocument()
    })

    it('muestra el importe cuando hay tarifa', () => {
      renderSummary({ shippingCost: 3990, shippingAvailable: true, total: 83970 }, 'Metropolitana')

      expect(screen.getByText('$3.990')).toBeInTheDocument()
    })

    it('dice "Gratis" solo cuando hay cobertura y no cuesta nada', () => {
      renderSummary({ shippingCost: 0, shippingAvailable: true }, 'Metropolitana')

      expect(screen.getByText('Gratis')).toBeInTheDocument()
    })

    it('no muestra la línea si la tienda no despacha', () => {
      renderSummary({ shippingEnabled: false })

      expect(screen.queryByText('Envío')).not.toBeInTheDocument()
    })
  })

  it('muestra el descuento cuando hay cupón aplicado', () => {
    renderSummary({ discountAmount: 1000, couponApplied: true, total: 78980 })

    expect(screen.getByText('Descuento')).toBeInTheDocument()
    expect(screen.getByText('-$1.000')).toBeInTheDocument()
  })
})
