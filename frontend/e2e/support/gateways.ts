// GAP-08. Las pasarelas desde el lado del test: configurarlas en el panel, firmar sus webhooks como lo hacen
// Stripe y MercadoPago, y leer lo que recibieron sus dobles.

import { createHmac, randomUUID } from 'node:crypto'
import { expect, type Page } from '@playwright/test'
import { http, storeHost, type HttpResult } from './http.ts'
import { BACKEND_URL, DOUBLES_URL, E2E_STRIPE_WEBHOOK_SECRET, storeUrl } from './env.ts'
import type { GatewayState, MercadoPagoPayment, StripeSession, WebpayTransaction } from '../doubles/gateways.ts'

export const WEBPAY_CHILD_COMMERCE_CODE = '597055555536'

export interface MercadoPagoCredentials {
  accessToken: string
  publicKey: string
  webhookSecret: string
}

/** Una tarjeta de «Pasarelas de pago»: el Card que contiene su interruptor y su «Guardar». */
function gatewayCard(page: Page, gateway: 'WEBPAY' | 'MERCADOPAGO') {
  return page
    .locator('div')
    .filter({ has: page.locator(`#${gateway}-enabled`) })
    .filter({ has: page.getByRole('button', { name: 'Guardar' }) })
    .last()
}

async function enableGateway(page: Page, gateway: 'WEBPAY' | 'MERCADOPAGO', fields: Record<string, string>, title: string) {
  const card = gatewayCard(page, gateway)
  for (const [key, value] of Object.entries(fields)) {
    await page.locator(`#${gateway}-${key}`).fill(value)
  }
  const toggle = page.locator(`#${gateway}-enabled`)
  if ((await toggle.getAttribute('aria-checked')) !== 'true') await toggle.click()
  await card.getByRole('button', { name: 'Guardar' }).click()
  await expect(page.getByText(`${title} actualizado`)).toBeVisible()
}

/** Webpay y MercadoPago en «Pasarelas de pago»; Stripe en la pestaña Stripe de «Config. general». */
export async function configureGateways(page: Page, slug: string, mercadoPago: MercadoPagoCredentials): Promise<void> {
  await page.goto(storeUrl(slug, '/admin/payment-gateways'))
  await expect(page.getByRole('heading', { name: 'Pasarelas de pago' })).toBeVisible()
  await enableGateway(page, 'WEBPAY', { childCommerceCode: WEBPAY_CHILD_COMMERCE_CODE }, 'Webpay Plus (Transbank)')
  await enableGateway(page, 'MERCADOPAGO', { ...mercadoPago }, 'MercadoPago')

  await page.goto(storeUrl(slug, '/admin/settings/general'))
  await page.getByRole('tab', { name: 'Stripe' }).click()
  const toggle = page.getByRole('switch', { name: 'Stripe habilitado' })
  if (!(await toggle.isChecked())) await toggle.click()
  await page.getByLabel('Public key').fill('pk_test_e2e')
  await page.getByLabel('Secret key').fill('sk_test_e2e_only')
  await page.getByRole('button', { name: 'Guardar cambios' }).click()
  await expect(page.getByText('Configuración guardada')).toBeVisible()
}

// ---------------------------------------------------------------------------------------------- dobles

export async function gatewayState(): Promise<GatewayState> {
  return (await http('GET', `${DOUBLES_URL}/__doubles/gateways`)).json as GatewayState
}

export async function webpayTransactions(orderNumber: string): Promise<WebpayTransaction[]> {
  return (await gatewayState()).webpay.filter((tx) => tx.buyOrder === orderNumber)
}

export async function mercadoPagoPayments(orderNumber: string): Promise<MercadoPagoPayment[]> {
  return (await gatewayState()).mercadopago.payments.filter((payment) => payment.externalReference === orderNumber)
}

export async function stripeSessions(orderNumber: string): Promise<StripeSession[]> {
  return (await gatewayState()).stripe.filter((session) => session.clientReferenceId === orderNumber)
}

export async function setMercadoPagoPaymentStatus(id: number, status: MercadoPagoPayment['status']): Promise<void> {
  const result = await http('POST', `${DOUBLES_URL}/__doubles/gateways/mercadopago/payments/${id}/status`, { body: { status } })
  expect(result.status, result.text).toBe(200)
}

export async function expireStripeSession(id: string): Promise<void> {
  const result = await http('POST', `${DOUBLES_URL}/__doubles/gateways/stripe/sessions/${id}/expire`)
  expect(result.status, result.text).toBe(200)
}

// ---------------------------------------------------------------------------------------------- webhooks

/**
 * Un aviso de pago de MercadoPago firmado como lo verifica MercadoPagoWebhookSignature: `x-signature` es
 * `ts=<t>,v1=<HMAC-SHA256 en hex de "id:<id>;request-id:<x-request-id>;ts:<t>;">` con el secreto de la tienda.
 */
export function mercadoPagoWebhook(slug: string, secret: string, paymentId: number): Promise<HttpResult> {
  const ts = String(Date.now())
  const requestId = randomUUID()
  const manifest = `id:${paymentId};request-id:${requestId};ts:${ts};`
  const v1 = createHmac('sha256', secret).update(manifest).digest('hex')
  return http('POST', `${BACKEND_URL}/api/v1/mercadopago/webhook`, {
    headers: { Host: storeHost(slug), 'x-signature': `ts=${ts},v1=${v1}`, 'x-request-id': requestId },
    body: JSON.stringify({ type: 'payment', action: 'payment.updated', data: { id: String(paymentId) } }),
  })
}

/**
 * Un evento de Stripe firmado como lo verifica Webhook.constructEvent: `t=<unix>,v1=<HMAC-SHA256 de "t.cuerpo">`.
 * El `api_version` tiene que ser el del SDK del backend: con otro, el evento se acepta pero su objeto no se lee.
 */
export function stripeWebhook(slug: string, type: string, session: StripeSession): Promise<HttpResult> {
  const payload = JSON.stringify({
    id: `evt_e2e_${randomUUID()}`,
    object: 'event',
    api_version: '2025-03-31.basil',
    created: Math.floor(Date.now() / 1000),
    livemode: false,
    type,
    data: {
      object: {
        id: session.id,
        object: 'checkout.session',
        amount_total: session.amountTotal,
        currency: 'clp',
        payment_status: session.paymentStatus,
        status: session.status,
        payment_intent: session.paymentIntent,
        metadata: session.metadata,
        client_reference_id: session.clientReferenceId,
      },
    },
  })
  const timestamp = Math.floor(Date.now() / 1000)
  const signature = createHmac('sha256', E2E_STRIPE_WEBHOOK_SECRET).update(`${timestamp}.${payload}`).digest('hex')
  return http('POST', `${BACKEND_URL}/api/v1/stripe/webhook`, {
    headers: { Host: storeHost(slug), 'Stripe-Signature': `t=${timestamp},v1=${signature}` },
    body: payload,
  })
}
