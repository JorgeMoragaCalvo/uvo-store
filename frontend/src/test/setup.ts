import '@testing-library/jest-dom/vitest'
import { afterEach } from 'vitest'
import { cleanup } from '@testing-library/react'

// jsdom doesn't implement ResizeObserver, which several Radix UI primitives (Switch, Select…)
// touch on mount — without a stub, any test rendering them throws.
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
globalThis.ResizeObserver ??= ResizeObserverStub as unknown as typeof ResizeObserver

afterEach(() => {
  cleanup()
})
