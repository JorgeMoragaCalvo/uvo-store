// GAP-08. Los fallos de pasarela en el navegador, contra el backend real y los dobles de Stripe, Webpay y
// MercadoPago (e2e/doubles/gateways.ts). Cada caso va en la pasarela donde ocurre: Webpay no tiene webhook, así
// que su retorno perdido lo arregla la conciliación; los webhooks tardíos o repetidos son de MercadoPago y Stripe.
//
// Lo corren los dos proyectos (escritorio y móvil), cada uno con su propia tienda y su propia empresa del POS, así
// que no comparten órdenes ni ventas. Los pasos van en serie: el primero deja la tienda lista para vender y los
// reembolsos usan órdenes que pagaron casos anteriores.
//
// En cada caso se mira lo que ve el comprador y, además, lo que el comprador no ve: el estado de la orden, el
// stock, los correos, las ventas que recibe el POS y lo que recibe la pasarela. Una caída o un aviso repetido no
// puede dejar dos correos, dos ventas ni dos descuentos.

import { expect, test, type Page } from '@playwright/test'
import { BACKEND_URL, DOUBLES_URL, storeUrl } from '../support/env.ts'
import { http, storeHost } from '../support/http.ts'
import { queryE2e } from '../support/database.ts'
import { linkIn, mailsTo, waitForMail } from '../support/mail.ts'
import {
  acceptInvitation,
  cancelOrder,
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
import {
  amountOnGatewayPage,
  confirmWith,
  fillCheckoutForms,
  fillCheckoutUntilPayment,
  openProduct,
  type Buyer,
} from '../support/storefront.ts'
import {
  configureGateways,
  expireStripeSession,
  mercadoPagoPayments,
  mercadoPagoWebhook,
  setMercadoPagoPaymentStatus,
  stripeSessions,
  stripeWebhook,
  webpayTransactions,
} from '../support/gateways.ts'

test.describe.configure({ mode: 'serial' })

const PASSWORD = 'Clave-E2e-Pasarelas-2026'
const REGION = 'Metropolitana de Santiago'
const COMMUNE = 'Santiago'
const PRODUCT = { externalId: 50201, sku: 'PAS-CAFE-01', name: 'Café de pasarela', price: 9990, stock: 50 }
const MERCADOPAGO = { accessToken: 'TEST-e2e-access-token', publicKey: 'TEST-e2e-public-key', webhookSecret: 'e2e-mp-webhook-secret' }
/** Lo que tarda en salir lo que el outbox despacha cada segundo, con margen. */
const SETTLE_MS = 4000

/** Lo que van dejando los pasos. Cada proyecto corre en su propio worker, así que no se comparte. */
const shop = {
  slug: '',
  ownerEmail: '',
  session: '',
  pos: null as PosCredentials | null,
  posVersion: 1,
  expectedStock: PRODUCT.stock,
  /** Órdenes pagadas que los casos de reembolso devuelven. */
  webpayPaid: '',
  mercadoPagoPaid: '',
}

function buyer(caseId: string): Buyer {
  // Un comprador por caso: el checkout reutiliza la orden pendiente de un mismo cliente con el mismo carrito, y
  // una orden que un caso deja pendiente a propósito no puede colarse en el siguiente.
  return {
    email: `c${caseId}-${shop.slug}@e2e.test`,
    firstName: 'Ignacia',
    lastName: 'Fuentes',
    phone: '+56922222222',
    address: 'Los Aromos 1234',
    city: 'Santiago',
    postalCode: '8320000',
    region: REGION,
    commune: COMMUNE,
  }
}

// ---------------------------------------------------------------------------------------------- lecturas

interface Tracking {
  status: string
  paymentStatus: string
  total: number
}

/** El seguimiento público de la orden, con los estados en mayúscula como los enums (la API los da en minúscula). */
async function tracking(orderNumber: string): Promise<Tracking> {
  const result = await http('GET', `${BACKEND_URL}/api/v1/orders/track?order_number=${encodeURIComponent(orderNumber)}`, {
    headers: { Host: storeHost(shop.slug) },
  })
  expect(result.status, result.text).toBe(200)
  const json = result.json as Tracking
  return { status: json.status.toUpperCase(), paymentStatus: json.paymentStatus.toUpperCase(), total: Number(json.total) }
}

async function expectPayment(orderNumber: string, paymentStatus: string, timeout = 20_000): Promise<void> {
  await expect
    .poll(async () => (await tracking(orderNumber)).paymentStatus, { message: `pago de ${orderNumber}`, timeout })
    .toBe(paymentStatus)
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

/**
 * Lo que deja un pago confirmado, exactamente una vez: un correo de confirmación, una venta en el POS y una unidad
 * menos. Se espera a que lleguen y después un poco más, para que un duplicado tenga tiempo de aparecer.
 */
async function expectConfirmedOnce(email: string, orderNumber: string): Promise<void> {
  shop.expectedStock -= 1
  await expect.poll(async () => (await confirmationMails(email, orderNumber)).length, { timeout: 20_000 }).toBe(1)
  await expect.poll(async () => (await posSales(orderNumber)).length, { timeout: 20_000 }).toBe(1)
  await expect.poll(stock, { timeout: 20_000 }).toBe(shop.expectedStock)
  await new Promise((resolve) => setTimeout(resolve, SETTLE_MS))
  expect(await confirmationMails(email, orderNumber)).toHaveLength(1)
  expect(await posSales(orderNumber)).toHaveLength(1)
  expect(await stock()).toBe(shop.expectedStock)
}

async function expectNothingConfirmed(email: string, orderNumber: string): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, SETTLE_MS))
  expect(await confirmationMails(email, orderNumber)).toHaveLength(0)
  expect(await posSales(orderNumber)).toHaveLength(0)
  expect(await stock()).toBe(shop.expectedStock)
}

async function startCheckout(page: Page, who: Buyer): Promise<void> {
  await openProduct(page, shop.slug, PRODUCT.name)
  await fillCheckoutUntilPayment(page, who)
}

/** Tras volver con «atrás», el checkout puede venir de la caché del navegador (con los datos) o recargado (sin ellos). */
async function backToPaymentStep(page: Page, who: Buyer): Promise<void> {
  await expect(page).toHaveURL(/\/checkout/)
  const paymentStep = page.getByRole('heading', { name: 'Pago', level: 2 })
  const contactStep = page.getByRole('heading', { name: 'Contacto', level: 2 })
  await expect(paymentStep.or(contactStep)).toBeVisible()
  if (await contactStep.isVisible()) await fillCheckoutForms(page, who)
  await expectCartKept(page)
}

/** El carrito sigue con su producto: lo dice el botón del encabezado (el resumen del checkout solo trae totales). */
async function expectCartKept(page: Page): Promise<void> {
  await expect(page.getByRole('button', { name: 'Abrir carrito, 1 producto' })).toBeVisible()
}

const REJECTED = 'El pago fue rechazado o no se pudo confirmar. Puedes intentarlo de nuevo o elegir otro medio de pago.'
const CANCELED = 'No se completó el pago. Tus datos siguen aquí: puedes intentarlo de nuevo o elegir otro medio de pago.'

function clp(amount: number): string {
  return new Intl.NumberFormat('es-CL', { style: 'currency', currency: 'CLP', maximumFractionDigits: 0 }).format(amount)
}

// ---------------------------------------------------------------------------------------------- preparación

test('preparación: la tienda vende con las tres pasarelas', async ({ page }, testInfo) => {
  const project = testInfo.project.name
  shop.slug = `pasarelas-${project}`
  shop.ownerEmail = `duena-${shop.slug}@e2e.test`
  shop.pos = newPosCredentials(project === 'mobile' ? 990082 : 990081)

  const created = await createStoreViaApi({
    slug: shop.slug,
    storeName: `Pasarelas ${project}`,
    adminName: 'Dueña Pasarelas',
    adminEmail: shop.ownerEmail,
    pos: { ...shop.pos, companyName: `Empresa pasarelas ${project}`, apiUrl: DOUBLES_URL },
  })
  expect(created.status, created.text).toBe(200)

  const invitation = await waitForMail(shop.ownerEmail, `Te invitaron a administrar Pasarelas ${project}`)
  await acceptInvitation(page, linkIn(invitation, '/admin/aceptar-invitacion?token='), PASSWORD)
  shop.session = await panelSession(page)

  await createShippingZone(page, shop.slug, REGION)
  await disableShipping(page, shop.slug)
  await configureGateways(page, shop.slug, MERCADOPAGO)

  const synced = await syncProduct(shop.pos, { ...PRODUCT, version: shop.posVersion, categoryName: 'Cafetería' })
  expect(synced.status, synced.text).toBe(200)
  expect(await stock()).toBe(PRODUCT.stock)

  // El checkout ofrece las tres.
  await page.goto(storeUrl(shop.slug, '/'))
  const config = await http('GET', `${BACKEND_URL}/api/v1/checkout/config`, { headers: { Host: storeHost(shop.slug) } })
  expect(config.json).toMatchObject({ stripeEnabled: true, webpayEnabled: true, mercadopagoEnabled: true })
})

// ---------------------------------------------------------------------------------------------- Webpay

test('1 · Webpay: pago aprobado, y recargar la página de éxito no repite nada', async ({ page }) => {
  const who = buyer('01')
  await startCheckout(page, who)
  const orderNumber = await confirmWith(page, 'Webpay')

  await page.getByRole('button', { name: 'Pagar', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/order-success\\?order=${orderNumber}$`))
  await expect(page.getByText('¡Gracias por tu compra!')).toBeVisible()
  await page.reload()
  await expect(page.getByText('¡Gracias por tu compra!')).toBeVisible()

  expect((await tracking(orderNumber)).paymentStatus).toBe('PAID')
  await expectConfirmedOnce(who.email, orderNumber)
  const [tx] = await webpayTransactions(orderNumber)
  expect(tx).toMatchObject({ status: 'AUTHORIZED', commits: 1 })
  shop.webpayPaid = orderNumber
})

test('2 · Webpay: un rechazo vuelve al checkout con el carrito, y el reintento paga una orden nueva', async ({ page }) => {
  const who = buyer('02')
  await startCheckout(page, who)
  const rejected = await confirmWith(page, 'Webpay')

  await page.getByRole('button', { name: 'Rechazar' }).click()
  await expect(page).toHaveURL(/\/checkout\?error=webpay$/)
  await expect(page.getByText(REJECTED)).toBeVisible()
  await expect(page.getByText('¡Gracias por tu compra!')).toHaveCount(0)
  await expect(page.getByRole('heading', { name: 'Pago', level: 2 })).toBeVisible()
  await expectCartKept(page)
  expect(await tracking(rejected)).toMatchObject({ status: 'PENDING', paymentStatus: 'FAILED' })
  await expectNothingConfirmed(who.email, rejected)

  // La orden rechazada ya no se puede pagar: el reintento crea otra.
  const retried = await confirmWith(page, 'Webpay')
  expect(retried).not.toBe(rejected)
  await page.getByRole('button', { name: 'Pagar', exact: true }).click()
  await expect(page.getByText('¡Gracias por tu compra!')).toBeVisible()
  await expectConfirmedOnce(who.email, retried)
  expect(await posSales(rejected)).toHaveLength(0)
})

test('3 · Webpay: «atrás» desde la pasarela y una anulación no pierden la orden', async ({ page }) => {
  const who = buyer('03')
  await startCheckout(page, who)
  const orderNumber = await confirmWith(page, 'Webpay')

  await page.goBack()
  await backToPaymentStep(page, who)
  expect(await confirmWith(page, 'Webpay')).toBe(orderNumber)

  await page.getByRole('button', { name: 'Anular' }).click()
  await expect(page).toHaveURL(/\/checkout\?canceled=1$/)
  await expect(page.getByText(CANCELED)).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Pago', level: 2 })).toBeVisible()
  expect(await tracking(orderNumber)).toMatchObject({ paymentStatus: 'PENDING' })

  // La misma orden, con una transacción nueva.
  expect(await confirmWith(page, 'Webpay')).toBe(orderNumber)
  await page.getByRole('button', { name: 'Pagar', exact: true }).click()
  await expect(page.getByText('¡Gracias por tu compra!')).toBeVisible()
  await expectConfirmedOnce(who.email, orderNumber)
  const transactions = await webpayTransactions(orderNumber)
  expect(transactions.map((tx) => tx.status)).toEqual(['INITIALIZED', 'ABORTED', 'AUTHORIZED'])
})

test('4 · Webpay: la sesión expira, el retorno viejo no cambia nada y el reintento paga', async ({ page }) => {
  const who = buyer('04')
  await startCheckout(page, who)
  const orderNumber = await confirmWith(page, 'Webpay')
  const [expired] = await webpayTransactions(orderNumber)

  await page.getByRole('button', { name: 'Dejar expirar' }).click()
  await expect(page).toHaveURL(/\/checkout\?canceled=1$/)
  await expect(page.getByText(CANCELED)).toBeVisible()

  // Transbank, o alguien, repite el retorno con el token vencido: el commit falla y la orden no se toca.
  const replay = await http('POST', `${BACKEND_URL}/api/v1/webpay/return`, {
    headers: { Host: storeHost(shop.slug), 'Content-Type': 'application/x-www-form-urlencoded' },
    body: `token_ws=${expired.token}`,
  })
  expect(replay.status).toBe(302)
  expect(String(replay.headers.location)).toMatch(/\/checkout\?error=webpay$/)
  expect(await tracking(orderNumber)).toMatchObject({ status: 'PENDING', paymentStatus: 'PENDING' })

  expect(await confirmWith(page, 'Webpay')).toBe(orderNumber)
  await page.getByRole('button', { name: 'Pagar', exact: true }).click()
  await expect(page.getByText('¡Gracias por tu compra!')).toBeVisible()
  await expectConfirmedOnce(who.email, orderNumber)
  expect((await webpayTransactions(orderNumber)).map((tx) => tx.commits)).toEqual([0, 1])
})

test('5 · Webpay: el comprador paga y no vuelve; la conciliación confirma la orden', async ({ page }) => {
  // La conciliación del E2E mira las órdenes pendientes de más de un minuto (ver el lanzador).
  test.setTimeout(180_000)
  const who = buyer('05')
  await startCheckout(page, who)
  const orderNumber = await confirmWith(page, 'Webpay')

  await page.getByRole('button', { name: 'Pagar sin volver' }).click()
  await expect(page.getByText('Pago autorizado')).toBeVisible()
  await page.close()

  expect((await tracking(orderNumber)).paymentStatus).toBe('PENDING')
  await expectPayment(orderNumber, 'PAID', 150_000)
  await expectConfirmedOnce(who.email, orderNumber)
  // status, nunca commit: la conciliación pregunta, no confirma.
  expect((await webpayTransactions(orderNumber))[0].commits).toBe(0)
})

// ---------------------------------------------------------------------------------------------- MercadoPago

test('6 · MercadoPago: aprobado, y la página espera al webhook antes de dar las gracias', async ({ page }) => {
  const who = buyer('06')
  await startCheckout(page, who)
  const orderNumber = await confirmWith(page, 'MercadoPago')

  await page.getByRole('button', { name: 'Aprobar' }).click()
  await expect(page).toHaveURL(new RegExp(`/order-success\\?order=${orderNumber}&gateway=mercadopago$`))
  await expect(page.getByText('Confirmando tu pago...')).toBeVisible()
  await expect(page.getByText('¡Gracias por tu compra!')).toHaveCount(0)
  expect((await tracking(orderNumber)).paymentStatus).toBe('PENDING')

  // El webhook llega unos segundos después.
  await page.waitForTimeout(3000)
  const [payment] = await mercadoPagoPayments(orderNumber)
  const notified = await mercadoPagoWebhook(shop.slug, MERCADOPAGO.webhookSecret, payment.id)
  expect(notified.status, notified.text).toBe(200)

  await expect(page.getByText('¡Gracias por tu compra!')).toBeVisible({ timeout: 20_000 })
  await expectConfirmedOnce(who.email, orderNumber)
  shop.mercadoPagoPaid = orderNumber
})

test('7 · MercadoPago: el mismo webhook tres veces, a la vez, confirma una sola vez', async ({ page }) => {
  const who = buyer('07')
  await startCheckout(page, who)
  const orderNumber = await confirmWith(page, 'MercadoPago')
  await page.getByRole('button', { name: 'Aprobar' }).click()
  await expect(page.getByText('Confirmando tu pago...')).toBeVisible()

  const [payment] = await mercadoPagoPayments(orderNumber)
  const results = await Promise.all([1, 2, 3].map(() => mercadoPagoWebhook(shop.slug, MERCADOPAGO.webhookSecret, payment.id)))
  for (const result of results) expect(result.status, result.text).toBe(200)

  await expect(page.getByText('¡Gracias por tu compra!')).toBeVisible({ timeout: 20_000 })
  await expectConfirmedOnce(who.email, orderNumber)
})

test('8 · MercadoPago: pago pendiente; la recarga sigue diciendo «en proceso» y el aviso posterior lo confirma', async ({ page }) => {
  const who = buyer('08')
  await startCheckout(page, who)
  const orderNumber = await confirmWith(page, 'MercadoPago')

  await page.getByRole('button', { name: 'Dejar pendiente' }).click()
  await expect(page).toHaveURL(/\/checkout\?pending=1$/)
  await expect(page.getByRole('heading', { name: 'Tu pago está en proceso' })).toBeVisible()
  await expect(page.getByText(`Número de pedido: ${orderNumber}`)).toBeVisible()
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Tu pago está en proceso' })).toBeVisible()

  // Un aviso «en proceso» no cambia nada.
  const [payment] = await mercadoPagoPayments(orderNumber)
  expect((await mercadoPagoWebhook(shop.slug, MERCADOPAGO.webhookSecret, payment.id)).status).toBe(200)
  expect((await tracking(orderNumber)).paymentStatus).toBe('PENDING')
  await expectNothingConfirmed(who.email, orderNumber)

  // MercadoPago acredita el pago y avisa.
  await setMercadoPagoPaymentStatus(payment.id, 'approved')
  expect((await mercadoPagoWebhook(shop.slug, MERCADOPAGO.webhookSecret, payment.id)).status).toBe(200)
  await expectPayment(orderNumber, 'PAID')
  await expectConfirmedOnce(who.email, orderNumber)
})

test('9 · MercadoPago: un rechazo vuelve al checkout con el carrito y su aviso deja el pago fallido', async ({ page }) => {
  const who = buyer('09')
  await startCheckout(page, who)
  const orderNumber = await confirmWith(page, 'MercadoPago')

  await page.getByRole('button', { name: 'Rechazar' }).click()
  await expect(page).toHaveURL(/\/checkout\?error=mercadopago$/)
  await expect(page.getByText(REJECTED)).toBeVisible()
  await expectCartKept(page)

  const [payment] = await mercadoPagoPayments(orderNumber)
  expect((await mercadoPagoWebhook(shop.slug, MERCADOPAGO.webhookSecret, payment.id)).status).toBe(200)
  await expectPayment(orderNumber, 'FAILED')
  await expectNothingConfirmed(who.email, orderNumber)
})

test('10 · MercadoPago: un cobro sobre una orden que el panel canceló no la revive', async ({ page, browser }) => {
  const who = buyer('10')
  await startCheckout(page, who)
  const orderNumber = await confirmWith(page, 'MercadoPago')

  // Mientras el comprador está en la pasarela, la tienda cancela la orden.
  const panel = await browser.newPage()
  await restorePanelSession(panel, shop.slug, shop.session)
  await openOrder(panel, shop.slug, orderNumber)
  await cancelOrder(panel)
  await panel.close()

  // El comprador paga igual: MercadoPago lo cobra, y la página no le da las gracias.
  await page.getByRole('button', { name: 'Aprobar' }).click()
  await expect(page).toHaveURL(/\/checkout\?error=mercadopago$/, { timeout: 20_000 })
  await expect(page.getByText(REJECTED)).toBeVisible()

  const [payment] = await mercadoPagoPayments(orderNumber)
  for (let i = 0; i < 2; i++) {
    expect((await mercadoPagoWebhook(shop.slug, MERCADOPAGO.webhookSecret, payment.id)).status).toBe(200)
  }
  expect(await tracking(orderNumber)).toMatchObject({ status: 'CANCELLED', paymentStatus: 'FAILED' })
  await expectNothingConfirmed(who.email, orderNumber)

  // Queda la nota para reembolsar el cobro, una sola vez aunque el aviso se repita. El panel no muestra el
  // historial de la orden, así que se lee de la base.
  const notes = await queryE2e<{ notes: string }>(
    `select h.notes from order_status_history h join orders o on o.id = h.order_id
      where o.order_number = $1 and h.notes like 'Pago confirmado por la pasarela sobre una orden cerrada%'`,
    [orderNumber],
  )
  expect(notes).toHaveLength(1)
  expect(notes[0].notes).toContain(`referencia ${payment.id}`)
})

// ---------------------------------------------------------------------------------------------- Stripe

test('11 · Stripe: pago aprobado y webhook repetido confirman una sola vez', async ({ page }) => {
  const who = buyer('11')
  await startCheckout(page, who)
  const orderNumber = await confirmWith(page, 'Tarjeta (Stripe)')

  await page.getByRole('button', { name: 'Pagar', exact: true }).click()
  await expect(page).toHaveURL(/\/order-success\?session_id=cs_test_e2e\d+$/)
  await expect(page.getByText('¡Gracias por tu compra!')).toBeVisible()

  const [session] = await stripeSessions(orderNumber)
  for (let i = 0; i < 2; i++) {
    const result = await stripeWebhook(shop.slug, 'checkout.session.completed', session)
    expect(result.status, result.text).toBe(200)
  }
  await expectConfirmedOnce(who.email, orderNumber)
})

test('12 · Stripe: la sesión vuelve sin pagar y la página dice «en proceso», no «gracias»', async ({ page }) => {
  const who = buyer('12')
  await startCheckout(page, who)
  const orderNumber = await confirmWith(page, 'Tarjeta (Stripe)')

  await page.getByRole('button', { name: 'Volver sin pagar' }).click()
  await expect(page).toHaveURL(/\/order-success\?session_id=/)
  await expect(page.getByRole('heading', { name: 'Tu pago está en proceso' })).toBeVisible()
  await expect(page.getByText('¡Gracias por tu compra!')).toHaveCount(0)
  await expect(page.getByText(`Número de pedido: ${orderNumber}`)).toBeVisible()
  expect((await tracking(orderNumber)).paymentStatus).toBe('PENDING')
  await expectNothingConfirmed(who.email, orderNumber)
})

test('13 · Stripe: la sesión expira; con «atrás» y reintento se paga una orden nueva', async ({ page }) => {
  const who = buyer('13')
  await startCheckout(page, who)
  const expiredOrder = await confirmWith(page, 'Tarjeta (Stripe)')

  const [session] = await stripeSessions(expiredOrder)
  await expireStripeSession(session.id)
  const expired = await stripeWebhook(shop.slug, 'checkout.session.expired', { ...session, status: 'expired' })
  expect(expired.status, expired.text).toBe(200)
  expect(await tracking(expiredOrder)).toMatchObject({ status: 'CANCELLED', paymentStatus: 'FAILED' })

  await page.goBack()
  await backToPaymentStep(page, who)
  const retried = await confirmWith(page, 'Tarjeta (Stripe)')
  expect(retried).not.toBe(expiredOrder)
  await page.getByRole('button', { name: 'Pagar', exact: true }).click()
  await expect(page.getByText('¡Gracias por tu compra!')).toBeVisible()
  await expectConfirmedOnce(who.email, retried)
  expect(await posSales(expiredOrder)).toHaveLength(0)
})

// ---------------------------------------------------------------------------------------------- reembolsos

test('14 · Webpay: devolución parcial y después el resto, desde el panel', async ({ page }) => {
  const total = (await tracking(shop.webpayPaid)).total
  await restorePanelSession(page, shop.slug, shop.session)
  await openOrder(page, shop.slug, shop.webpayPaid)

  await refund(page, 'Devolución parcial pedida por el cliente', 1000)
  await expect(refundRow(page, 'Devuelto')).toHaveText(clp(1000))
  await expect(refundRow(page, 'Por devolver')).toHaveText(clp(total - 1000))
  expect((await tracking(shop.webpayPaid)).paymentStatus).toBe('PAID')
  expect(await stock()).toBe(shop.expectedStock)

  await refund(page, 'El cliente devuelve el resto')
  await expect(page.getByText('Esta orden ya se devolvió por completo.')).toBeVisible()
  expect(await tracking(shop.webpayPaid)).toMatchObject({ status: 'REFUNDED', paymentStatus: 'REFUNDED' })
  // La devolución total deshace la compra: la unidad vuelve al stock.
  shop.expectedStock += 1
  expect(await stock()).toBe(shop.expectedStock)

  const [tx] = await webpayTransactions(shop.webpayPaid)
  expect(tx.refunds).toEqual([1000, total - 1000])

  // Y el comprador lo ve en el seguimiento.
  await page.goto(storeUrl(shop.slug, `/track-order?order=${shop.webpayPaid}`))
  await page.getByRole('button', { name: 'Buscar' }).click()
  await expect(page.getByText('Reembolsado')).toBeVisible()
})

test('15 · MercadoPago: devolución total desde el panel', async ({ page }) => {
  const total = (await tracking(shop.mercadoPagoPaid)).total
  await restorePanelSession(page, shop.slug, shop.session)
  await openOrder(page, shop.slug, shop.mercadoPagoPaid)

  await refund(page, 'Pedido devuelto completo')
  await expect(page.getByText('Esta orden ya se devolvió por completo.')).toBeVisible()
  expect(await tracking(shop.mercadoPagoPaid)).toMatchObject({ paymentStatus: 'REFUNDED' })
  shop.expectedStock += 1
  expect(await stock()).toBe(shop.expectedStock)

  const [payment] = await mercadoPagoPayments(shop.mercadoPagoPaid)
  expect(payment.refunds).toEqual([total])
})

// ---------------------------------------------------------------------------------------------- precio

test('16 · Webpay: el precio cambia en el POS con el comprador en el paso «Pago»', async ({ page }) => {
  // Al final: cambia el precio del producto para lo que venga después.
  const who = buyer('16')
  await startCheckout(page, who)

  shop.posVersion += 1
  const repriced = await syncProduct(shop.pos as PosCredentials, {
    ...PRODUCT,
    price: 12990,
    stock: shop.expectedStock,
    version: shop.posVersion,
    categoryName: 'Cafetería',
  })
  expect(repriced.status, repriced.text).toBe(200)

  await page.getByLabel('Webpay').check()
  await page.getByRole('button', { name: 'Confirmar Pedido' }).click()
  const notice = page.getByText(/El total de tu pedido cambió de \$[\d.]+ a \$[\d.]+\. Revisa el carrito y confirma de nuevo\./)
  await expect(notice).toBeVisible()
  const newTotal = Number(/a \$([\d.]+)\./.exec(await notice.innerText())?.[1].replace(/\./g, ''))
  await expect(page.getByText(clp(newTotal)).first()).toBeVisible()

  // Confirmar otra vez es aceptar el precio nuevo: la pasarela cobra eso.
  const orderNumber = await confirmWith(page, 'Webpay')
  expect(await amountOnGatewayPage(page)).toBe(newTotal)
  await page.getByRole('button', { name: 'Pagar', exact: true }).click()
  await expect(page.getByText('¡Gracias por tu compra!')).toBeVisible()
  expect((await tracking(orderNumber)).total).toBe(newTotal)
  expect((await webpayTransactions(orderNumber))[0].amount).toBe(newTotal)
  await expectConfirmedOnce(who.email, orderNumber)
})
