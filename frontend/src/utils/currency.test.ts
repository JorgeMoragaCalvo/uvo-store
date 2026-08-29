import { describe, expect, it } from 'vitest'
import { formatCurrency } from './currency'

describe('formatCurrency', () => {
  it('formats a whole number as CLP with no decimals', () => {
    expect(formatCurrency(1000)).toBe('$1.000')
  })

  it('formats zero', () => {
    expect(formatCurrency(0)).toBe('$0')
  })

  it('accepts a different currency code', () => {
    expect(formatCurrency(1000, 'USD')).toContain('1.000')
  })
})
