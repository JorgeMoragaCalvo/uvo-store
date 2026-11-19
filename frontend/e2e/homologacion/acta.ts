// IMP-07. La evidencia de la homologación: cada caso anota qué esperaba, qué obtuvo y qué dijo el proveedor, con
// identificadores que se pueden buscar (número de orden, orden de compra, código de autorización) y sin secretos: ni
// tokens, ni datos de tarjeta, ni credenciales. Al terminar se escribe en e2e/.logs/homologacion/, en JSON y en una
// tabla Markdown lista para pegar en docs/homologacion-pagos.md.

import { mkdirSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { queryE2e } from '../support/database.ts'

export interface Registro {
  caso: string
  orden: string
  esperado: string
  obtenido: string
  /** null: el proveedor no permitió concluir (por ejemplo, no se observó lo que había que observar). */
  cumple: boolean | null
  /** Lo que respondió el proveedor, ya saneado. */
  proveedor?: Record<string, unknown>
}

const registros: Registro[] = []

export function registrar(registro: Registro): void {
  registros.push({ ...registro, proveedor: registro.proveedor ? sanear(registro.proveedor) : undefined })
}

// Nada que se parezca a un token o a una tarjeta llega al acta, aunque un caso lo pase por error.
const PROHIBIDAS = /token|card_number|cvv|password|secret|api.?key/i

function sanear(datos: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(datos)
      .filter(([clave]) => !PROHIBIDAS.test(clave))
      .map(([clave, valor]) => [
        clave,
        valor && typeof valor === 'object' && !Array.isArray(valor) ? sanear(valor as Record<string, unknown>) : valor,
      ]),
  )
}

export function escribirActa(pasarela: string): string {
  const dir = path.resolve(import.meta.dirname, '..', '.logs', 'homologacion')
  mkdirSync(dir, { recursive: true })
  const fecha = new Date().toISOString()
  const base = path.join(dir, `${fecha.slice(0, 19).replace(/[:T]/g, '-')}-${pasarela}`)
  writeFileSync(`${base}.json`, JSON.stringify({ pasarela, fecha, registros }, null, 2))

  const filas = registros.map((r) => {
    const proveedor = r.proveedor
      ? Object.entries(r.proveedor)
          .filter(([, v]) => v !== undefined && v !== null)
          .map(([k, v]) => `${k}=${JSON.stringify(v)}`)
          .join(', ')
      : ''
    return `| ${r.caso} | ${r.orden} | ${r.esperado} | ${r.obtenido} | ${proveedor} | ${r.cumple === null ? 'Sin conclusión' : r.cumple ? 'Cumple' : '**No cumple**'} |`
  })
  writeFileSync(
    `${base}.md`,
    [
      `Homologación ${pasarela}, ${fecha}`,
      '',
      '| Caso | Orden | Esperado | Obtenido | Proveedor | Resultado |',
      '|---|---|---|---|---|---|',
      ...filas,
      '',
    ].join('\n'),
  )
  return base
}

/** El token de pago que guardó la orden (orders.payment_id). Solo para consultar al proveedor; nunca va al acta. */
export async function paymentIdOf(orderNumber: string): Promise<string> {
  const [row] = await queryE2e<{ payment_id: string | null }>('SELECT payment_id FROM orders WHERE order_number = $1', [
    orderNumber,
  ])
  if (!row?.payment_id) throw new Error(`La orden ${orderNumber} no tiene payment_id`)
  return row.payment_id
}

/** La última orden de un comprador: el checkout no muestra el número antes de ir a la pasarela. */
export async function lastOrderOf(email: string): Promise<string> {
  const [row] = await queryE2e<{ order_number: string }>(
    'SELECT order_number FROM orders WHERE customer_email = $1 ORDER BY id DESC LIMIT 1',
    [email],
  )
  if (!row) throw new Error(`No hay órdenes de ${email}`)
  return row.order_number
}

// ---------------------------------------------------------------------------------------------- Transbank

/**
 * Las credenciales PÚBLICAS del ambiente de integración de Transbank para Webpay Plus Mall: las mismas que el SDK y
 * que application.properties traen por defecto. No sirven fuera de integración.
 */
const TRANSBANK_INTEGRACION = {
  base: 'https://webpay3gint.transbank.cl/rswebpaytransaction/api/webpay/v1.2/transactions',
  commerceCode: '597055555535',
  apiKey: '579B532A7440BB0C9079DED94D31EA1615BACEB56610332264630D42D0A36B1C',
}

export interface TransbankStatus {
  httpStatus: number
  vci?: string | null
  buy_order?: string
  status?: string
  response_code?: number
  authorization_code?: string | null
  amount?: number
  balance?: number
}

/** Lo que Transbank dice de la transacción de una orden, como lo vería la conciliación (status, nunca commit). */
export async function transbankStatus(orderNumber: string): Promise<TransbankStatus> {
  const token = await paymentIdOf(orderNumber)
  const res = await fetch(`${TRANSBANK_INTEGRACION.base}/${token}`, {
    headers: { 'Tbk-Api-Key-Id': TRANSBANK_INTEGRACION.commerceCode, 'Tbk-Api-Key-Secret': TRANSBANK_INTEGRACION.apiKey },
  })
  const body = (await res.json().catch(() => ({}))) as {
    vci?: string
    buy_order?: string
    details?: { status: string; response_code: number; authorization_code: string | null; amount: number; balance?: number }[]
    error_message?: string
  }
  const detail = body.details?.[0]
  return {
    httpStatus: res.status,
    vci: body.vci ?? null,
    buy_order: body.buy_order,
    status: detail?.status ?? body.error_message,
    response_code: detail?.response_code,
    authorization_code: detail?.authorization_code ?? null,
    amount: detail?.amount,
    balance: detail?.balance,
  }
}
