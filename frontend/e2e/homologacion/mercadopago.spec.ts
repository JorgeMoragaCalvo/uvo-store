// IMP-07. Homologación de MercadoPago (Checkout Pro) contra el sandbox REAL, con las credenciales de prueba de la
// cuenta de quien opera. No se puede correr sin ellas: sin las variables, los casos se omiten y dicen qué falta.
//
//   HOMOLOGACION=mercadopago \
//   MP_ACCESS_TOKEN=TEST-...        (del usuario de prueba VENDEDOR)
//   MP_PUBLIC_KEY=TEST-...
//   MP_WEBHOOK_SECRET=...           (Tus integraciones > Webhooks, de la aplicación del vendedor)
//   HOMOLOGACION_PUBLIC_HOST=xxxx.trycloudflare.com   (túnel HTTPS a http://localhost:18080; opcional, ver M5)
//   npx playwright test -c playwright.homologacion.config.ts --headed
//
// El pago lo hace una persona en la ventana que se abre: se inicia sesión con el usuario de prueba COMPRADOR y se paga
// con una tarjeta de prueba de MercadoPago. El nombre del titular decide el resultado: APRO aprueba, OTHE rechaza y
// CONT deja pendiente. Cada caso dice en la consola qué nombre usar y espera hasta 10 minutos.
//
// Lo que se puede provocar sin una persona —firma inválida, aviso duplicado, devolución— va solo. La guía completa y lo
// que queda pendiente están en docs/homologacion-pagos.md.

import { expect, test, type Page } from '@playwright/test'
import { BACKEND_URL, DOUBLES_URL, E2E_PLATFORM_API_KEY, HOMOLOGACION, HOMOLOGACION_PUBLIC_HOST, storeUrl } from '../support/env.ts'
import { http, storeHost } from '../support/http.ts'
import { linkIn, mailsTo, waitForMail } from '../support/mail.ts'
import {
  acceptInvitation,
  createShippingZone,
  disableShipping,
  openOrder,
  panelSession,
  refund,
  restorePanelSession,
} from '../support/panel.ts'
import { createStoreViaApi } from '../support/platform.ts'
import { newPosCredentials, posOrdersReceived, syncProduct, type PosCredentials } from '../support/pos.ts'
import { fillCheckoutUntilPayment, openProduct, type Buyer } from '../support/storefront.ts'
import { mercadoPagoWebhook } from '../support/gateways.ts'
import { escribirActa, lastOrderOf, registrar } from './acta.ts'

const CREDENTIALS = {
  accessToken: process.env.MP_ACCESS_TOKEN?.trim() ?? '',
  publicKey: process.env.MP_PUBLIC_KEY?.trim() ?? '',
  webhookSecret: process.env.MP_WEBHOOK_SECRET?.trim() ?? '',
}
const MISSING = Object.entries({ MP_ACCESS_TOKEN: CREDENTIALS.accessToken, MP_PUBLIC_KEY: CREDENTIALS.publicKey, MP_WEBHOOK_SECRET: CREDENTIALS.webhookSecret })
  .filter(([, value]) => !value)
  .map(([name]) => name)

test.skip(!HOMOLOGACION.includes('mercadopago'), 'HOMOLOGACION no incluye mercadopago')
test.skip(MISSING.length > 0, `Faltan ${MISSING.join(', ')}: credenciales de prueba del vendedor (docs/homologacion-pagos.md)`)
test.describe.configure({ mode: 'serial' })

const PASSWORD = 'Clave-Homologacion-MP-2026'
const REGION = 'Metropolitana de Santiago'
const PRODUCT = { externalId: 70201, sku: 'HOM-MP-01', name: 'Café homologación MercadoPago', price: 1990, stock: 40 }
const OPERATOR_TIMEOUT = 10 * 60_000
const SETTLE_MS = 5000

const shop = {
  slug: `homologacion-mp-${Date.now().toString(36)}`,
  storeId: 0,
  session: '',
  pos: null as PosCredentials | null,
  approved: '',
  approvedPaymentId: 0,
}

function buyer(caseId: string): Buyer {
  return {
    email: `m${caseId}-${shop.slug}@e2e.test`,
    firstName: 'Valentina',
    lastName: 'Soto',
    phone: '+56944444444',
    address: 'Irarrázaval 789',
    city: 'Santiago',
    postalCode: '8320000',
    region: REGION,
    commune: 'Santiago',
  }
}

interface Tracking {
  status: string
  paymentStatus: string
  total: number
}

async function tracking(orderNumber: string): Promise<Tracking> {
  const result = await http('GET', `${BACKEND_URL}/api/v1/orders/track?order_number=${encodeURIComponent(orderNumber)}`, {
    headers: { Host: storeHost(shop.slug) },
  })
  const json = result.json as Tracking
  return { status: json.status.toUpperCase(), paymentStatus: json.paymentStatus.toUpperCase(), total: Number(json.total) }
}

async function effects(email: string, orderNumber: string) {
  await new Promise((resolve) => setTimeout(resolve, SETTLE_MS))
  const mails = (await mailsTo(email)).filter((mail) => mail.subject.includes(`Confirmación de tu pedido ${orderNumber}`))
  const sales = (await posOrdersReceived()).filter((call) => call.idempotencyKey === `${orderNumber}:${shop.pos?.companyId}`)
  return { correos: mails.length, ventasPos: sales.length }
}

/** El pago de MercadoPago de una orden, buscado en su API con el access token del vendedor. */
async function mercadoPagoPayment(orderNumber: string): Promise<{ id: number; status: string; transaction_amount: number } | null> {
  const res = await fetch(`https://api.mercadopago.com/v1/payments/search?external_reference=${encodeURIComponent(orderNumber)}`, {
    headers: { Authorization: `Bearer ${CREDENTIALS.accessToken}` },
  })
  const body = (await res.json()) as { results?: { id: number; status: string; transaction_amount: number }[] }
  return body.results?.[0] ?? null
}

/** Hasta la página de MercadoPago, y la persona paga con el titular indicado. Devuelve el número de orden. */
async function payWithOperator(page: Page, who: Buyer, holder: 'APRO' | 'OTHE' | 'CONT'): Promise<string> {
  await openProduct(page, shop.slug, PRODUCT.name)
  await fillCheckoutUntilPayment(page, who)
  await page.getByLabel('MercadoPago').check()
  await page.getByRole('button', { name: 'Confirmar Pedido' }).click()
  await expect(page).toHaveURL(/mercadopago|mercadolibre/, { timeout: 60_000 })
  const orderNumber = await lastOrderOf(who.email)
  console.log(`\n[homologación] Orden ${orderNumber}: inicia sesión con el COMPRADOR de prueba y paga con titular «${holder}».\n`)
  await expect(page).toHaveURL(new RegExp(`${shop.slug}|order-success|checkout`), { timeout: OPERATOR_TIMEOUT })
  return orderNumber
}

test.afterAll(() => {
  const base = escribirActa('mercadopago')
  console.log(`[homologación] acta en ${base}.md`)
})

test('preparación: una tienda con las credenciales de prueba del vendedor', async ({ page }) => {
  shop.pos = newPosCredentials(990800 + Math.floor(Math.random() * 99))
  const ownerEmail = `duena-${shop.slug}@e2e.test`
  const created = await createStoreViaApi({
    slug: shop.slug,
    storeName: 'Homologación MercadoPago',
    adminName: 'Dueña Homologación',
    adminEmail: ownerEmail,
    pos: { ...shop.pos, companyName: 'Empresa homologación MP', apiUrl: DOUBLES_URL },
  })
  expect(created.status, created.text).toBe(200)
  shop.storeId = (created.json as { id: number }).id

  if (HOMOLOGACION_PUBLIC_HOST) {
    // El aviso llega por el túnel con su host: la tienda se resuelve por él aunque no esté verificado.
    const domain = await http('PUT', `${BACKEND_URL}/api/platform/stores/${shop.storeId}/domain`, {
      headers: { 'X-Platform-Key': E2E_PLATFORM_API_KEY, 'X-Platform-Operator': 'e2e' },
      body: { domain: HOMOLOGACION_PUBLIC_HOST },
    })
    expect(domain.status, domain.text).toBe(200)
  }

  const invitation = await waitForMail(ownerEmail, 'Te invitaron a administrar Homologación MercadoPago')
  await acceptInvitation(page, linkIn(invitation, '/admin/aceptar-invitacion?token='), PASSWORD)
  shop.session = await panelSession(page)
  await createShippingZone(page, shop.slug, REGION)
  await disableShipping(page, shop.slug)

  await page.goto(storeUrl(shop.slug, '/admin/payment-gateways'))
  await page.locator('#MERCADOPAGO-accessToken').fill(CREDENTIALS.accessToken)
  await page.locator('#MERCADOPAGO-publicKey').fill(CREDENTIALS.publicKey)
  await page.locator('#MERCADOPAGO-webhookSecret').fill(CREDENTIALS.webhookSecret)
  await page.locator('#MERCADOPAGO-enabled').click()
  const card = page.locator('[data-slot="card"]').filter({ hasText: 'MercadoPago' }).first()
  await card.getByRole('button', { name: 'Guardar' }).click()
  await expect(page.getByText('MercadoPago actualizado')).toBeVisible()

  const synced = await syncProduct(shop.pos, { ...PRODUCT, version: 1, categoryName: 'Cafetería' })
  expect(synced.status, synced.text).toBe(200)
})

test('M1 · pago aprobado (APRO): pagado una vez, por el importe de la orden', async ({ page }) => {
  const who = buyer('01')
  const orderNumber = await payWithOperator(page, who, 'APRO')
  await expect.poll(async () => (await tracking(orderNumber)).paymentStatus, { timeout: 120_000 }).toBe('PAID')
  const payment = await mercadoPagoPayment(orderNumber)
  const state = await tracking(orderNumber)
  const after = await effects(who.email, orderNumber)
  shop.approved = orderNumber
  shop.approvedPaymentId = payment?.id ?? 0
  const ok = payment?.status === 'approved' && payment.transaction_amount === state.total && after.correos === 1 && after.ventasPos === 1
  registrar({
    caso: 'Pago aprobado (APRO)',
    orden: orderNumber,
    esperado: `PAID por ${state.total}; 1 correo, 1 venta`,
    obtenido: `${state.paymentStatus}; ${after.correos} correo, ${after.ventasPos} venta`,
    cumple: ok,
    proveedor: { id: payment?.id, status: payment?.status, transaction_amount: payment?.transaction_amount },
  })
  expect(ok).toBe(true)
})

test('M2 · aviso duplicado y firma inválida: nada se repite ni se acepta', async () => {
  test.skip(!shop.approvedPaymentId, 'Necesita el pago aprobado de M1')
  const duplicate = await mercadoPagoWebhook(shop.slug, CREDENTIALS.webhookSecret, shop.approvedPaymentId)
  const forged = await mercadoPagoWebhook(shop.slug, 'otro-secreto', shop.approvedPaymentId)
  const after = await effects(buyer('01').email, shop.approved)
  const ok = duplicate.status === 200 && forged.status === 401 && after.correos === 1 && after.ventasPos === 1
  registrar({
    caso: 'Aviso duplicado (firmado) y aviso con firma inválida',
    orden: shop.approved,
    esperado: 'Duplicado 200 sin efectos; firma inválida 401',
    obtenido: `duplicado HTTP ${duplicate.status}, falsificado HTTP ${forged.status}; ${after.correos} correo, ${after.ventasPos} venta`,
    cumple: ok,
  })
  expect(ok).toBe(true)
})

test('M3 · rechazo (OTHE): el pago queda fallido y el comprador vuelve con su carrito', async ({ page }) => {
  const who = buyer('03')
  const orderNumber = await payWithOperator(page, who, 'OTHE')
  await expect.poll(async () => (await tracking(orderNumber)).paymentStatus, { timeout: 120_000 }).toBe('FAILED')
  const after = await effects(who.email, orderNumber)
  const payment = await mercadoPagoPayment(orderNumber)
  const ok = after.correos === 0 && after.ventasPos === 0
  registrar({
    caso: 'Rechazo (OTHE)',
    orden: orderNumber,
    esperado: 'FAILED; sin efectos',
    obtenido: `FAILED; ${after.correos} correos, ${after.ventasPos} ventas`,
    cumple: ok,
    proveedor: { id: payment?.id, status: payment?.status },
  })
  expect(ok).toBe(true)
})

test('M4 · pendiente (CONT): «en proceso», sin efectos mientras MercadoPago no lo apruebe', async ({ page }) => {
  const who = buyer('04')
  const orderNumber = await payWithOperator(page, who, 'CONT')
  const state = await tracking(orderNumber)
  const after = await effects(who.email, orderNumber)
  const payment = await mercadoPagoPayment(orderNumber)
  const ok = state.paymentStatus === 'PENDING' && after.correos === 0
  registrar({
    caso: 'Pendiente (CONT)',
    orden: orderNumber,
    esperado: 'PENDING; «en proceso»; sin efectos',
    obtenido: `${state.paymentStatus}; ${after.correos} correos`,
    cumple: ok,
    proveedor: { id: payment?.id, status: payment?.status },
  })
  expect(ok).toBe(true)
})

test('M5 · sin aviso (sin túnel): la conciliación encuentra el pago por la referencia y lo confirma', async ({ page }) => {
  test.skip(Boolean(HOMOLOGACION_PUBLIC_HOST), 'Con túnel el aviso llega solo: este caso se corre sin HOMOLOGACION_PUBLIC_HOST')
  const who = buyer('05')
  const orderNumber = await payWithOperator(page, who, 'APRO')
  await expect.poll(async () => (await tracking(orderNumber)).paymentStatus, { timeout: 180_000 }).toBe('PAID')
  const after = await effects(who.email, orderNumber)
  const ok = after.correos === 1 && after.ventasPos === 1
  registrar({
    caso: 'Sin aviso: conciliación por external_reference',
    orden: orderNumber,
    esperado: 'PAID por la conciliación; 1 correo, 1 venta',
    obtenido: `PAID; ${after.correos} correo, ${after.ventasPos} venta`,
    cumple: ok,
  })
  expect(ok).toBe(true)
})

test('M6 · devolución parcial y total desde el panel, contra MercadoPago', async ({ page }) => {
  test.skip(!shop.approved, 'Necesita el pago aprobado de M1')
  await restorePanelSession(page, shop.slug, shop.session)
  await openOrder(page, shop.slug, shop.approved)
  await refund(page, 'Homologación: devolución parcial', 500)
  await refund(page, 'Homologación: devolución del resto')
  await expect(page.getByText('Esta orden ya se devolvió por completo.')).toBeVisible()
  const state = await tracking(shop.approved)
  const refunds = await fetch(`https://api.mercadopago.com/v1/payments/${shop.approvedPaymentId}/refunds`, {
    headers: { Authorization: `Bearer ${CREDENTIALS.accessToken}` },
  }).then((res) => res.json() as Promise<{ amount: number; status: string }[]>)
  const ok = state.paymentStatus === 'REFUNDED' && Array.isArray(refunds) && refunds.length === 2
  registrar({
    caso: 'Devolución parcial (500) y total',
    orden: shop.approved,
    esperado: 'REFUNDED; dos devoluciones en MercadoPago',
    obtenido: `${state.paymentStatus}; ${Array.isArray(refunds) ? refunds.length : 0} devoluciones`,
    cumple: ok,
    proveedor: { devoluciones: Array.isArray(refunds) ? refunds.map((r) => ({ amount: r.amount, status: r.status })) : refunds },
  })
  expect(ok).toBe(true)
})
