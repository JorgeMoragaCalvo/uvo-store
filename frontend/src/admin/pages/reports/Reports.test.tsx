import { describe, expect, it } from 'vitest'
import { toDateInputValue } from './Reports'

/**
 * F20. El rango por defecto se calcula con la fecha LOCAL.
 *
 * <p>Era `toISOString().slice(0, 10)`, que es UTC: abriendo la pantalla a las 22:00 en Chile (UTC-3) el
 * "hasta" que proponía era el día siguiente, uno que todavía no había empezado.
 */
describe('toDateInputValue', () => {
  it('usa el día local, no el UTC', () => {
    // 31 de octubre a las 22:30 con el reloj de la máquina. En una máquina al oeste de Greenwich
    // `toISOString()` daría el 1 de noviembre.
    const lateEvening = new Date(2026, 9, 31, 22, 30, 0)
    expect(toDateInputValue(lateEvening)).toBe('2026-10-31')
  })

  it('rellena mes y día con cero a la izquierda', () => {
    expect(toDateInputValue(new Date(2026, 0, 5, 12, 0, 0))).toBe('2026-01-05')
  })
})
