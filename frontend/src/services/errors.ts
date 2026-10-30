// PROD-24b. Qué texto ve el comprador cuando falla una llamada a la API.
//
// El interceptor de `api` rechaza con `{ message, status, ...cuerpo }`, y `message` era lo que se pintaba
// tal cual. Sin respuesta del servidor (red caída, timeout) ese mensaje es el de axios, en inglés
// ("Network Error"); con un 404 es el de la excepción de Java ("Order 42 not found"). Solo los 4xx de
// negocio traen un texto pensado para el comprador: BusinessException, stock, envío, validación y el
// rate limiter (GlobalExceptionHandler). Los 5xx ya llegan con un genérico en español, pero tampoco dicen
// nada útil en contexto, así que se usa el `fallback` de quien llama.

export const NETWORK_ERROR_MESSAGE = 'No pudimos conectar con la tienda. Revisa tu conexión e inténtalo de nuevo.'

export interface ApiFailure {
  message?: string
  status?: number
  fieldErrors?: Record<string, string> | null
}

const BUSINESS_STATUSES = new Set([400, 409, 422, 429])

export function friendlyError(error: unknown, fallback: string): string {
  const failure = (error ?? {}) as ApiFailure
  if (failure.status === undefined) return NETWORK_ERROR_MESSAGE
  if (BUSINESS_STATUSES.has(failure.status) && failure.message) return failure.message
  return fallback
}
