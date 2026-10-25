// PROD-14a. Dobles del POS y del correo para la red de navegador, en un solo proceso y sin dependencias.
//
// Corre directo con Node (`node e2e/doubles/server.ts`, que quita los tipos), así que solo usa sintaxis
// borrable. Los tests corren en otro proceso, por eso lo que reciben los dobles se lee por HTTP
// (/__doubles/...) y no de memoria compartida.
//
// - POS: cumple el contrato tal como lo implementa PosClient (POS-1 sigue abierto): recibe las ventas en
//   /api/v1/orders/external y la lectura del catálogo en /api/v1/products/snapshot.
// - SMTP: lo justo para que JavaMail entregue un SimpleMailMessage, sin STARTTLS ni AUTH. Guarda destinatario,
//   asunto y cuerpo ya decodificados, para que los tests lean el enlace de la invitación tal cual.

import { createServer, type IncomingMessage, type ServerResponse } from 'node:http'
import { createServer as createTcpServer, type Socket } from 'node:net'
import { DOUBLES_HTTP_PORT, DOUBLES_SMTP_PORT } from '../support/env.ts'

export interface PosOrderCall {
  receivedAt: string
  path: string
  authorization: string | null
  companyId: string | null
  idempotencyKey: string | null
  requestId: string | null
  body: unknown
}

export interface CapturedMail {
  receivedAt: string
  to: string[]
  subject: string
  body: string
}

const posOrders: PosOrderCall[] = []
const mails: CapturedMail[] = []

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
  const chunks: Buffer[] = []
  for await (const chunk of req) chunks.push(chunk as Buffer)
  return Buffer.concat(chunks).toString('utf8')
}

const http = createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`)
  try {
    if (req.method === 'POST' && url.pathname === '/api/v1/orders/external') {
      const raw = await readBody(req)
      posOrders.push({
        receivedAt: new Date().toISOString(),
        path: url.pathname,
        authorization: header(req, 'Authorization'),
        companyId: header(req, 'X-Company-ID'),
        idempotencyKey: header(req, 'Idempotency-Key'),
        requestId: header(req, 'X-Request-Id'),
        body: raw ? JSON.parse(raw) : null,
      })
      // Sin resultados de stock: la comprobación de divergencia de la respuesta no tiene nada que comparar.
      sendJson(res, 200, { success: true, data: { results: [] } })
      return
    }
    if (req.method === 'POST' && url.pathname === '/api/v1/products/snapshot') {
      await readBody(req)
      sendJson(res, 200, { data: [] })
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
