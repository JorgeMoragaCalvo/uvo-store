import { defineConfig, devices, type PlaywrightTestConfig } from '@playwright/test'
import {
  BACKEND_PORT,
  BACKEND_URL,
  DOUBLES_URL,
  E2E_MODE,
  VITE_PORT,
} from './e2e/support/env.ts'

type TestConfigWebServer = Extract<NonNullable<PlaywrightTestConfig['webServer']>, { command: string }>

// PROD-14a. Red de navegador contra el backend real (el jar), Vite y los dobles del POS y del correo.
// Se corre con `npm run e2e` después de `./mvnw -o package -DskipTests` en la raíz; ver CLAUDE.md.
//
// Un solo worker y en serie: es un único backend con estado, y el circuito encadena sus pasos (la compra
// necesita la tienda configurada, la confirmación necesita la compra...).
//
// IMP-04. El storefront depende de E2E_MODE (e2e/support/env.ts): `dist` (por defecto) compila y sirve el build
// con nginx y la configuración de ops/nginx/; `vite` (npm run e2e:dev) usa el servidor de desarrollo.
const storefront: TestConfigWebServer =
  E2E_MODE === 'dist'
    ? {
        command: 'node e2e/launch/frontend.ts',
        url: `http://127.0.0.1:${VITE_PORT}`,
        reuseExistingServer: false,
        // Incluye el vite build.
        timeout: 180_000,
      }
    : {
        command: `npx vite --host 127.0.0.1 --port ${VITE_PORT} --strictPort`,
        url: `http://127.0.0.1:${VITE_PORT}`,
        reuseExistingServer: false,
        timeout: 60_000,
        // La variable del proceso le gana a frontend/.env: el proxy de /api va al backend de la red.
        env: { VITE_DEV_PROXY_TARGET: `http://demo.localhost:${BACKEND_PORT}` },
      }

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
  // GAP-08. El proyecto móvil corre solo el spec de pasarelas, con su propia tienda: el circuito ya recorre el
  // panel completo en escritorio. Pixel 7 sobre el mismo Chromium que instala la CI.
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'mobile', testMatch: /pasarelas\.spec\.ts/, use: { ...devices['Pixel 7'] } },
  ],
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
    storefront,
  ],
})
