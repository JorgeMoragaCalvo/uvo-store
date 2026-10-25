import path from 'node:path'
import { configDefaults, defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(import.meta.dirname, './src'),
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    // PROD-14a: e2e/ holds the Playwright suite (*.spec.ts), which needs the real backend and a browser.
    // Without this, Vitest's default include picks those files up and `npm test` fails.
    exclude: [...configDefaults.exclude, 'e2e/**'],
    // Multi-step form flows driven by @testing-library/user-event (real per-keystroke typing
    // across several fields) legitimately take longer than the 5s default, especially on slower
    // machines/CI runners.
    testTimeout: 15000,
  },
})
