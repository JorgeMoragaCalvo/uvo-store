// PROD-14a. El circuito completo en el navegador, contra el backend real, Vite y los dobles del POS y del correo.
//
// Dos tiendas nuevas (la `demo` de V8 no tiene administrador y no se puede crear por la API):
// - tienda-pos: la da de alta el POS por la API de plataforma, con su conexión; su catálogo lo empuja el POS y
//   sus ventas vuelven al POS por el outbox.
// - tienda-panel: la da de alta un operador en el formulario de plataforma; su producto se crea en el panel.
//
// Los pasos van en serie y comparten estado: cada uno parte de lo que dejó el anterior, como en la vida real.
// Lo que pasa después del commit (correos, descuento de stock, venta al POS) se comprueba de verdad, que es lo
// que los tests del backend no pueden ver porque revierten su transacción.

import { expect, test, type Page } from '@playwright/test'
import { BACKEND_URL, DOUBLES_URL, PLATFORM_URL, E2E_PLATFORM_API_KEY, storeUrl } from '../support/env.ts'
import { http, storeHost } from '../support/http.ts'
import { linkIn, waitForMail } from '../support/mail.ts'
import {
  ADMIN_AUTH_KEY,
  acceptInvitation,
  createProduct,
  createShippingZone,
  disableShipping,
  expectStockInList,
  login,
  logout,
  openOrder,
  panelSession,
  restorePanelSession,
  saveTracking,
  sessionToken,
  setPaymentStatus,
} from '../support/panel.ts'
import { createStoreViaApi } from '../support/platform.ts'
import { newPosCredentials, posOrdersReceived, signedWebhook, syncProduct } from '../support/pos.ts'
import {
  checkoutWithManualPayment,
  expectAvailableStock,
  openProduct,
  trackOrder,
  type Buyer,
} from '../support/storefront.ts'

test.describe.configure({ mode: 'serial' })

const PASSWORD = 'Clave-E2e-Circuito-2026'
// Nombres canónicos del catálogo de comunas: la zona se marca con ellos y el checkout los ofrece así.
const REGION = 'Metropolitana de Santiago'
const COMMUNE = 'Santiago'

interface StoreUnderTest {
  slug: string
  storeName: string
  ownerName: string
  ownerEmail: string
  product: { name: string; sku: string; price: number; stock: number }
  buyer: Buyer
  /** Lo que van dejando los pasos. */
  session: string
  orderNumber: string
  orderPath: string
  productPath: string
}

function buyer(email: string): Buyer {
  return {
    email,
    firstName: 'Camila',
    lastName: 'Rojas',
    phone: '+56911111111',
    address: 'Av. Siempre Viva 742',
    city: 'Santiago',
    postalCode: '8320000',
    region: REGION,
    commune: COMMUNE,
  }
}

const posStore: StoreUnderTest = {
  slug: 'tienda-pos',
  storeName: 'Tienda POS E2E',
  ownerName: 'Dueña POS',
  ownerEmail: 'duena-pos@e2e.test',
  product: { name: 'Café del POS', sku: 'POS-CAFE-01', price: 8990, stock: 10 },
  buyer: buyer('comprador-pos@e2e.test'),
  session: '',
  orderNumber: '',
  orderPath: '',
  productPath: '',
}

const panelStore: StoreUnderTest = {
  slug: 'tienda-panel',
  storeName: 'Tienda Panel E2E',
  ownerName: 'Dueño Panel',
  ownerEmail: 'dueno-panel@e2e.test',
  product: { name: 'Té del panel', sku: 'PANEL-TE-01', price: 4990, stock: 5 },
  buyer: buyer('comprador-panel@e2e.test'),
  session: '',
  orderNumber: '',
  orderPath: '',
  productPath: '',
}

const POS_COMPANY_ID = 990014
const POS_EXTERNAL_ID = 50101
const posCredentials = newPosCredentials(POS_COMPANY_ID)

async function openPanel(page: Page, store: StoreUnderTest): Promise<void> {
  await restorePanelSession(page, store.slug, store.session)
}

test('alta de tienda-pos por la API de plataforma, con su conexión al POS', async () => {
  const result = await createStoreViaApi({
    slug: posStore.slug,
    storeName: posStore.storeName,
    adminName: posStore.ownerName,
    adminEmail: posStore.ownerEmail,
    pos: { ...posCredentials, companyName: 'Empresa E2E', apiUrl: DOUBLES_URL },
  })
  expect(result.status, result.text).toBe(200)
  expect(result.json).toMatchObject({ slug: posStore.slug, created: true })
  // La respuesta nunca devuelve los secretos de la conexión.
  expect(result.text).not.toContain(posCredentials.apiKey)
  expect(result.text).not.toContain(posCredentials.webhookSecret)
})

test('alta de tienda-panel por el formulario de plataforma', async ({ page }) => {
  await page.goto(`${PLATFORM_URL}/plataforma/nueva-tienda`)
  await page.getByLabel('API key de plataforma').fill(E2E_PLATFORM_API_KEY)
  await page.getByLabel('Nick (subdominio interno)').fill(panelStore.slug)
  await page.getByLabel('Nombre de la tienda').fill(panelStore.storeName)
  await page.getByLabel('Nombre del cliente').fill(panelStore.ownerName)
  await page.getByLabel('Email del cliente').fill(panelStore.ownerEmail)
  await page.getByRole('button', { name: 'Crear tienda' }).click()

  await expect(page.getByText('Tienda creada')).toBeVisible()
  await expect(page.getByText(storeUrl(panelStore.slug), { exact: true })).toBeVisible()
})

for (const store of [posStore, panelStore]) {
  test(`${store.slug}: el dueño entra por el enlace del correo y vuelve a iniciar sesión`, async ({ page }) => {
    const invitation = await waitForMail(store.ownerEmail, `Te invitaron a administrar ${store.storeName}`)
    const link = linkIn(invitation, '/admin/aceptar-invitacion?token=')
    // El enlace apunta al storefront de SU tienda (y pasa por el proxy de Vite con su subdominio).
    expect(link.startsWith(storeUrl(store.slug, '/admin/aceptar-invitacion'))).toBe(true)

    await acceptInvitation(page, link, PASSWORD)
    await logout(page)
    await login(page, store.slug, store.ownerEmail, PASSWORD)
    store.session = await panelSession(page)
  })
}

for (const store of [posStore, panelStore]) {
  test(`${store.slug}: se configura desde el panel para poder vender`, async ({ page }) => {
    await openPanel(page, store)
    await createShippingZone(page, store.slug, REGION)
    await disableShipping(page, store.slug)
  })
}

test('tienda-pos: el POS empuja su producto', async ({ page }) => {
  const result = await syncProduct(posCredentials, {
    externalId: POS_EXTERNAL_ID,
    sku: posStore.product.sku,
    name: posStore.product.name,
    price: posStore.product.price,
    stock: posStore.product.stock,
    version: 1,
    categoryName: 'Cafetería',
  })
  expect(result.status, result.text).toBe(200)
  expect(result.json).toMatchObject({ success: true })

  await openPanel(page, posStore)
  await expectStockInList(page, posStore.slug, posStore.product.sku, posStore.product.stock)
})

test('tienda-panel: crea su producto en el panel', async ({ page }) => {
  await openPanel(page, panelStore)
  await createProduct(page, panelStore.slug, panelStore.product)
  await expectStockInList(page, panelStore.slug, panelStore.product.sku, panelStore.product.stock)
})

for (const store of [posStore, panelStore]) {
  test(`${store.slug}: compra con pago manual en el storefront`, async ({ page }) => {
    await openProduct(page, store.slug, store.product.name)
    store.productPath = new URL(page.url()).pathname
    await expectAvailableStock(page, store.product.stock)

    store.orderNumber = await checkoutWithManualPayment(page, store.buyer)

    // Después del commit: el acuse sale por el outbox hacia el SMTP.
    await waitForMail(store.buyer.email, `Recibimos tu pedido ${store.orderNumber}`)
  })
}

for (const store of [posStore, panelStore]) {
  test(`${store.slug}: el panel confirma el pago y se descuenta el stock`, async ({ page }) => {
    await openPanel(page, store)
    await openOrder(page, store.slug, store.orderNumber)
    store.orderPath = new URL(page.url()).pathname
    await setPaymentStatus(page, 'PAID')

    // Después del commit: el stock baja y sale la confirmación.
    await expectStockInList(page, store.slug, store.product.sku, store.product.stock - 1)
    await waitForMail(store.buyer.email, `Confirmación de tu pedido ${store.orderNumber}`)
  })
}

test('tienda-pos: la venta web llega al POS', async () => {
  await expect
    .poll(async () => (await posOrdersReceived()).some((call) => call.idempotencyKey?.startsWith(posStore.orderNumber)), {
      message: `el POS recibe ${posStore.orderNumber}`,
      timeout: 20_000,
    })
    .toBe(true)

  const sale = (await posOrdersReceived()).find((call) => call.idempotencyKey?.startsWith(posStore.orderNumber))
  expect(sale).toMatchObject({
    authorization: `Bearer ${posCredentials.apiKey}`,
    companyId: String(POS_COMPANY_ID),
    idempotencyKey: `${posStore.orderNumber}:${POS_COMPANY_ID}`,
    body: {
      source: 'uvostore',
      order_number: posStore.orderNumber,
      items: [expect.objectContaining({ product_id: POS_EXTERNAL_ID, quantity: 1 })],
    },
  })
  // PROD-15b: la venta lleva el id de la petición que confirmó el pago, para seguirla entre los dos sistemas.
  expect(sale?.requestId).toMatch(/^[A-Za-z0-9][A-Za-z0-9._-]{7,63}$/)
})

test('tienda-panel: sin conexión al POS, su venta no sale hacia el POS', async () => {
  const calls = await posOrdersReceived()
  expect(calls.some((call) => call.idempotencyKey?.startsWith(panelStore.orderNumber))).toBe(false)
  expect(calls).toHaveLength(1)
})

test('tienda-pos: un stock.updated del POS se refleja en la web', async ({ page }) => {
  const afterSale = posStore.product.stock - 1
  const webhook = signedWebhook('stock-updated', posCredentials, {
    event: 'stock.updated',
    productId: POS_EXTERNAL_ID,
    sku: posStore.product.sku,
    companyId: POS_COMPANY_ID,
    warehouseId: 1,
    oldStock: afterSale,
    newStock: 20,
    stockWeb: 20,
  })
  const first = await webhook.send()
  expect(first.status, first.text).toBe(200)
  expect(first.json).toMatchObject({ success: true })

  await page.goto(storeUrl(posStore.slug, posStore.productPath))
  await expectAvailableStock(page, 20)

  await openPanel(page, posStore)
  await expectStockInList(page, posStore.slug, posStore.product.sku, 20)

  // El mismo webhook otra vez (misma firma) se rechaza: antirreplay de POS-3.
  const replay = await webhook.send()
  expect(replay.status).toBe(401)
  expect(replay.text).toContain('REPLAYED_WEBHOOK')
})

test('tienda-pos: el seguimiento del pedido', async ({ page }) => {
  await openPanel(page, posStore)
  await page.goto(storeUrl(posStore.slug, posStore.orderPath))
  await saveTracking(page, 'E2E-TRACK-0001')

  await trackOrder(page, posStore.slug, posStore.orderNumber)
  await expect(page.getByText('E2E-TRACK-0001')).toBeVisible()
  await expect(page.getByText('Enviado').first()).toBeVisible()
  await expect(page.getByText('Pagado').first()).toBeVisible()
})

test('aislamiento: el token de una tienda no sirve en la otra', async ({ page }) => {
  const posToken = sessionToken(posStore.session)
  const own = await http('GET', `${BACKEND_URL}/api/admin/orders`, {
    headers: { Host: storeHost(posStore.slug), Authorization: `Bearer ${posToken}` },
  })
  expect(own.status, own.text).toBe(200)
  const foreign = await http('GET', `${BACKEND_URL}/api/admin/orders`, {
    headers: { Host: storeHost(panelStore.slug), Authorization: `Bearer ${posToken}` },
  })
  expect(foreign.status).toBe(401)

  // En el navegador: la sesión de tienda-pos puesta en el origen de tienda-panel termina en el login, sin token.
  await restorePanelSession(page, panelStore.slug, posStore.session)
  await page.goto(storeUrl(panelStore.slug, '/admin/orders'))
  await expect(page).toHaveURL(/\/admin\/login$/)
  const stored = await page.evaluate((key) => window.localStorage.getItem(key), ADMIN_AUTH_KEY)
  expect(stored ? (JSON.parse(stored) as { state: { token: string | null } }).state.token : null).toBeNull()

  // Y el catálogo de una tienda no muestra el producto de la otra.
  const search = await http('GET', `${BACKEND_URL}/api/v1/products?search=${encodeURIComponent(posStore.product.name)}`, {
    headers: { Host: storeHost(panelStore.slug) },
  })
  expect(search.status, search.text).toBe(200)
  expect(search.text).not.toContain(posStore.product.sku)
  await page.goto(storeUrl(panelStore.slug, `/shop?search=${encodeURIComponent(posStore.product.name)}`))
  await page.waitForLoadState('networkidle')
  await expect(page.getByRole('link', { name: posStore.product.name })).toHaveCount(0)
})

test('recarga directa de rutas del panel y del storefront', async ({ page }) => {
  await openPanel(page, posStore)

  const checks: { url: string; visible: () => Promise<void> }[] = [
    {
      url: storeUrl(posStore.slug, posStore.orderPath),
      visible: () => expect(page.getByRole('heading', { level: 1, name: posStore.orderNumber })).toBeVisible(),
    },
    {
      url: storeUrl(posStore.slug, '/admin/settings/general'),
      visible: () => expect(page.getByRole('button', { name: 'Guardar cambios' })).toBeVisible(),
    },
    {
      url: storeUrl(posStore.slug, posStore.productPath),
      visible: () => expect(page.getByRole('heading', { name: posStore.product.name })).toBeVisible(),
    },
    {
      url: storeUrl(posStore.slug, `/track-order?order=${posStore.orderNumber}`),
      visible: () => expect(page.getByPlaceholder('ej: ORD-AAXFD5GP')).toHaveValue(posStore.orderNumber),
    },
  ]
  for (const check of checks) {
    await page.goto(check.url)
    await check.visible()
    await page.reload()
    await check.visible()
  }
  // La sesión del panel sobrevive a las recargas.
  await page.goto(storeUrl(posStore.slug, '/admin/orders'))
  await expect(page.getByRole('button', { name: 'Salir' })).toBeVisible()
})
