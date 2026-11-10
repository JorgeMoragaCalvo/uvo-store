import { describe, expect, it } from 'vitest'
import { formatRut } from './rut'

describe('formatRut', () => {
  it('adds the thousands dots to an eight-digit RUT', () => {
    expect(formatRut('76123456-0')).toBe('76.123.456-0')
  })

  it('formats a seven-digit RUT with a K check digit', () => {
    expect(formatRut('1000005-K')).toBe('1.000.005-K')
  })

  it('returns anything else unchanged', () => {
    expect(formatRut('no es un rut')).toBe('no es un rut')
  })
})
