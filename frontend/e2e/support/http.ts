// PROD-14a. Llamadas HTTP directas desde los tests: la API de plataforma, la entrada del POS y la inspección
// de los dobles.
//
// Va con node:http y no con fetch, porque fetch no deja fijar la cabecera Host, y las llamadas a una tienda
// concreta la necesitan: el backend resuelve la tienda por Host, y el resolvedor de Node en Windows no
// resuelve `<slug>.localhost`, así que se conecta a 127.0.0.1 y se manda el Host a mano.

import { request } from 'node:http'

export interface HttpResult {
  status: number
  text: string
  json: unknown
}

export interface HttpOptions {
  headers?: Record<string, string>
  /** Si es string se manda tal cual (hace falta para firmar un webhook sobre el cuerpo exacto). */
  body?: unknown
}

export function http(method: string, url: string, options: HttpOptions = {}): Promise<HttpResult> {
  const payload =
    options.body === undefined ? undefined : typeof options.body === 'string' ? options.body : JSON.stringify(options.body)
  const headers: Record<string, string> = { Accept: 'application/json', ...options.headers }
  if (payload !== undefined) {
    headers['Content-Type'] ??= 'application/json'
    headers['Content-Length'] = String(Buffer.byteLength(payload))
  }
  return new Promise((resolve, reject) => {
    const req = request(url, { method, headers }, (res) => {
      const chunks: Buffer[] = []
      res.on('data', (chunk: Buffer) => chunks.push(chunk))
      res.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8')
        resolve({ status: res.statusCode ?? 0, text, json: parseJson(text) })
      })
    })
    req.on('error', reject)
    if (payload !== undefined) req.write(payload)
    req.end()
  })
}

function parseJson(text: string): unknown {
  try {
    return text ? JSON.parse(text) : null
  } catch {
    return null
  }
}

/** Host con el que el backend resuelve una tienda de la plataforma. */
export function storeHost(slug: string): string {
  return `${slug}.localhost`
}
