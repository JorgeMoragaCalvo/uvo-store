// PROD-14a. El lado del POS: credenciales de la conexión, el catálogo que empuja, los webhooks firmados que
// manda y las ventas que recibe su doble. Sigue el contrato tal como lo implementa el backend (POS-1, el
// contrato confirmado con UvoPOS, sigue abierto).

import { createHmac, randomBytes } from 'node:crypto'
import { http, type HttpResult } from './http.ts'
import { BACKEND_URL, DOUBLES_URL } from './env.ts'
import type { PosOrderCall } from '../doubles/server.ts'

export interface PosCredentials {
  companyId: number
  /** `uvp_<empresa>_<≥32 alfanuméricos>`: el backend rechaza otra forma (PosApiKey). */
  apiKey: string
  webhookSecret: string
}

export function newPosCredentials(companyId: number): PosCredentials {
  return {
    companyId,
    apiKey: `uvp_${companyId}_${randomBytes(20).toString('hex')}`,
    webhookSecret: randomBytes(24).toString('hex'),
  }
}

function posHeaders(credentials: PosCredentials): Record<string, string> {
  return { Authorization: `Bearer ${credentials.apiKey}`, 'X-Company-ID': String(credentials.companyId) }
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
  return http('POST', `${BACKEND_URL}/api/sync/product`, {
    headers: posHeaders(credentials),
    body: { companyId: credentials.companyId, active: true, ...product },
  })
}

export interface SignedWebhook {
  /** Manda el webhook. Llamarlo dos veces reenvía exactamente la misma petición (cuerpo, hora y firma). */
  send(): Promise<HttpResult>
}

/**
 * Un webhook del POS firmado como lo verifica PosWebhookAuthFilter: X-Signature es el HMAC-SHA256 en hex de
 * `cuerpo + timestamp` con el secreto de la conexión, y X-Timestamp va en segundos.
 */
export function signedWebhook(event: string, credentials: PosCredentials, payload: object): SignedWebhook {
  const body = JSON.stringify(payload)
  const timestamp = String(Math.floor(Date.now() / 1000))
  const signature = createHmac('sha256', credentials.webhookSecret).update(body + timestamp).digest('hex')
  return {
    send: () =>
      http('POST', `${BACKEND_URL}/api/webhooks/pos/${event}`, {
        headers: { 'X-Signature': signature, 'X-Company-ID': String(credentials.companyId), 'X-Timestamp': timestamp },
        body,
      }),
  }
}

/** Las ventas que recibió el doble del POS en `/api/v1/orders/external`. */
export async function posOrdersReceived(): Promise<PosOrderCall[]> {
  const result = await http('GET', `${DOUBLES_URL}/__doubles/pos/orders`)
  return result.json as PosOrderCall[]
}
