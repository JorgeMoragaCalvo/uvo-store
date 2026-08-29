import path from 'node:path'
import { defineConfig } from 'vitest/config'
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
    // Multi-step form flows driven by @testing-library/user-event (real per-keystroke typing
    // across several fields) legitimately take longer than the 5s default, especially on slower
    // machines/CI runners.
    testTimeout: 15000,
  },
})
