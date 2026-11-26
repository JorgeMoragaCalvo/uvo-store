// PROD-14a. Dobles del POS y del correo para la red de navegador, en un solo proceso y sin dependencias.
//
// Corre directo con Node (`node e2e/doubles/server.ts`, que quita los tipos), así que solo usa sintaxis
// borrable. Los tests corren en otro proceso, por eso lo que reciben los dobles se lee por HTTP
// (/__doubles/...) y no de memoria compartida.
//
// - POS (IMP-08): cumple el contrato UvoPOS ↔ uvo-store v1 (docs/contrato-pos-ecommerce.md). Verifica la firma
//   UVO-HMAC con la llave tienda→POS que el test registra (/__doubles/pos/keys), deduplica las ventas por empresa y
//   clave con el hash del cuerpo (§8), responde la consulta (§9) y la lectura del catálogo (§5), y deja inyectar
//   fallos (/__doubles/pos/faults) para probar cómo los clasifica la tienda (§10).
// - SMTP: lo justo para que JavaMail entregue un SimpleMailMessage, sin STARTTLS ni AUTH. Guarda destinatario,
//   asunto y cuerpo ya decodificados, para que los tests lean el enlace de la invitación tal cual.
// - Pasarelas (GAP-08): Stripe, Webpay y MercadoPago, con su API y su página de pago. Ver gateways.ts.

import { createHash, createHmac, timingSafeEqual } from 'node:crypto'
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http'
import { createServer as createTcpServer, type Socket } from 'node:net'
import { DOUBLES_HTTP_PORT, DOUBLES_SMTP_PORT, DOUBLES_URL } from '../support/env.ts'
import { handleGateway } from './gateways.ts'

export interface PosOrderCall {
  receivedAt: string
  path: string
  /** IMP-08: el key_id de la firma y si la firma verificó con la llave registrada. */
  keyId: string | null
  signatureValid: boolean
  contract: string | null
  companyId: string | null
  idempotencyKey: string | null
  requestId: string | null
  /** Lo que respondió el doble: 201 nueva, 200 repetida, 409, 422, 500… */
  status: number
  body: unknown
}

/** IMP-08. Un fallo a inyectar en la próxima venta: lo que el test quiere ver clasificado (§10). */
export type PosFault =
  | 'error500'
  | 'inProgress'
  | 'outOfStock'
  /** Registra la venta y "pierde" la respuesta: responde 500. La tienda tiene que consultar (§9). */
  | 'registerThenFail'


export interface CapturedMail {
  receivedAt: string
  to: string[]
  subject: string
  body: string
}

/** IMP-04. Un envelope de Sentry: la cabecera y cada ítem (`event`, `session`...) con su cuerpo ya parseado. */
export interface SentryEnvelope {
  receivedAt: string
  items: { type: string; payload: unknown }[]
}

const posOrders: PosOrderCall[] = []

// IMP-08. Las llaves tienda→POS registradas por los tests (key_id → secreto base64 y empresa), las ventas
// registradas por empresa y clave (§8) y los fallos pendientes de inyectar.
const posKeys = new Map<string, { secret: string; companyId: string }>()
const posSales = new Map<string, { saleId: number; bodySha256: string; registeredAt: string }>()
const posFaults: PosFault[] = []
let nextSaleId = 88000
const mails: CapturedMail[] = []
const sentryEnvelopes: SentryEnvelope[] = []

/** Envelope: línea de cabecera y luego pares cabecera de ítem / cuerpo. Lo que no es JSON se guarda tal cual. */
function parseEnvelope(raw: string): SentryEnvelope['items'] {
  const lines = raw.split('\n').filter((line) => line.trim() !== '')
  const items: SentryEnvelope['items'] = []
  for (let i = 1; i + 1 < lines.length; i += 2) {
    let type = 'desconocido'
    let payload: unknown = lines[i + 1]
    try {
      type = (JSON.parse(lines[i]) as { type?: string }).type ?? type
      payload = JSON.parse(lines[i + 1])
    } catch {
      // Un ítem binario (adjunto) no se puede leer como texto; no hace falta para las pruebas.
    }
    items.push({ type, payload })
  }
  return items
}

// ---------------------------------------------------------------------------------------------- HTTP

function header(req: IncomingMessage, name: string): string | null {
  const value = req.headers[name.toLowerCase()]
  return Array.isArray(value) ? value.join(',') : (value ?? null)
}

function sendJson(res: ServerResponse, status: number, payload: unknown): void {
  const body = JSON.stringify(payload)
  res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) })
  res.end(body)
}

async function readBody(req: IncomingMessage): Promise<string> {
  return (await readBytes(req)).toString('utf8')
}

async function readBytes(req: IncomingMessage): Promise<Buffer> {
  const chunks: Buffer[] = []
  for await (const chunk of req) chunks.push(chunk as Buffer)
  return Buffer.concat(chunks)
}

// ---------------------------------------------------------------------------------------------- POS (IMP-08)

function sha256Hex(bytes: Buffer): string {
  return createHash('sha256').update(bytes).digest('hex')
}

/** §3: la canónica de siete líneas sobre la ruta cruda y los bytes del cuerpo, con la llave registrada. */
function verifyPosSignature(req: IncomingMessage, rawPath: string, body: Buffer): boolean {
  const keyId = header(req, 'X-Uvo-Key-Id')
  const key = keyId ? posKeys.get(keyId) : undefined
  const signature = header(req, 'X-Uvo-Signature') ?? ''
  if (!key || !/^v1=[0-9a-f]{64}$/.test(signature) || header(req, 'X-Company-ID') !== key.companyId) return false
  const canonical = [
    'UVO-HMAC-SHA256',
    req.method ?? '',
    rawPath,
    header(req, 'X-Uvo-Timestamp') ?? '',
    header(req, 'X-Uvo-Nonce') ?? '',
    keyId ?? '',
    sha256Hex(body),
  ].join('\n')
  const expected = 'v1=' + createHmac('sha256', Buffer.from(key.secret, 'base64')).update(canonical).digest('hex')
  return timingSafeEqual(Buffer.from(expected), Buffer.from(signature))
}

function posError(res: ServerResponse, status: number, code: string, message: string, extra: object = {}): void {
  sendJson(res, status, { success: false, error: { code, message, details: extra } })
}

/** §7 y §8: registra una venta exactamente una vez por empresa y clave. */
async function handlePosSale(req: IncomingMessage, res: ServerResponse, rawPath: string): Promise<void> {
  const raw = await readBytes(req)
  const companyId = header(req, 'X-Company-ID')
  const idempotencyKey = header(req, 'Idempotency-Key')
  const signatureValid = verifyPosSignature(req, rawPath, raw)
  const call: PosOrderCall = {
    receivedAt: new Date().toISOString(),
    path: rawPath,
    keyId: header(req, 'X-Uvo-Key-Id'),
    signatureValid,
    contract: header(req, 'X-Uvo-Contract'),
    companyId,
    idempotencyKey,
    requestId: header(req, 'X-Request-Id'),
    status: 0,
    body: raw.length ? JSON.parse(raw.toString('utf8')) : null,
  }
  posOrders.push(call)
  const answer = (status: number, write: () => void) => {
    call.status = status
    write()
  }
  if (!signatureValid) return answer(401, () => posError(res, 401, 'UNAUTHORIZED', 'Firma inválida'))
  if (call.contract !== '1') return answer(400, () => posError(res, 400, 'UNSUPPORTED_CONTRACT', 'Contrato'))

  const salesKey = `${companyId}|${idempotencyKey}`
  const hash = sha256Hex(raw)
  const fault = posFaults.shift()
  if (fault === 'inProgress') {
    res.setHeader('Retry-After', '1')
    return answer(409, () => posError(res, 409, 'IN_PROGRESS', 'La venta con esta clave todavía se está procesando'))
  }
  if (fault === 'outOfStock') {
    return answer(422, () => posError(res, 422, 'OUT_OF_STOCK', 'No hay stock web suficiente para registrar la venta'))
  }
  if (fault === 'error500') return answer(500, () => posError(res, 500, 'INTERNAL', 'Fallo del doble'))

  const existing = posSales.get(salesKey)
  if (existing && existing.bodySha256 !== hash) {
    return answer(409, () => posError(res, 409, 'IDEMPOTENCY_CONFLICT', 'La clave ya se usó con un cuerpo distinto',
      { sale_id: existing.saleId }))
  }
  const sale = existing ?? { saleId: nextSaleId++, bodySha256: hash, registeredAt: new Date().toISOString() }
  posSales.set(salesKey, sale)
  if (fault === 'registerThenFail') return answer(500, () => posError(res, 500, 'INTERNAL', 'Respuesta perdida'))
  const status = existing ? 200 : 201
  answer(status, () => sendJson(res, status, {
    success: true,
    data: {
      sale_id: sale.saleId,
      idempotency_key: idempotencyKey,
      duplicate: Boolean(existing),
      document: { tipo: 999, folio: sale.saleId, estado_sii: 'NO_APLICA' },
      // Sin resultados de stock: la comprobación de divergencia de la respuesta no tiene nada que comparar.
      results: [],
    },
  }))
}

/** §9: la consulta de una venta. Un 404 con NOT_PROCESSED es una promesa: no la tiene. */
async function handlePosLookup(req: IncomingMessage, res: ServerResponse, rawPath: string): Promise<void> {
  if (!verifyPosSignature(req, rawPath, Buffer.alloc(0))) return posError(res, 401, 'UNAUTHORIZED', 'Firma inválida')
  const key = decodeURIComponent(rawPath.slice('/api/v1/orders/external/'.length))
  const sale = posSales.get(`${header(req, 'X-Company-ID')}|${key}`)
  if (!sale) return posError(res, 404, 'NOT_PROCESSED', 'El POS no registró ninguna venta con esta clave')
  sendJson(res, 200, {
    success: true,
    data: {
      status: 'REGISTERED',
      sale_id: sale.saleId,
      idempotency_key: key,
      registered_at: sale.registeredAt,
      body_sha256: sale.bodySha256,
    },
  })
}

const http = createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`)
  try {
    if (await handleGateway(req, res, url, DOUBLES_URL)) return
    // La ruta cruda, sin decodificar: es la que se firma (§3), con la clave de la consulta como %3A.
    const rawPath = (req.url ?? '/').split('?')[0]
    if (req.method === 'POST' && rawPath === '/api/v1/orders/external') {
      await handlePosSale(req, res, rawPath)
      return
    }
    if (req.method === 'GET' && rawPath.startsWith('/api/v1/orders/external/')) {
      await handlePosLookup(req, res, rawPath)
      return
    }
    if (req.method === 'POST' && rawPath === '/api/v1/products/snapshot') {
      const raw = await readBytes(req)
      if (!verifyPosSignature(req, rawPath, raw)) return posError(res, 401, 'UNAUTHORIZED', 'Firma inválida')
      // El doble no lleva catálogo: todo lo pedido va en missing, como exige el §5 (data o missing).
      const ids = (JSON.parse(raw.toString('utf8')) as { product_ids?: number[] }).product_ids ?? []
      sendJson(res, 200, { success: true, data: [], missing: ids })
      return
    }
    if (req.method === 'POST' && url.pathname === '/__doubles/pos/keys') {
      const key = JSON.parse(await readBody(req)) as { keyId: string; secret: string; companyId: number }
      posKeys.set(key.keyId, { secret: key.secret, companyId: String(key.companyId) })
      res.writeHead(204)
      res.end()
      return
    }
    if (req.method === 'POST' && url.pathname === '/__doubles/pos/faults') {
      const { faults } = JSON.parse(await readBody(req)) as { faults: PosFault[] }
      posFaults.push(...faults)
      sendJson(res, 200, { pending: posFaults.length })
      return
    }
    // IMP-04. Sentry del build `dist` (SENTRY_DOUBLE_DSN): el SDK manda cada evento como un envelope, una línea
    // JSON por parte. Va desde el navegador y desde otro origen, así que necesita CORS como el Sentry real.
    if (url.pathname === '/api/1/envelope/') {
      res.setHeader('Access-Control-Allow-Origin', '*')
      res.setHeader('Access-Control-Allow-Headers', '*')
      if (req.method === 'OPTIONS') {
        res.writeHead(204)
        res.end()
        return
      }
      if (req.method === 'POST') {
        const raw = await readBody(req)
        sentryEnvelopes.push({ receivedAt: new Date().toISOString(), items: parseEnvelope(raw) })
        sendJson(res, 200, { id: 'e2e' })
        return
      }
    }
    // IMP-04. Una página de otro origen y sin CSP propia que enmarca `src` (la prueba de frame-ancestors de
    // artefacto.spec.ts) y, como control, una página que sí se deja enmarcar.
    if (req.method === 'GET' && url.pathname === '/__doubles/enmarcar') {
      const src = (url.searchParams.get('src') ?? '').replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`)
      const body = `<!doctype html><iframe name="panel" src="${src}"></iframe><iframe name="control" src="/__doubles/health"></iframe>`
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Content-Length': Buffer.byteLength(body) })
      res.end(body)
      return
    }
    if (req.method === 'GET' && url.pathname === '/__doubles/sentry') {
      sendJson(res, 200, sentryEnvelopes)
      return
    }
    if (req.method === 'GET' && url.pathname === '/__doubles/health') {
      sendJson(res, 200, { status: 'UP' })
      return
    }
    if (req.method === 'GET' && url.pathname === '/__doubles/pos/orders') {
      sendJson(res, 200, posOrders)
      return
    }
    if (req.method === 'GET' && url.pathname === '/__doubles/mail') {
      const to = url.searchParams.get('to')?.toLowerCase()
      sendJson(res, 200, to ? mails.filter((mail) => mail.to.includes(to)) : mails)
      return
    }
    sendJson(res, 404, { message: `El doble no conoce ${req.method} ${url.pathname}` })
  } catch (err) {
    sendJson(res, 500, { message: String(err) })
  }
})

// ---------------------------------------------------------------------------------------------- SMTP

/** Quoted-printable (RFC 2045) a bytes: quita los saltos blandos y traduce los =XX. */
function decodeQuotedPrintable(text: string): Buffer {
  const unfolded = text.replace(/=\r?\n/g, '')
  const bytes: number[] = []
  for (let i = 0; i < unfolded.length; i++) {
    const hex = unfolded.slice(i + 1, i + 3)
    if (unfolded[i] === '=' && /^[0-9A-Fa-f]{2}$/.test(hex)) {
      bytes.push(parseInt(hex, 16))
      i += 2
    } else {
      bytes.push(unfolded.charCodeAt(i) & 0xff)
    }
  }
  return Buffer.from(bytes)
}

/** Palabras codificadas de una cabecera (RFC 2047), como el asunto «Confirmación de tu pedido …». */
function decodeEncodedWords(value: string): string {
  return value
    .replace(/\?=\s+=\?/g, '?==?')
    .replace(/=\?([^?]+)\?([BbQq])\?([^?]*)\?=/g, (_match, _charset: string, encoding: string, text: string) =>
      encoding.toUpperCase() === 'B'
        ? Buffer.from(text, 'base64').toString('utf8')
        : decodeQuotedPrintable(text.replace(/_/g, ' ')).toString('utf8'),
    )
}

/** El mensaje llega como bytes en latin1 (uno a uno); el cuerpo se decodifica según su cabecera. */
function parseMessage(raw: string): { subject: string; body: string } {
  const split = raw.indexOf('\r\n\r\n')
  const headerBlock = split >= 0 ? raw.slice(0, split) : raw
  const bodyBlock = split >= 0 ? raw.slice(split + 4) : ''
  const headers = new Map<string, string>()
  for (const line of headerBlock.replace(/\r\n[ \t]+/g, ' ').split('\r\n')) {
    const colon = line.indexOf(':')
    if (colon > 0) headers.set(line.slice(0, colon).trim().toLowerCase(), line.slice(colon + 1).trim())
  }
  const encoding = (headers.get('content-transfer-encoding') ?? '7bit').toLowerCase()
  const bytes =
    encoding === 'quoted-printable'
      ? decodeQuotedPrintable(bodyBlock)
      : encoding === 'base64'
        ? Buffer.from(bodyBlock.replace(/\s+/g, ''), 'base64')
        : Buffer.from(bodyBlock, 'latin1')
  return { subject: decodeEncodedWords(headers.get('subject') ?? ''), body: bytes.toString('utf8') }
}

function handleSmtp(socket: Socket): void {
  socket.setEncoding('latin1')
  let buffer = ''
  let inData = false
  let recipients: string[] = []
  const reply = (line: string) => socket.write(`${line}\r\n`)

  reply('220 e2e-smtp listo')
  socket.on('data', (chunk: string) => {
    buffer += chunk
    for (;;) {
      if (inData) {
        const end = buffer.indexOf('\r\n.\r\n')
        if (end < 0) return
        // Quita el relleno de puntos (RFC 5321 §4.5.2) antes de leer el mensaje.
        const raw = buffer.slice(0, end).replace(/\r\n\.\./g, '\r\n.').replace(/^\.\./, '.')
        buffer = buffer.slice(end + 5)
        inData = false
        const { subject, body } = parseMessage(raw)
        mails.push({ receivedAt: new Date().toISOString(), to: recipients, subject, body })
        recipients = []
        reply('250 OK')
        continue
      }
      const newline = buffer.indexOf('\r\n')
      if (newline < 0) return
      const line = buffer.slice(0, newline)
      buffer = buffer.slice(newline + 2)
      const command = line.slice(0, 4).toUpperCase()
      if (command === 'EHLO') {
        reply('250-e2e-smtp')
        reply('250 8BITMIME')
      } else if (command === 'HELO' || command === 'MAIL' || command === 'NOOP') {
        reply('250 OK')
      } else if (command === 'RSET') {
        recipients = []
        reply('250 OK')
      } else if (command === 'RCPT') {
        const address = /<([^>]*)>/.exec(line)?.[1] ?? line.slice(line.indexOf(':') + 1).trim()
        recipients.push(address.toLowerCase())
        reply('250 OK')
      } else if (command === 'DATA') {
        inData = true
        reply('354 Termina con <CRLF>.<CRLF>')
      } else if (command === 'QUIT') {
        reply('221 Adios')
        socket.end()
        return
      } else {
        reply('502 No implementado')
      }
    }
  })
  socket.on('error', () => socket.destroy())
}

const smtp = createTcpServer(handleSmtp)

http.listen(DOUBLES_HTTP_PORT, '127.0.0.1', () => {
  console.log(`[dobles] POS e inspección en http://127.0.0.1:${DOUBLES_HTTP_PORT}`)
})
smtp.listen(DOUBLES_SMTP_PORT, '127.0.0.1', () => {
  console.log(`[dobles] SMTP en 127.0.0.1:${DOUBLES_SMTP_PORT}`)
})

function shutdown(): void {
  http.close()
  smtp.close()
  process.exit(0)
}
process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)
