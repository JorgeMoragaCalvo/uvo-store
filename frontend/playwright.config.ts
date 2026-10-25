import { defineConfig, devices } from '@playwright/test'
import {
  BACKEND_PORT,
  BACKEND_URL,
  DOUBLES_URL,
  VITE_PORT,
} from './e2e/support/env.ts'

// PROD-14a. Red de navegador contra el backend real (el jar), Vite y los dobles del POS y del correo.
// Se corre con `npm run e2e` después de `./mvnw -o package -DskipTests` en la raíz; ver CLAUDE.md.
//
// Un solo worker y en serie: es un único backend con estado, y el circuito encadena sus pasos (la compra
// necesita la tienda configurada, la confirmación necesita la compra...).
export default defineConfig({
  testDir: './e2e/tests',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: Boolean(process.env.CI),
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    locale: 'es-CL',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: [
    {
      command: 'node e2e/doubles/server.ts',
      url: `${DOUBLES_URL}/__doubles/health`,
      reuseExistingServer: false,
      timeout: 20_000,
    },
    {
      command: 'node e2e/launch/backend.ts',
      url: `${BACKEND_URL}/actuator/health`,
      reuseExistingServer: false,
      timeout: 180_000,
      stdout: 'pipe',
    },
    {
      command: `npx vite --host 127.0.0.1 --port ${VITE_PORT} --strictPort`,
      url: `http://127.0.0.1:${VITE_PORT}`,
      reuseExistingServer: false,
      timeout: 60_000,
      // La variable del proceso le gana a frontend/.env: el proxy de /api va al backend de la red.
      env: { VITE_DEV_PROXY_TARGET: `http://demo.localhost:${BACKEND_PORT}` },
    },
  ],
})
