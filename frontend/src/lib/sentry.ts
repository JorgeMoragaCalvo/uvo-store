import type { Breadcrumb, BrowserOptions, ErrorEvent } from '@sentry/react'

/**
 * PROD-15a. Qué se le entrega al SDK de Sentry: DSN, ambiente, versión, y lo que se borra antes de enviar.
 *
 * Sin `environment` y `release`, un error de staging aparece junto a los de producción y no se puede
 * decir de qué despliegue viene. El ambiente es una variable de BUILD (`VITE_SENTRY_ENVIRONMENT`), no de
 * ejecución: el bundle de Vite se queda con lo que había al construirlo, así que cada variante del
 * frontend se construye (y se registra como artefacto) con el suyo. Si no se define se usa `MODE` de Vite,
 * que al menos separa el servidor de desarrollo (`development`) de cualquier build (`production`).
 */
export interface SentryEnv {
  VITE_SENTRY_DSN?: string
  VITE_SENTRY_ENVIRONMENT?: string
  VITE_SENTRY_RELEASE?: string
  MODE: string
}

/**
 * Los nombres de parámetro de la query cuyo valor no debe llegar a un tercero. Es por NOMBRE y no una lista
 * cerrada de rutas: hoy el único es `token` (`/admin/reset-password?token=…` y
 * `/admin/aceptar-invitacion?token=…`, el secreto de un solo uso de esos correos), pero cuando exista otro
 * enlace de correo con `invitation_token` o `reset-token` queda cubierto sin acordarse de venir aquí.
 */
const SENSITIVE_PARAM = /token|password|secret|signature/i

export const FILTERED = '[Filtered]'

function notBlank(value: string | undefined): string | undefined {
  const trimmed = value?.trim()
  return trimmed ? trimmed : undefined
}

function safeDecode(value: string): string {
  try {
    return decodeURIComponent(value)
  } catch {
    return value // un `%` suelto no puede impedir el filtrado
  }
}

/** Filtra una query (sin el `?` inicial): conserva los nombres y sustituye el valor de los sensibles. */
function scrubQuery(query: string): string {
  return query
    .split('&')
    .map((pair) => {
      const eq = pair.indexOf('=')
      if (eq === -1) return pair // sin valor no hay nada que ocultar
      const name = safeDecode(pair.slice(0, eq))
      return SENSITIVE_PARAM.test(name) ? `${pair.slice(0, eq)}=${FILTERED}` : pair
    })
    .join('&')
}

/**
 * Sustituye el valor de los parámetros sensibles de una URL, absoluta o relativa (los breadcrumbs de
 * navegación del SDK son relativos: `/admin/reset-password?token=abc`). Se hace sobre el texto y no con
 * `new URL` precisamente para no necesitar una base para las relativas ni reescribir lo demás.
 */
export function scrubUrl(url: string): string {
  const queryStart = url.indexOf('?')
  if (queryStart === -1) return url
  const hashStart = url.indexOf('#', queryStart)
  const queryEnd = hashStart === -1 ? url.length : hashStart
  return `${url.slice(0, queryStart + 1)}${scrubQuery(url.slice(queryStart + 1, queryEnd))}${url.slice(queryEnd)}`
}

/**
 * Por dónde entra la dirección de la página en un evento: `request.url` (el SDK pone `location.href`
 * completo, con su query), `request.query_string` y la cabecera `Referer`.
 */
export function scrubEvent<T extends ErrorEvent>(event: T): T {
  const request = event.request
  if (!request) return event
  if (typeof request.url === 'string') request.url = scrubUrl(request.url)
  if (typeof request.query_string === 'string') request.query_string = scrubQuery(request.query_string)
  if (request.headers) {
    for (const name of Object.keys(request.headers)) {
      if (name.toLowerCase() === 'referer') request.headers[name] = scrubUrl(request.headers[name])
    }
  }
  return event
}

/**
 * Los breadcrumbs de navegación llevan `from`/`to` y los de red (`fetch`/`xhr`) `url`: los tres son
 * direcciones con su query.
 */
export function scrubBreadcrumb(breadcrumb: Breadcrumb): Breadcrumb {
  const data = breadcrumb.data
  if (!data) return breadcrumb
  for (const key of ['from', 'to', 'url']) {
    if (typeof data[key] === 'string') data[key] = scrubUrl(data[key])
  }
  return breadcrumb
}

/** `null` si no hay DSN: Sentry queda apagado, como el resto de integraciones externas hasta configurarlas. */
export function buildSentryOptions(env: SentryEnv): BrowserOptions | null {
  const dsn = notBlank(env.VITE_SENTRY_DSN)
  if (!dsn) return null
  return {
    dsn,
    environment: notBlank(env.VITE_SENTRY_ENVIRONMENT) ?? env.MODE,
    release: notBlank(env.VITE_SENTRY_RELEASE),
    beforeSend: scrubEvent,
    beforeBreadcrumb: scrubBreadcrumb,
  }
}
