// GAP-08. Dobles de las tres pasarelas de pago, servidos por el mismo proceso que los del POS y el SMTP.
//
// El backend llega aquí por STRIPE_API_BASE, WEBPAY_API_BASE y MERCADOPAGO_API_BASE (GatewayApiBaseConfig), que
// cambian solo el origen: las rutas y los cuerpos son los de cada API tal como las usa su SDK. Cada doble tiene
// además su «página de pago», la que el comprador ve en la pasarela, con un botón por desenlace: así el test
// decide si el pago se aprueba, se rechaza, se anula o se queda sin retorno, y lo demás lo hace el navegador.
//
// Cumplen el contrato tal como lo usa el backend, no el comportamiento completo de cada proveedor: lo que
// solo se ve contra el sandbox real queda para GAP-19.
//
// Igual que server.ts, solo sintaxis borrable: Node lo corre quitando los tipos.

import type { IncomingMessage, ServerResponse } from 'node:http'

export interface WebpayTransaction {
  token: string
  buyOrder: string
  sessionId: string
  amount: number
  commerceCode: string
  returnUrl: string
  /** INITIALIZED hasta que el comprador actúa en la página de pago. */
  status: 'INITIALIZED' | 'AUTHORIZED' | 'FAILED' | 'ABORTED'
  commits: number
  refunds: number[]
}

export interface MercadoPagoPreference {
  id: string
  externalReference: string
  amount: number
  backUrls: { success: string; failure: string; pending: string }
  notificationUrl: string
}

export interface MercadoPagoPayment {
  id: number
  preferenceId: string
  externalReference: string
  amount: number
  status: 'approved' | 'rejected' | 'in_process'
  refunds: number[]
}

export interface StripeSession {
  id: string
  amountTotal: number
  successUrl: string
  cancelUrl: string
  metadata: Record<string, string>
  clientReferenceId: string
  paymentStatus: 'paid' | 'unpaid'
  status: 'open' | 'complete' | 'expired'
  paymentIntent: string | null
  refunds: number[]
}

export interface GatewayState {
  webpay: WebpayTransaction[]
  mercadopago: { preferences: MercadoPagoPreference[]; payments: MercadoPagoPayment[] }
  stripe: StripeSession[]
}

const state: GatewayState = { webpay: [], mercadopago: { preferences: [], payments: [] }, stripe: [] }
let sequence = 0

function next(): number {
  sequence += 1
  return sequence
}

export function gatewayState(): GatewayState {
  return state
}

// ---------------------------------------------------------------------------------------------- utilidades

async function readBody(req: IncomingMessage): Promise<string> {
  const chunks: Buffer[] = []
  for await (const chunk of req) chunks.push(chunk as Buffer)
  return Buffer.concat(chunks).toString('utf8')
}

function sendJson(res: ServerResponse, status: number, payload: unknown): void {
  const body = JSON.stringify(payload)
  res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) })
  res.end(body)
}

function sendHtml(res: ServerResponse, html: string): void {
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' })
  res.end(`<!doctype html><html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>Pasarela de prueba</title></head>
<body style="font-family: sans-serif; padding: 16px">${html}</body></html>`)
}

function redirect(res: ServerResponse, location: string): void {
  res.writeHead(302, { Location: location, 'Cache-Control': 'no-store' })
  res.end()
}

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (char) => `&#${char.charCodeAt(0)};`)
}

/** Un formulario que el navegador envía solo: así vuelve Transbank al comercio, con un POST. */
function autoPost(res: ServerResponse, action: string, fields: Record<string, string>): void {
  const inputs = Object.entries(fields)
    .map(([name, value]) => `<input type="hidden" name="${escapeHtml(name)}" value="${escapeHtml(value)}">`)
    .join('')
  sendHtml(
    res,
    `<p>Volviendo al comercio…</p><form method="post" action="${escapeHtml(action)}">${inputs}</form>
<script>document.forms[0].submit()</script>`,
  )
}

function buttonForm(action: string, fields: Record<string, string>, label: string, method = 'post'): string {
  const inputs = Object.entries(fields)
    .map(([name, value]) => `<input type="hidden" name="${escapeHtml(name)}" value="${escapeHtml(value)}">`)
    .join('')
  return `<form method="${method}" action="${escapeHtml(action)}" style="margin: 8px 0">${inputs}
<button type="submit" style="font-size: 16px; padding: 8px 16px">${escapeHtml(label)}</button></form>`
}

function formatClp(amount: number): string {
  return `$${Math.round(amount).toLocaleString('es-CL')}`
}

// ---------------------------------------------------------------------------------------------- Webpay

const WEBPAY_API = '/webpay/rswebpaytransaction/api/webpay/v1.2/transactions'

function webpayDetail(tx: WebpayTransaction) {
  const authorized = tx.status === 'AUTHORIZED'
  return {
    amount: tx.amount,
    status: tx.status,
    authorization_code: authorized ? '1213' : null,
    payment_type_code: 'VN',
    response_code: authorized ? 0 : -1,
    installments_number: 0,
    commerce_code: tx.commerceCode,
    buy_order: tx.buyOrder,
  }
}

function webpayTransactionResponse(tx: WebpayTransaction) {
  return {
    vci: tx.status === 'AUTHORIZED' ? 'TSY' : null,
    buy_order: tx.buyOrder,
    session_id: tx.sessionId,
    card_detail: { card_number: '6623' },
    accounting_date: '1106',
    transaction_date: new Date().toISOString(),
    details: [webpayDetail(tx)],
  }
}

function webpayError(res: ServerResponse, status: number, message: string): void {
  sendJson(res, status, { error_message: message })
}

async function handleWebpayApi(req: IncomingMessage, res: ServerResponse, path: string, origin: string): Promise<void> {
  if (req.method === 'POST' && path === WEBPAY_API) {
    const body = JSON.parse(await readBody(req)) as {
      buy_order: string
      session_id: string
      return_url: string
      details: { amount: number; commerce_code: string; buy_order: string }[]
    }
    const tx: WebpayTransaction = {
      token: `e2e${next().toString().padStart(6, '0')}${'0'.repeat(55)}`,
      buyOrder: body.buy_order,
      sessionId: body.session_id,
      amount: body.details[0].amount,
      commerceCode: body.details[0].commerce_code,
      returnUrl: body.return_url,
      status: 'INITIALIZED',
      commits: 0,
      refunds: [],
    }
    state.webpay.push(tx)
    sendJson(res, 200, { token: tx.token, url: `${origin}/__gateway/webpay/pay` })
    return
  }

  const match = /^\/webpay\/rswebpaytransaction\/api\/webpay\/v1\.2\/transactions\/([^/]+)(\/refunds)?$/.exec(path)
  const tx = match ? state.webpay.find((candidate) => candidate.token === match[1]) : undefined
  if (!match || !tx) {
    webpayError(res, 404, 'Transacción no encontrada')
    return
  }

  if (req.method === 'PUT' && !match[2]) {
    // Como Transbank: un rechazo se confirma con su resultado; una transacción anulada, vencida o ya confirmada
    // no se puede confirmar.
    if (tx.status === 'INITIALIZED' || tx.status === 'ABORTED') {
      webpayError(res, 422, `Invalid status '${tx.status}' for transaction while authorizing`)
      return
    }
    if (tx.commits > 0) {
      webpayError(res, 422, 'Transaction already locked by another process')
      return
    }
    tx.commits += 1
    sendJson(res, 200, webpayTransactionResponse(tx))
    return
  }
  if (req.method === 'GET' && !match[2]) {
    sendJson(res, 200, webpayTransactionResponse(tx))
    return
  }
  if (req.method === 'POST' && match[2]) {
    const body = JSON.parse(await readBody(req)) as { amount: number }
    const refunded = tx.refunds.reduce((sum, amount) => sum + amount, 0)
    if (tx.status !== 'AUTHORIZED' || body.amount > tx.amount - refunded) {
      webpayError(res, 422, 'Invalid refund amount')
      return
    }
    tx.refunds.push(body.amount)
    sendJson(res, 200, {
      type: body.amount === tx.amount ? 'REVERSED' : 'NULLIFIED',
      authorization_code: '123456',
      authorization_date: new Date().toISOString(),
      nullified_amount: body.amount,
      balance: tx.amount - refunded - body.amount,
      response_code: 0,
    })
    return
  }
  webpayError(res, 405, `El doble de Webpay no conoce ${req.method} ${path}`)
}

async function handleWebpayPage(req: IncomingMessage, res: ServerResponse, path: string): Promise<void> {
  const form = new URLSearchParams(await readBody(req))
  const token = form.get('token_ws') ?? ''
  const tx = state.webpay.find((candidate) => candidate.token === token)
  if (!tx) {
    sendHtml(res, '<h1>Transacción desconocida</h1>')
    return
  }

  if (path === '/__gateway/webpay/pay') {
    const action = '/__gateway/webpay/act'
    sendHtml(
      res,
      `<h1>Webpay de prueba</h1><p>Orden ${escapeHtml(tx.buyOrder)} por ${formatClp(tx.amount)}</p>
${buttonForm(action, { token_ws: token, outcome: 'pay' }, 'Pagar')}
${buttonForm(action, { token_ws: token, outcome: 'reject' }, 'Rechazar')}
${buttonForm(action, { token_ws: token, outcome: 'abort' }, 'Anular')}
${buttonForm(action, { token_ws: token, outcome: 'expire' }, 'Dejar expirar')}
${buttonForm(action, { token_ws: token, outcome: 'pay-no-return' }, 'Pagar sin volver')}`,
    )
    return
  }

  // Lo que manda Transbank de vuelta en cada caso: token_ws al terminar (pagado o rechazado), TBK_TOKEN al anular,
  // y solo la orden y la sesión cuando vence el tiempo en el formulario.
  switch (form.get('outcome')) {
    case 'pay':
      tx.status = 'AUTHORIZED'
      autoPost(res, tx.returnUrl, { token_ws: token })
      return
    case 'reject':
      tx.status = 'FAILED'
      autoPost(res, tx.returnUrl, { token_ws: token })
      return
    case 'abort':
      tx.status = 'ABORTED'
      autoPost(res, tx.returnUrl, { TBK_TOKEN: token, TBK_ORDEN_COMPRA: tx.buyOrder, TBK_ID_SESION: tx.sessionId })
      return
    case 'expire':
      tx.status = 'ABORTED'
      autoPost(res, tx.returnUrl, { TBK_ORDEN_COMPRA: tx.buyOrder, TBK_ID_SESION: tx.sessionId })
      return
    case 'pay-no-return':
      // El cobro se autorizó y el navegador nunca vuelve: solo la conciliación puede enterarse.
      tx.status = 'AUTHORIZED'
      sendHtml(res, '<h1>Pago autorizado</h1><p>Puedes cerrar esta ventana.</p>')
      return
    default:
      sendHtml(res, '<h1>Acción desconocida</h1>')
  }
}

// ---------------------------------------------------------------------------------------------- MercadoPago

function mercadoPagoPaymentResponse(payment: MercadoPagoPayment) {
  return {
    id: payment.id,
    status: payment.status,
    status_detail: payment.status === 'approved' ? 'accredited' : payment.status === 'rejected' ? 'cc_rejected_other_reason' : 'pending_contingency',
    external_reference: payment.externalReference,
    transaction_amount: payment.amount,
    currency_id: 'CLP',
    date_created: new Date().toISOString(),
  }
}

async function handleMercadoPagoApi(req: IncomingMessage, res: ServerResponse, url: URL, origin: string): Promise<void> {
  const path = url.pathname
  if (req.method === 'POST' && path === '/mercadopago/checkout/preferences') {
    const body = JSON.parse(await readBody(req)) as {
      items: { unit_price: number; quantity: number }[]
      external_reference: string
      notification_url: string
      back_urls: { success: string; failure: string; pending: string }
    }
    const preference: MercadoPagoPreference = {
      id: `pref-e2e-${next()}`,
      externalReference: body.external_reference,
      amount: body.items.reduce((sum, item) => sum + item.unit_price * item.quantity, 0),
      backUrls: body.back_urls,
      notificationUrl: body.notification_url,
    }
    state.mercadopago.preferences.push(preference)
    const initPoint = `${origin}/__gateway/mercadopago/pay?pref=${preference.id}`
    sendJson(res, 201, { id: preference.id, init_point: initPoint, sandbox_init_point: initPoint })
    return
  }
  if (req.method === 'GET' && path === '/mercadopago/v1/payments/search') {
    const reference = url.searchParams.get('external_reference')
    const results = state.mercadopago.payments.filter((payment) => payment.externalReference === reference)
    sendJson(res, 200, {
      paging: { total: results.length, limit: 10, offset: 0 },
      results: results.map(mercadoPagoPaymentResponse),
    })
    return
  }
  const match = /^\/mercadopago\/v1\/payments\/(\d+)(\/refunds)?$/.exec(path)
  const payment = match ? state.mercadopago.payments.find((candidate) => candidate.id === Number(match[1])) : undefined
  if (!match || !payment) {
    sendJson(res, 404, { message: 'Payment not found', error: 'not_found', status: 404 })
    return
  }
  if (req.method === 'GET' && !match[2]) {
    sendJson(res, 200, mercadoPagoPaymentResponse(payment))
    return
  }
  if (req.method === 'POST' && match[2]) {
    const raw = await readBody(req)
    const amount = raw ? (JSON.parse(raw) as { amount?: number }).amount : undefined
    const refunded = payment.refunds.reduce((sum, value) => sum + value, 0)
    const value = amount ?? payment.amount - refunded
    if (payment.status !== 'approved' || value > payment.amount - refunded) {
      sendJson(res, 400, { message: 'Invalid refund amount', error: 'bad_request', status: 400 })
      return
    }
    payment.refunds.push(value)
    sendJson(res, 201, { id: next(), payment_id: payment.id, amount: value, status: 'approved', date_created: new Date().toISOString() })
    return
  }
  sendJson(res, 405, { message: `El doble de MercadoPago no conoce ${req.method} ${path}` })
}

function handleMercadoPagoPage(res: ServerResponse, url: URL): void {
  const preference = state.mercadopago.preferences.find((candidate) => candidate.id === url.searchParams.get('pref'))
  if (!preference) {
    sendHtml(res, '<h1>Preferencia desconocida</h1>')
    return
  }
  if (url.pathname === '/__gateway/mercadopago/pay') {
    const action = '/__gateway/mercadopago/act'
    sendHtml(
      res,
      `<h1>MercadoPago de prueba</h1><p>Orden ${escapeHtml(preference.externalReference)} por ${formatClp(preference.amount)}</p>
${buttonForm(action, { pref: preference.id, outcome: 'approved' }, 'Aprobar', 'get')}
${buttonForm(action, { pref: preference.id, outcome: 'rejected' }, 'Rechazar', 'get')}
${buttonForm(action, { pref: preference.id, outcome: 'in_process' }, 'Dejar pendiente', 'get')}`,
    )
    return
  }
  const outcome = url.searchParams.get('outcome') as MercadoPagoPayment['status']
  const payment: MercadoPagoPayment = {
    id: 9_000_000 + next(),
    preferenceId: preference.id,
    externalReference: preference.externalReference,
    amount: preference.amount,
    status: outcome,
    refunds: [],
  }
  state.mercadopago.payments.push(payment)
  // El webhook no sale de aquí: lo manda el test cuando el caso lo pide (tarde, repetido o nunca).
  const back = outcome === 'approved' ? preference.backUrls.success : outcome === 'rejected' ? preference.backUrls.failure : preference.backUrls.pending
  redirect(res, back)
}

// ---------------------------------------------------------------------------------------------- Stripe

function stripeSessionResponse(session: StripeSession, origin: string) {
  return {
    id: session.id,
    object: 'checkout.session',
    url: session.status === 'open' ? `${origin}/__gateway/stripe/pay?session=${session.id}` : null,
    amount_total: session.amountTotal,
    currency: 'clp',
    payment_status: session.paymentStatus,
    status: session.status,
    payment_intent: session.paymentIntent,
    metadata: session.metadata,
    client_reference_id: session.clientReferenceId,
    success_url: session.successUrl,
    cancel_url: session.cancelUrl,
    livemode: false,
  }
}

function stripeError(res: ServerResponse, status: number, message: string): void {
  sendJson(res, status, { error: { type: 'invalid_request_error', message } })
}

async function handleStripeApi(req: IncomingMessage, res: ServerResponse, path: string, origin: string): Promise<void> {
  if (req.method === 'POST' && path === '/stripe/v1/checkout/sessions') {
    const form = new URLSearchParams(await readBody(req))
    const metadata: Record<string, string> = {}
    for (const [key, value] of form) {
      const meta = /^metadata\[(.+)\]$/.exec(key)
      if (meta) metadata[meta[1]] = value
    }
    const session: StripeSession = {
      id: `cs_test_e2e${next()}`,
      amountTotal: Number(form.get('line_items[0][price_data][unit_amount]') ?? 0) * Number(form.get('line_items[0][quantity]') ?? 1),
      successUrl: form.get('success_url') ?? '',
      cancelUrl: form.get('cancel_url') ?? '',
      metadata,
      clientReferenceId: form.get('client_reference_id') ?? '',
      paymentStatus: 'unpaid',
      status: 'open',
      paymentIntent: null,
      refunds: [],
    }
    state.stripe.push(session)
    sendJson(res, 200, stripeSessionResponse(session, origin))
    return
  }
  const match = /^\/stripe\/v1\/checkout\/sessions\/([^/]+)$/.exec(path)
  if (req.method === 'GET' && match) {
    const session = state.stripe.find((candidate) => candidate.id === decodeURIComponent(match[1]))
    if (!session) {
      stripeError(res, 404, 'No such checkout.session')
      return
    }
    sendJson(res, 200, stripeSessionResponse(session, origin))
    return
  }
  if (req.method === 'POST' && path === '/stripe/v1/refunds') {
    const form = new URLSearchParams(await readBody(req))
    const session = state.stripe.find((candidate) => candidate.paymentIntent === form.get('payment_intent'))
    const amount = Number(form.get('amount'))
    const refunded = session ? session.refunds.reduce((sum, value) => sum + value, 0) : 0
    if (!session || amount > session.amountTotal - refunded) {
      stripeError(res, 400, 'Invalid refund')
      return
    }
    session.refunds.push(amount)
    sendJson(res, 200, { id: `re_e2e${next()}`, object: 'refund', amount, status: 'succeeded', payment_intent: session.paymentIntent })
    return
  }
  stripeError(res, 404, `El doble de Stripe no conoce ${req.method} ${path}`)
}

function handleStripePage(res: ServerResponse, url: URL): void {
  const session = state.stripe.find((candidate) => candidate.id === url.searchParams.get('session'))
  if (!session) {
    sendHtml(res, '<h1>Sesión desconocida</h1>')
    return
  }
  if (url.pathname === '/__gateway/stripe/pay') {
    const action = '/__gateway/stripe/act'
    sendHtml(
      res,
      `<h1>Stripe de prueba</h1><p>Pedido ${escapeHtml(session.clientReferenceId)} por ${formatClp(session.amountTotal)}</p>
${buttonForm(action, { session: session.id, outcome: 'pay' }, 'Pagar', 'get')}
${buttonForm(action, { session: session.id, outcome: 'cancel' }, 'Cancelar', 'get')}
${buttonForm(action, { session: session.id, outcome: 'unpaid' }, 'Volver sin pagar', 'get')}`,
    )
    return
  }
  const successUrl = session.successUrl.replace('{CHECKOUT_SESSION_ID}', session.id)
  switch (url.searchParams.get('outcome')) {
    case 'pay':
      session.paymentStatus = 'paid'
      session.status = 'complete'
      session.paymentIntent = `pi_e2e${next()}`
      redirect(res, successUrl)
      return
    case 'cancel':
      redirect(res, session.cancelUrl)
      return
    case 'unpaid':
      // Una sesión que vuelve sin cobro confirmado, como un medio de pago diferido.
      redirect(res, successUrl)
      return
    default:
      sendHtml(res, '<h1>Acción desconocida</h1>')
  }
}

// ---------------------------------------------------------------------------------------------- control

async function handleControl(req: IncomingMessage, res: ServerResponse, path: string): Promise<void> {
  if (req.method === 'GET' && path === '/__doubles/gateways') {
    sendJson(res, 200, state)
    return
  }
  // MercadoPago: un pago pendiente que se acredita después, o un estado forzado por el test.
  const payment = /^\/__doubles\/gateways\/mercadopago\/payments\/(\d+)\/status$/.exec(path)
  if (req.method === 'POST' && payment) {
    const target = state.mercadopago.payments.find((candidate) => candidate.id === Number(payment[1]))
    if (!target) {
      sendJson(res, 404, { message: 'Pago desconocido' })
      return
    }
    target.status = (JSON.parse(await readBody(req)) as { status: MercadoPagoPayment['status'] }).status
    sendJson(res, 200, target)
    return
  }
  // Stripe: la sesión vence en Stripe (el test manda después checkout.session.expired).
  const expire = /^\/__doubles\/gateways\/stripe\/sessions\/([^/]+)\/expire$/.exec(path)
  if (req.method === 'POST' && expire) {
    const target = state.stripe.find((candidate) => candidate.id === expire[1])
    if (!target) {
      sendJson(res, 404, { message: 'Sesión desconocida' })
      return
    }
    target.status = 'expired'
    sendJson(res, 200, stripeSessionResponse(target, ''))
    return
  }
  sendJson(res, 404, { message: `El control de pasarelas no conoce ${req.method} ${path}` })
}

/** Atiende la petición si es de una pasarela. Devuelve false si no le corresponde. */
export async function handleGateway(req: IncomingMessage, res: ServerResponse, url: URL, origin: string): Promise<boolean> {
  const path = url.pathname
  if (path.startsWith('/webpay/')) await handleWebpayApi(req, res, path, origin)
  else if (path.startsWith('/__gateway/webpay/')) await handleWebpayPage(req, res, path)
  else if (path.startsWith('/mercadopago/')) await handleMercadoPagoApi(req, res, url, origin)
  else if (path.startsWith('/__gateway/mercadopago/')) handleMercadoPagoPage(res, url)
  else if (path.startsWith('/stripe/')) await handleStripeApi(req, res, path, origin)
  else if (path.startsWith('/__gateway/stripe/')) handleStripePage(res, url)
  else if (path.startsWith('/__doubles/gateways')) await handleControl(req, res, path)
  else return false
  return true
}
