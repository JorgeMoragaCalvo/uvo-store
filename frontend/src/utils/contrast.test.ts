import { describe, expect, it } from 'vitest'
import { contrastRatio, formatRatio, relativeLuminance } from './contrast'

describe('contrastRatio', () => {
  it('gives 21:1 for black on white and 1:1 for a color against itself', () => {
    expect(contrastRatio('#000000', '#ffffff')).toBeCloseTo(21, 5)
    expect(contrastRatio('#E91E63', '#E91E63')).toBeCloseTo(1, 5)
  })

  it('matches axe for the old default primary, which falls short of AA', () => {
    // axe-core informó 4.34 para #ffffff sobre #e91e63 (trunca, como formatRatio).
    const ratio = contrastRatio('#ffffff', '#E91E63')!
    expect(ratio).toBeLessThan(4.5)
    expect(formatRatio(ratio)).toBe('4,34:1')
  })

  it('passes AA for the new default primary and for dark text on the default accent', () => {
    expect(contrastRatio('#D81B60', '#ffffff')!).toBeGreaterThanOrEqual(4.5)
    expect(contrastRatio('#1F2937', '#FFD700')!).toBeGreaterThanOrEqual(4.5)
  })

  it('accepts short hex and is symmetric', () => {
    expect(contrastRatio('#fff', '#000')).toBeCloseTo(21, 5)
    expect(contrastRatio('#123456', '#abcdef')).toBeCloseTo(contrastRatio('#abcdef', '#123456')!, 10)
  })

  it('returns null for anything that is not a hex color', () => {
    expect(relativeLuminance('rojo')).toBeNull()
    expect(contrastRatio('', '#ffffff')).toBeNull()
    expect(contrastRatio('#ffffff', 'rgb(0,0,0)')).toBeNull()
  })
})

describe('formatRatio', () => {
  it('truncates instead of rounding up to the threshold', () => {
    expect(formatRatio(4.499)).toBe('4,49:1')
    expect(formatRatio(4.346)).toBe('4,34:1')
  })
})
