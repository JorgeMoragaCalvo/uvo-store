import { describe, expect, it } from 'vitest'
import { friendlyError, NETWORK_ERROR_MESSAGE } from './errors'

// PROD-24b. El comprador veía tal cual el `message` del interceptor: el de axios sin respuesta ("Network
// Error") o el de la excepción de Java en un 404.
describe('friendlyError', () => {
  it('explains a missing response as a connection problem, in Spanish', () => {
    expect(friendlyError({ message: 'Network Error' }, 'respaldo')).toBe(NETWORK_ERROR_MESSAGE)
  })

  it('keeps the backend message of a business error', () => {
    expect(friendlyError({ status: 409, message: 'Uno o más productos no tienen stock suficiente' }, 'respaldo')).toBe(
      'Uno o más productos no tienen stock suficiente',
    )
    expect(friendlyError({ status: 400, message: 'Cupón vencido' }, 'respaldo')).toBe('Cupón vencido')
  })

  it('uses the fallback for a 404, whose message is the Java exception text', () => {
    expect(friendlyError({ status: 404, message: 'Order 42 not found' }, 'respaldo')).toBe('respaldo')
  })

  it('uses the fallback for a server error', () => {
    expect(friendlyError({ status: 500, message: 'Ocurrió un error inesperado' }, 'respaldo')).toBe('respaldo')
  })

  it('uses the fallback for a business error without a message', () => {
    expect(friendlyError({ status: 400 }, 'respaldo')).toBe('respaldo')
  })
})
