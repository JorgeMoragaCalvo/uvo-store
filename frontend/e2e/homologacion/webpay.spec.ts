// IMP-07. Homologación de Webpay Plus Mall contra el ambiente de integración REAL de Transbank: el formulario, el banco
// simulado, el retorno, la conciliación y las devoluciones de verdad, no los dobles. Recorre la matriz del
// GO_LIVE_CHECKLIST §10 en lo que Transbank permite provocar; el resto lo cubren las regresiones locales (ver
// docs/homologacion-pagos.md).
//
//   HOMOLOGACION=webpay npm run e2e:homologacion
//   HOMOLOGACION=webpay HOMOLOGACION_LARGOS=1 npm run e2e:homologacion   (suma el vencimiento del formulario)
//
// Datos públicos de prueba de Transbank: tarjeta VISA 4051 8856 0044 6623, cualquier vencimiento futuro, CVV 123;
// banco simulado con RUT 11.111.111-1 y clave 123. El comercio hijo 597055555536 es del Mall de integración.

import { expect, test, type Page } from '@playwright/test'
import { BACKEND_URL, DOUBLES_URL, HOMOLOGACION, storeUrl } from '../support/env.ts'
import { http, storeHost } from '../support/http.ts'
import { linkIn, mailsTo, waitForMail } from '../support/mail.ts'
import {
  acceptInvitation,
  createShippingZone,
  disableShipping,
  openOrder,
  panelSession,
  refund,
  refundRow,
  restorePanelSession,
} from '../support/panel.ts'
import { createStoreViaApi } from '../support/platform.ts'
import { newPosCredentials, posOrdersReceived, syncProduct, type PosCredentials } from '../support/pos.ts'
import { fillCheckoutUntilPayment, openProduct, type Buyer } from '../support/storefront.ts'
import { escribirActa, lastOrderOf, paymentIdOf, registrar, transbankStatus } from './acta.ts'

test.skip(!HOMOLOGACION.includes('webpay'), 'HOMOLOGACION no incluye webpay')
test.describe.configure({ mode: 'serial' })

const PASSWORD = 'Clave-Homologacion-Webpay-2026'
const REGION = 'Metropolitana de Santiago'
const CHILD_COMMERCE_CODE = '597055555536'
const PRODUCT = { externalId: 70101, sku: 'HOM-WP-01', name: 'Café homologación Webpay', price: 2990, stock: 40 }
const SETTLE_MS = 4000
/** La conciliación del lanzador mira las órdenes pendientes de más de un minuto, cada 5 s. */
const RECONCILIATION_MS = 90_000

const shop = {
  slug: `homologacion-webpay-${Date.now().toString(36)}`,
  ownerEmail: '',
  session: '',
  pos: null as PosCredentials | null,
  expectedStock: PRODUCT.stock,
  paid: '',
  paidAt: 0,
}

function buyer(caseId: string): Buyer {
  return {
    email: `w${caseId}-${shop.slug}@e2e.test`,
    firstName: 'Tomás',
    lastName: 'Rojas',
    phone: '+56933333333',
    address: 'Av. Matta 456',
    city: 'Santiago',
    postalCode: '8320000',
    region: REGION,
    commune: 'Santiago',
  }
}

// ---------------------------------------------------------------------------------------------- lecturas

interface Tracking {
  status: string
  paymentStatus: string
  total: number
}

async function tracking(orderNumber: string): Promise<Tracking> {
  const result = await http('GET', `${BACKEND_URL}/api/v1/orders/track?order_number=${encodeURIComponent(orderNumber)}`, {
    headers: { Host: storeHost(shop.slug) },
  })
  expect(result.status, result.text).toBe(200)
  const json = result.json as Tracking
  return { status: json.status.toUpperCase(), paymentStatus: json.paymentStatus.toUpperCase(), total: Number(json.total) }
}

async function stock(): Promise<number> {
  const result = await http('GET', `${BACKEND_URL}/api/v1/products?search=${encodeURIComponent(PRODUCT.name)}`, {
    headers: { Host: storeHost(shop.slug) },
  })
  const products = (result.json as { content: { sku: string; stock: number }[] }).content
  return products.find((product) => product.sku === PRODUCT.sku)?.stock ?? -1
}

async function posSales(orderNumber: string) {
  return (await posOrdersReceived()).filter((call) => call.idempotencyKey === `${orderNumber}:${shop.pos?.companyId}`)
}

async function confirmationMails(email: string, orderNumber: string) {
  return (await mailsTo(email)).filter((mail) => mail.subject.includes(`Confirmación de tu pedido ${orderNumber}`))
}

async function effects(email: string, orderNumber: string) {
  await new Promise((resolve) => setTimeout(resolve, SETTLE_MS))
  return {
    correos: (await confirmationMails(email, orderNumber)).length,
    ventasPos: (await posSales(orderNumber)).length,
    stock: await stock(),
  }
}

// ---------------------------------------------------------------------------------------------- Transbank

/** Elige Webpay, confirma y espera el formulario de Transbank. Devuelve el número de la orden creada. */
async function goToTransbank(page: Page, who: Buyer): Promise<string> {
  await openProduct(page, shop.slug, PRODUCT.name)
  await fillCheckoutUntilPayment(page, who)
  await page.getByLabel('Webpay').check()
  await page.getByRole('button', { name: 'Confirmar Pedido' }).click()
  await expect(page).toHaveURL(/webpay3gint\.transbank\.cl/, { timeout: 60_000 })
  await expect(page.getByRole('button', { name: /Tarjetas/ })).toBeVisible({ timeout: 60_000 })
  return lastOrderOf(who.email)
}

/**
 * Las tarjetas de crédito de prueba de Transbank. En la homologación, la VISA usada varias veces seguidas a veces
 * respondió «Intenta pagar con otra tarjeta» al validar el número; entonces se usa la siguiente.
 */
const TEST_CARDS = ['4051885600446623', '5186059559590568']

/** Paga con una tarjeta de prueba y decide en el banco simulado. Termina en el «Continuar» que vuelve a la tienda. */
async function payOnTransbank(page: Page, decision: 'Aceptar' | 'Rechazar'): Promise<void> {
  await page.getByRole('button', { name: /Tarjetas/ }).click()
  for (const card of TEST_CARDS) {
    await page.locator('#card-number').fill('')
    await page.locator('#card-number').pressSequentially(card, { delay: 30 })
    await page.getByRole('button', { name: 'Continuar' }).click()
    const refused = page.getByText('Intenta pagar con otra tarjeta')
    await expect(page.locator('#card-exp').or(refused)).toBeVisible({ timeout: 30_000 })
    if (await page.locator('#card-exp').isVisible()) break
  }
  await page.locator('#card-exp').pressSequentially('1230', { delay: 50 })
  await page.locator('#card-cvv').pressSequentially('123', { delay: 50 })
  await page.getByRole('button', { name: 'Pagar' }).click()
  await expect(page).toHaveURL(/authenticator\.cgi/, { timeout: 60_000 })
  await page.locator('#rutClient').fill('111111111')
  await page.locator('#passwordClient').fill('123')
  await page.getByRole('button', { name: 'Aceptar' }).click()
  await expect(page.locator('#vci')).toBeVisible({ timeout: 60_000 })
  await page.locator('#vci').selectOption({ label: decision })
  await page.getByRole('button', { name: 'Continuar' }).click()
}

// ---------------------------------------------------------------------------------------------- preparación

test.afterAll(() => {
  const base = escribirActa('webpay')
  console.log(`[homologación] acta en ${base}.md`)
})

test('preparación: una tienda con Webpay de integración y el POS de prueba', async ({ page }) => {
  shop.ownerEmail = `duena-${shop.slug}@e2e.test`
  shop.pos = newPosCredentials(990700 + Math.floor(Math.random() * 99))

  const created = await createStoreViaApi({
    slug: shop.slug,
    storeName: 'Homologación Webpay',
    adminName: 'Dueña Homologación',
    adminEmail: shop.ownerEmail,
    pos: { ...shop.pos, companyName: 'Empresa homologación Webpay', apiUrl: DOUBLES_URL },
  })
  expect(created.status, created.text).toBe(200)

  const invitation = await waitForMail(shop.ownerEmail, 'Te invitaron a administrar Homologación Webpay')
  await acceptInvitation(page, linkIn(invitation, '/admin/aceptar-invitacion?token='), PASSWORD)
  shop.session = await panelSession(page)
  await createShippingZone(page, shop.slug, REGION)
  await disableShipping(page, shop.slug)

  await page.goto(storeUrl(shop.slug, '/admin/payment-gateways'))
  await page.locator('#WEBPAY-childCommerceCode').fill(CHILD_COMMERCE_CODE)
  await page.locator('#WEBPAY-enabled').click()
  const card = page.locator('[data-slot="card"]').filter({ hasText: 'Webpay Plus (Transbank)' })
  await card.getByRole('button', { name: 'Guardar' }).click()
  await expect(page.getByText('Webpay Plus (Transbank) actualizado')).toBeVisible()

  const synced = await syncProduct(shop.pos, { ...PRODUCT, version: 1, categoryName: 'Cafetería' })
  expect(synced.status, synced.text).toBe(200)
  expect(await stock()).toBe(PRODUCT.stock)
})

// ---------------------------------------------------------------------------------------------- casos

test('W1 · pago aprobado: importe correcto, pagado una vez, stock y venta al POS una vez', async ({ page }) => {
  const who = buyer('01')
  const orderNumber = await goToTransbank(page, who)
  const total = (await tracking(orderNumber)).total
  await expect(page.getByText(new Intl.NumberFormat('es-CL').format(total)).first()).toBeVisible()

  await payOnTransbank(page, 'Aceptar')
  await expect(page).toHaveURL(new RegExp(`/order-success\\?order=${orderNumber}$`), { timeout: 60_000 })
  await expect(page.getByText('¡Gracias por tu compra!')).toBeVisible()
  shop.paid = orderNumber
  shop.paidAt = Date.now()

  // Recargar la página de éxito no confirma nada otra vez.
  await page.reload()
  await expect(page.getByText('¡Gracias por tu compra!')).toBeVisible()

  shop.expectedStock -= 1
  const after = await effects(who.email, orderNumber)
  const transbank = await transbankStatus(orderNumber)
  const state = await tracking(orderNumber)
  const ok = state.paymentStatus === 'PAID' && after.correos === 1 && after.ventasPos === 1
    && after.stock === shop.expectedStock && transbank.status === 'AUTHORIZED' && transbank.amount === total
  registrar({
    caso: 'Pago aprobado',
    orden: orderNumber,
    esperado: `PAID por ${total}; 1 correo, 1 venta POS, stock ${shop.expectedStock}`,
    obtenido: `${state.paymentStatus}; ${after.correos} correo, ${after.ventasPos} venta POS, stock ${after.stock}`,
    cumple: ok,
    proveedor: { ...transbank },
  })
  expect(ok).toBe(true)
})

test('W2 · retorno repetido al instante: no repite correo, venta ni stock', async () => {
  // Transbank vuelve por GET con token_ws; repetirlo es lo que haría un doble clic o un reintento del navegador.
  const token = await paymentIdOf(shop.paid)
  const replay = await http('GET', `${BACKEND_URL}/api/v1/webpay/return?token_ws=${token}`, {
    headers: { Host: storeHost(shop.slug) },
  })
  const after = await effects(buyer('01').email, shop.paid)
  const ok = replay.status === 302 && after.correos === 1 && after.ventasPos === 1 && after.stock === shop.expectedStock
  registrar({
    caso: 'Retorno repetido (segundo commit al instante)',
    orden: shop.paid,
    esperado: 'Redirección; sin segundo correo, venta ni descuento',
    obtenido: `HTTP ${replay.status} a ${String(replay.headers.location).replace(/^https?:\/\/[^/]+/, '')}; ${after.correos} correo, ${after.ventasPos} venta, stock ${after.stock}`,
    cumple: ok,
  })
  expect(ok).toBe(true)
})

test('W3 · rechazo en el banco: no se registra un pago y el comprador vuelve al checkout con su carrito', async ({ page }) => {
  const who = buyer('03')
  const orderNumber = await goToTransbank(page, who)
  await payOnTransbank(page, 'Rechazar')
  await expect(page).toHaveURL(/\/checkout\?error=webpay$/, { timeout: 60_000 })
  await expect(page.getByRole('button', { name: 'Abrir carrito, 1 producto' })).toBeVisible()

  const state = await tracking(orderNumber)
  const after = await effects(who.email, orderNumber)
  const transbank = await transbankStatus(orderNumber)
  const ok = state.paymentStatus === 'FAILED' && after.correos === 0 && after.ventasPos === 0 && after.stock === shop.expectedStock
  registrar({
    caso: 'Rechazo en el banco',
    orden: orderNumber,
    esperado: 'FAILED; checkout con el carrito; sin efectos',
    obtenido: `${state.paymentStatus}; ${after.correos} correos, ${after.ventasPos} ventas, stock ${after.stock}`,
    cumple: ok,
    proveedor: { ...transbank },
  })
  expect(ok).toBe(true)
})

test('W4 · «Anular compra y volver»: la orden sigue pendiente para reintentar', async ({ page }) => {
  const who = buyer('04')
  const orderNumber = await goToTransbank(page, who)
  await page.getByRole('button', { name: 'Anular compra y volver' }).click()
  await expect(page).toHaveURL(/\/checkout\?canceled=1$/, { timeout: 60_000 })

  const state = await tracking(orderNumber)
  const transbank = await transbankStatus(orderNumber)
  const ok = state.paymentStatus === 'PENDING'
  registrar({
    caso: 'Anulación en Transbank',
    orden: orderNumber,
    esperado: 'Checkout con aviso; orden PENDING',
    obtenido: `${state.paymentStatus}`,
    cumple: ok,
    proveedor: { ...transbank },
  })
  expect(ok).toBe(true)
})

test('W5 · abandono y «autoriza pero no vuelve»: la conciliación no inventa un pago', async ({ browser }) => {
  // Dos órdenes a la vez, para esperar una sola pasada de conciliación:
  //  - abandono: cierra la pestaña en el formulario;
  //  - autoriza en el banco y el navegador nunca vuelve (se corta el retorno), así que nadie confirma.
  const abandonPage = await browser.newPage()
  const abandonWho = buyer('05a')
  const abandoned = await goToTransbank(abandonPage, abandonWho)
  await abandonPage.close()

  const noReturnPage = await browser.newPage()
  const noReturnWho = buyer('05b')
  const noReturn = await goToTransbank(noReturnPage, noReturnWho)
  let returnBlocked = false
  await noReturnPage.route('**/api/v1/webpay/return**', async (route) => {
    returnBlocked = true
    await route.abort()
  })
  await payOnTransbank(noReturnPage, 'Aceptar')
  await expect.poll(() => returnBlocked, { timeout: 60_000 }).toBe(true)
  await noReturnPage.close()

  await new Promise((resolve) => setTimeout(resolve, RECONCILIATION_MS))

  for (const [caso, orderNumber, who] of [
    ['Abandono del formulario + conciliación', abandoned, abandonWho],
    ['Autoriza en el banco y no vuelve + conciliación', noReturn, noReturnWho],
  ] as const) {
    const state = await tracking(orderNumber)
    const after = await effects(who.email, orderNumber)
    const transbank = await transbankStatus(orderNumber)
    const ok = state.paymentStatus === 'PENDING' && after.correos === 0 && after.ventasPos === 0
      && transbank.status !== 'AUTHORIZED'
    registrar({
      caso,
      orden: orderNumber,
      esperado: 'Sigue PENDING tras la conciliación; Transbank sin cobro; sin efectos',
      obtenido: `${state.paymentStatus}; ${after.correos} correos, ${after.ventasPos} ventas`,
      cumple: ok,
      proveedor: { ...transbank },
    })
    expect(ok, caso).toBe(true)
  }
})

test('W6 · el token de una tienda en el retorno de otra no toca la orden', async () => {
  const other = `${shop.slug}-otra`
  const created = await createStoreViaApi({
    slug: other,
    storeName: 'Homologación otra tienda',
    adminName: 'Otra Dueña',
    adminEmail: `duena-${other}@e2e.test`,
  })
  expect(created.status, created.text).toBe(200)

  const token = await paymentIdOf(shop.paid)
  const crossed = await http('GET', `${BACKEND_URL}/api/v1/webpay/return?token_ws=${token}`, {
    headers: { Host: storeHost(other) },
  })
  const state = await tracking(shop.paid)
  const after = await effects(buyer('01').email, shop.paid)
  const location = String(crossed.headers.location)
  const ok = crossed.status === 302 && location.includes(storeHost(other)) && location.endsWith('/checkout?error=webpay')
    && state.paymentStatus === 'PAID' && after.correos === 1
  registrar({
    caso: 'Retorno con el token de otra tienda',
    orden: shop.paid,
    esperado: 'La otra tienda responde error; la orden no cambia ni se revela',
    obtenido: `HTTP ${crossed.status} a ${location.replace(/^https?:\/\/[^/]+/, '')}; orden ${state.paymentStatus}`,
    cumple: ok,
  })
  expect(ok).toBe(true)
})

test('W7 · devolución parcial y después el resto, desde el panel, contra Transbank', async ({ page }) => {
  const total = (await tracking(shop.paid)).total
  await restorePanelSession(page, shop.slug, shop.session)
  await openOrder(page, shop.slug, shop.paid)

  await refund(page, 'Homologación: devolución parcial', 1000)
  await expect(refundRow(page, 'Devuelto')).toHaveText(new Intl.NumberFormat('es-CL', { style: 'currency', currency: 'CLP', maximumFractionDigits: 0 }).format(1000))
  const partial = await transbankStatus(shop.paid)

  await refund(page, 'Homologación: devolución del resto')
  await expect(page.getByText('Esta orden ya se devolvió por completo.')).toBeVisible()
  const state = await tracking(shop.paid)
  shop.expectedStock += 1
  const after = await effects(buyer('01').email, shop.paid)
  const final = await transbankStatus(shop.paid)
  const ok = state.paymentStatus === 'REFUNDED' && after.stock === shop.expectedStock
  registrar({
    caso: 'Devolución parcial (1.000) y total',
    orden: shop.paid,
    esperado: `REFUNDED; stock repuesto a ${shop.expectedStock}`,
    obtenido: `${state.status}/${state.paymentStatus}; stock ${after.stock}`,
    cumple: ok,
    proveedor: { trasParcial: { ...partial }, trasTotal: { ...final }, total },
  })
  expect(ok).toBe(true)
})

test('W8 · retorno repetido minutos después: no cambia la orden', async () => {
  // Pasada la ventana de confirmación de Transbank (medida entre 30 y 55 s), repetir el retorno de una orden ya pagada
  // y devuelta no puede revivirla ni repetir efectos.
  const wait = Math.max(0, 70_000 - (Date.now() - shop.paidAt))
  await new Promise((resolve) => setTimeout(resolve, wait))
  const token = await paymentIdOf(shop.paid)
  const replay = await http('GET', `${BACKEND_URL}/api/v1/webpay/return?token_ws=${token}`, {
    headers: { Host: storeHost(shop.slug) },
  })
  const state = await tracking(shop.paid)
  const after = await effects(buyer('01').email, shop.paid)
  const ok = replay.status === 302 && state.paymentStatus === 'REFUNDED' && after.correos === 1 && after.ventasPos === 1
  registrar({
    caso: 'Retorno repetido fuera de la ventana',
    orden: shop.paid,
    esperado: 'Redirección; la orden sigue REFUNDED; sin efectos nuevos',
    obtenido: `HTTP ${replay.status} a ${String(replay.headers.location).replace(/^https?:\/\/[^/]+/, '')}; ${state.paymentStatus}; ${after.correos} correo, ${after.ventasPos} venta`,
    cumple: ok,
  })
  expect(ok).toBe(true)
})

test('W9 · vencimiento del formulario de Transbank (largo, opcional)', async ({ page }) => {
  test.skip(!process.env.HOMOLOGACION_LARGOS, 'Solo con HOMOLOGACION_LARGOS=1: espera a que Transbank venza el formulario')
  test.setTimeout(20 * 60_000)
  const who = buyer('09')
  const orderNumber = await goToTransbank(page, who)
  const returns: string[] = []
  page.on('request', (request) => {
    if (request.url().includes('/api/v1/webpay/return')) {
      const params = request.method() === 'GET' ? new URL(request.url()).searchParams : new URLSearchParams(request.postData() ?? '')
      returns.push(`${request.method()} ${[...params.keys()].sort().join(',')}`)
    }
  })
  // En la homologación del 18-nov el formulario de integración, sin tocarlo, seguía abierto a los 18 minutos: no se
  // pudo observar cómo vuelve un vencimiento. Se registra sin conclusión en vez de fallar.
  const expired = await expect(page)
    .toHaveURL(/\/checkout\?/, { timeout: 18 * 60_000 })
    .then(() => true, () => false)
  const state = await tracking(orderNumber)
  if (!expired) {
    registrar({
      caso: 'Vencimiento del formulario',
      orden: orderNumber,
      esperado: 'Vuelve al checkout como anulado; la orden no queda pagada',
      obtenido: `Transbank no venció el formulario en 18 min sin interacción; orden ${state.paymentStatus}`,
      cumple: null,
    })
    expect(state.paymentStatus).not.toBe('PAID')
    return
  }
  const ok = state.paymentStatus !== 'PAID'
  registrar({
    caso: 'Vencimiento del formulario',
    orden: orderNumber,
    esperado: 'Vuelve al checkout como anulado; la orden no queda pagada',
    obtenido: `retorno ${returns.join(' | ')} → ${new URL(page.url()).pathname}${new URL(page.url()).search}; ${state.paymentStatus}`,
    cumple: ok,
  })
  expect(ok).toBe(true)
})
