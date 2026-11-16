import { describe, expect, it, vi } from 'vitest'
import { canReloadForStaleChunk, isStaleChunkError, reloadForStaleChunk } from './staleChunk'

function memoryStorage(initial: Record<string, string> = {}): Storage {
  const data = new Map(Object.entries(initial))
  return {
    get length() {
      return data.size
    },
    clear: () => data.clear(),
    getItem: (key) => data.get(key) ?? null,
    key: (index) => [...data.keys()][index] ?? null,
    removeItem: (key) => void data.delete(key),
    setItem: (key, value) => void data.set(key, value),
  }
}

describe('isStaleChunkError', () => {
  it.each([
    'Failed to fetch dynamically imported module: https://tienda.example/assets/Checkout-abc.js',
    'error loading dynamically imported module: https://tienda.example/assets/Checkout-abc.js',
    'Importing a module script failed.',
    'Unable to preload CSS for /assets/Checkout-abc.css',
  ])('reconoce «%s»', (message) => {
    expect(isStaleChunkError(new TypeError(message))).toBe(true)
  })

  it('no confunde otros errores', () => {
    expect(isStaleChunkError(new Error('Cannot read properties of undefined'))).toBe(false)
    expect(isStaleChunkError({ status: 404 })).toBe(false)
    expect(isStaleChunkError(undefined)).toBe(false)
  })
})

describe('recarga ante un chunk viejo', () => {
  it('recarga la primera vez y deja la marca', () => {
    const storage = memoryStorage()
    const reload = vi.fn()
    expect(canReloadForStaleChunk(1_000_000, storage)).toBe(true)
    expect(reloadForStaleChunk(reload, 1_000_000, storage)).toBe(true)
    expect(reload).toHaveBeenCalledOnce()
  })

  it('no vuelve a recargar dentro del minuto: así no hay bucle si el chunk sigue faltando', () => {
    const storage = memoryStorage()
    reloadForStaleChunk(vi.fn(), 1_000_000, storage)
    expect(canReloadForStaleChunk(1_000_000 + 59_000, storage)).toBe(false)
  })

  it('pasado el minuto se puede volver a intentar (otro despliegue)', () => {
    const storage = memoryStorage()
    reloadForStaleChunk(vi.fn(), 1_000_000, storage)
    expect(canReloadForStaleChunk(1_000_000 + 61_000, storage)).toBe(true)
  })

  it('sin sessionStorage no recarga sola: no tendría cómo cortar un bucle', () => {
    const reload = vi.fn()
    expect(canReloadForStaleChunk(1_000_000, null)).toBe(false)
    expect(reloadForStaleChunk(reload, 1_000_000, null)).toBe(false)
    expect(reload).not.toHaveBeenCalled()
  })

  it('si guardar la marca falla, tampoco recarga', () => {
    const storage = memoryStorage()
    storage.setItem = () => {
      throw new Error('QuotaExceededError')
    }
    const reload = vi.fn()
    expect(reloadForStaleChunk(reload, 1_000_000, storage)).toBe(false)
    expect(reload).not.toHaveBeenCalled()
  })
})
