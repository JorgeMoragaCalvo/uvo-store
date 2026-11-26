// PROD-14a e IMP-08. El lado del POS: las llaves de la conexión, el catálogo que empuja, los avisos firmados que
// manda y las ventas que recibe su doble. Sigue el contrato UvoPOS ↔ uvo-store v1 (docs/contrato-pos-ecommerce.md).

import { createHash, createHmac, randomBytes } from 'node:crypto'
import { http, type HttpResult } from './http.ts'
import { BACKEND_URL, DOUBLES_URL } from './env.ts'
import type { PosFault, PosOrderCall } from '../doubles/server.ts'

/** Las dos llaves de una conexión (§2): con la tienda→POS firma la tienda, con la POS→tienda firma el POS. */
export interface PosCredentials {
  companyId: number
  outboundKeyId: string
  outboundSecret: string
  inboundKeyId: string
  inboundSecret: string
}

export function newPosCredentials(companyId: number): PosCredentials {
  return {
    companyId,
    outboundKeyId: `pos-${companyId}-tienda-${randomBytes(4).toString('hex')}`,
    outboundSecret: randomBytes(32).toString('base64'),
    inboundKeyId: `pos-${companyId}-pos-${randomBytes(4).toString('hex')}`,
    inboundSecret: randomBytes(32).toString('base64'),
  }
}

/** El doble del POS verifica lo que firma la tienda: necesita la llave tienda→POS de la conexión. */
export async function registerWithPosDouble(credentials: PosCredentials): Promise<void> {
  await http('POST', `${DOUBLES_URL}/__doubles/pos/keys`, {
    body: { keyId: credentials.outboundKeyId, secret: credentials.outboundSecret, companyId: credentials.companyId },
  })
}

/** §3: las cabeceras UVO-HMAC de una petición del POS a la tienda, con la llave POS→tienda. */
function signedHeaders(credentials: PosCredentials, path: string, body: string, nonce: string): Record<string, string> {
  const timestamp = String(Math.floor(Date.now() / 1000))
  const canonical = [
    'UVO-HMAC-SHA256',
    'POST',
    path,
    timestamp,
    nonce,
    credentials.inboundKeyId,
    createHash('sha256').update(body, 'utf8').digest('hex'),
  ].join('\n')
  const signature = createHmac('sha256', Buffer.from(credentials.inboundSecret, 'base64')).update(canonical).digest('hex')
  return {
    'X-Uvo-Key-Id': credentials.inboundKeyId,
    'X-Uvo-Timestamp': timestamp,
    'X-Uvo-Nonce': nonce,
    'X-Uvo-Signature': `v1=${signature}`,
    'X-Company-ID': String(credentials.companyId),
    'X-Uvo-Contract': '1',
  }
}

function newNonce(): string {
  return randomBytes(24).toString('base64url')
}

export interface PosProduct {
  externalId: number
  sku: string
  name: string
  price: number
  stock: number
  version: number
  categoryName?: string
}

/** El POS empuja un producto de su catálogo (`/api/sync/product`). La tienda sale de la conexión, no del Host. */
export function syncProduct(credentials: PosCredentials, product: PosProduct): Promise<HttpResult> {
  const path = '/api/sync/product'
  const body = JSON.stringify({ companyId: credentials.companyId, active: true, ...product })
  return http('POST', `${BACKEND_URL}${path}`, { headers: signedHeaders(credentials, path, body, newNonce()), body })
}

export interface SignedWebhook {
  /** Manda el aviso. Llamarlo dos veces reenvía exactamente la misma petición (cuerpo, nonce y firma). */
  send(): Promise<HttpResult>
}

/** Un aviso del POS firmado como lo verifica PosSignatureAuthFilter (§3). */
export function signedWebhook(event: string, credentials: PosCredentials, payload: object): SignedWebhook {
  const path = `/api/webhooks/pos/${event}`
  const body = JSON.stringify(payload)
  const headers = signedHeaders(credentials, path, body, newNonce())
  return { send: () => http('POST', `${BACKEND_URL}${path}`, { headers, body }) }
}

/** Las ventas que recibió el doble del POS en `/api/v1/orders/external`. */
export async function posOrdersReceived(): Promise<PosOrderCall[]> {
  const result = await http('GET', `${DOUBLES_URL}/__doubles/pos/orders`)
  return result.json as PosOrderCall[]
}

/** IMP-08. Los fallos que el doble aplica, en orden, a las próximas ventas (§10). */
export async function injectPosFaults(...faults: PosFault[]): Promise<void> {
  await http('POST', `${DOUBLES_URL}/__doubles/pos/faults`, { body: { faults } })
}
