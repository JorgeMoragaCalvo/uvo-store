import { defineConfig, devices } from '@playwright/test'
import base from './playwright.config.ts'
import { HOMOLOGACION } from './e2e/support/env.ts'

// IMP-07. La homologación de pagos: el mismo backend, storefront y dobles del E2E (POS, SMTP), pero las pasarelas
// de HOMOLOGACION hablan con el ambiente de pruebas real del proveedor (ver e2e/launch/backend.ts). Fuera de la CI:
// depende de Internet, de la disponibilidad del proveedor y, para MercadoPago, de credenciales y un túnel propios.
//
//   HOMOLOGACION=webpay npm run e2e:homologacion
//
// Cada caso deja su evidencia (sin tokens ni datos de tarjeta) en e2e/.logs/homologacion/, que se transcribe al
// expediente docs/homologacion-pagos.md. Ver también el GO_LIVE_CHECKLIST §10.
if (HOMOLOGACION.length === 0) {
  throw new Error('Falta HOMOLOGACION: webpay, mercadopago o las dos separadas por coma')
}

export default defineConfig({
  ...base,
  testDir: './e2e/homologacion',
  // Transbank y MercadoPago tardan más que los dobles, y algunos casos esperan a la conciliación.
  timeout: 240_000,
  expect: { timeout: 30_000 },
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report-homologacion' }]],
  projects: [{ name: 'homologacion', use: { ...devices['Desktop Chrome'] } }],
})
