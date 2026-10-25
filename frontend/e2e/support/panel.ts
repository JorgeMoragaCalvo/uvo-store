// PROD-14a. Pasos del panel de administración que repiten las dos tiendas del circuito.
//
// Los selectores van por rol, label o texto, como los ve una persona; la app no tiene data-testid.

import { expect, type Page } from '@playwright/test'
import { storeUrl } from './env.ts'

/** Clave de persistencia de la sesión del panel (useAdminAuthStore). */
export const ADMIN_AUTH_KEY = 'uvostore_admin_auth'

export async function acceptInvitation(page: Page, link: string, password: string): Promise<void> {
  await page.goto(link)
  await expect(page.getByText('Elige tu contraseña')).toBeVisible()
  await page.getByLabel('Contraseña', { exact: true }).fill(password)
  await page.getByLabel('Confirmar contraseña').fill(password)
  await page.getByRole('button', { name: 'Entrar al panel' }).click()
  await expect(page.getByRole('button', { name: 'Salir' })).toBeVisible()
}

export async function logout(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Salir' }).click()
  await expect(page).toHaveURL(/\/admin\/login$/)
}

export async function login(page: Page, slug: string, email: string, password: string): Promise<void> {
  await page.goto(storeUrl(slug, '/admin/login'))
  await page.getByLabel('Correo electrónico').fill(email)
  await page.getByLabel('Contraseña').fill(password)
  await page.getByRole('button', { name: 'Ingresar' }).click()
  await expect(page.getByRole('button', { name: 'Salir' })).toBeVisible()
}

/** La sesión del panel tal como la guarda el navegador, para reabrirla en otro test sin repetir el login. */
export async function panelSession(page: Page): Promise<string> {
  const raw = await page.evaluate((key) => window.localStorage.getItem(key), ADMIN_AUTH_KEY)
  if (!raw) throw new Error('No hay sesión del panel en el navegador')
  return raw
}

export function sessionToken(session: string): string {
  return (JSON.parse(session) as { state: { token: string } }).state.token
}

/** Deja la sesión en el localStorage del origen de la tienda antes de que cargue la app. */
export async function restorePanelSession(page: Page, slug: string, session: string): Promise<void> {
  await page.addInitScript(
    ({ host, key, value }) => {
      if (window.location.host === host && !window.sessionStorage.getItem('e2e-session-restored')) {
        window.localStorage.setItem(key, value)
        window.sessionStorage.setItem('e2e-session-restored', '1')
      }
    },
    { host: new URL(storeUrl(slug)).host, key: ADMIN_AUTH_KEY, value: session },
  )
}

/** Una zona de envío activa: sin ella el checkout no ofrece región y no se puede comprar. */
export async function createShippingZone(page: Page, slug: string, region: string): Promise<void> {
  await page.goto(storeUrl(slug, '/admin/shipping/zones/new'))
  await page.getByLabel('Nombre').fill(`Zona ${region}`)
  await page.getByLabel('Regiones (una por línea)').fill(region)
  await page.getByRole('button', { name: 'Guardar' }).click()
  await expect(page.getByText('Zona creada')).toBeVisible()
}

/**
 * Apaga «Envío habilitado» en Config. general: la compra sale sin costo de envío y sin métodos ni tarifas.
 * Guarda el formulario completo, así que también reescribe los ajustes del POS con lo que muestra.
 */
export async function disableShipping(page: Page, slug: string): Promise<void> {
  await page.goto(storeUrl(slug, '/admin/settings/general'))
  await page.getByRole('tab', { name: 'Envío' }).click()
  const toggle = page.getByRole('switch', { name: 'Envío habilitado' })
  await expect(toggle).toBeChecked()
  await toggle.click()
  await expect(toggle).not.toBeChecked()
  await page.getByRole('button', { name: 'Guardar cambios' }).click()
  await expect(page.getByText('Configuración guardada')).toBeVisible()
}

export interface PanelProduct {
  name: string
  sku: string
  price: number
  stock: number
}

export async function createProduct(page: Page, slug: string, product: PanelProduct): Promise<void> {
  await page.goto(storeUrl(slug, '/admin/products/new'))
  await page.getByLabel('Nombre', { exact: true }).fill(product.name)
  await page.getByLabel('SKU', { exact: true }).fill(product.sku)
  await page.getByLabel('Precio', { exact: true }).fill(String(product.price))
  // Exacto: «Gestionar stock» también contiene «Stock».
  await page.getByLabel('Stock', { exact: true }).fill(String(product.stock))
  await page.getByRole('button', { name: 'Guardar' }).click()
  await expect(page.getByText('Producto creado')).toBeVisible()
  await expect(page).toHaveURL(/\/admin\/products$/)
}

/** La celda de stock de un producto en `/admin/products` (columnas: Producto, SKU, Precio, Stock…). */
export async function expectStockInList(page: Page, slug: string, sku: string, stock: number): Promise<void> {
  await page.goto(storeUrl(slug, '/admin/products'))
  const row = page.getByRole('row').filter({ hasText: sku })
  await expect(row.getByRole('cell').nth(3)).toHaveText(String(stock))
}

export async function openOrder(page: Page, slug: string, orderNumber: string): Promise<void> {
  await page.goto(storeUrl(slug, '/admin/orders'))
  await page.getByRole('link', { name: orderNumber }).click()
  await expect(page.getByRole('heading', { level: 1, name: orderNumber })).toBeVisible()
}

/**
 * Cambia «Estado de pago» en el detalle de la orden. El Select de Radix no tiene nombre accesible (su Label no
 * apunta a él), así que se ubica por el contenedor del texto.
 */
export async function setPaymentStatus(page: Page, status: string): Promise<void> {
  const select = page.getByText('Estado de pago', { exact: true }).locator('xpath=..').getByRole('combobox')
  await select.click()
  await page.getByRole('option', { name: status, exact: true }).click()
  await expect(page.getByText('Estado de pago actualizado')).toBeVisible()
  await expect(select).toHaveText(status)
}

export async function saveTracking(page: Page, trackingNumber: string): Promise<void> {
  await page.getByLabel('Número de seguimiento').fill(trackingNumber)
  await page.getByRole('button', { name: 'Guardar' }).click()
  await expect(page.getByText('Seguimiento guardado')).toBeVisible()
}
