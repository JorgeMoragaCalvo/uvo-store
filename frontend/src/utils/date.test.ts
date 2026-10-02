import { describe, expect, it } from 'vitest'
import { toDateInputValue } from './date'

describe('toDateInputValue', () => {
  it('usa el día local, no el UTC', () => {
    // F20: 31 de octubre a las 22:30 con el reloj de la máquina. En una máquina al oeste de Greenwich
    // `toISOString()` daría el 1 de noviembre, y el rango por defecto de los informes acababa pidiendo un
    // día que todavía no había empezado.
    const lateEvening = new Date(2026, 9, 31, 22, 30, 0)
    expect(toDateInputValue(lateEvening)).toBe('2026-10-31')
  })

  it('rellena mes y día con cero a la izquierda', () => {
    expect(toDateInputValue(new Date(2026, 0, 5, 12, 0, 0))).toBe('2026-01-05')
  })
})
