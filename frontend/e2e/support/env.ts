// PROD-14a. Lo que comparten el lanzador, los dobles, la configuración de Playwright y los tests.
//
// Los puertos son propios para no chocar con el entorno de desarrollo (8080 y 5173), así que la red puede
// correr con el backend y Vite de siempre levantados.
//
// Los secretos son fijos y SOLO de prueba: el backend de esta red corre contra una base que se borra en cada
// corrida, y los tests y el lanzador corren en procesos distintos, así que un valor aleatorio no se podría
// compartir sin escribirlo a disco. No sirven para nada fuera de aquí.

/**
 * IMP-04. Qué sirve el storefront en VITE_PORT:
 * - `dist` (por defecto, y en CI y en el release): el build de producción detrás de nginx, con la configuración
 *   de ops/nginx/ (e2e/launch/frontend.ts). Prueba la CSP real, las cabeceras, la caché y las rutas del proxy.
 * - `vite` (`npm run e2e:dev`): el servidor de desarrollo, más rápido de levantar y sin nginx. Sin CSP.
 */
export type E2eMode = 'dist' | 'vite'
export const E2E_MODE: E2eMode = parseMode(process.env.E2E_MODE)

function parseMode(value: string | undefined): E2eMode {
  if (!value?.trim()) return 'dist'
  if (value === 'dist' || value === 'vite') return value
  throw new Error(`E2E_MODE tiene que ser dist o vite; llegó «${value}»`)
}

/**
 * IMP-04. La dirección desde la que el nginx de `dist` se conecta al backend (proxy_bind), y la única que el
 * backend acepta como proxy (RATE_LIMIT_TRUSTED_PROXIES). Distinta de 127.0.0.1 a propósito, como en producción
 * el proxy tiene la suya: ClientIpResolver.resolveStrict no le cree a un proxy de confianza que llega sin
 * X-Forwarded-For, así que si 127.0.0.1 fuera el proxy, las llamadas directas de los tests a la API de
 * plataforma quedarían rechazadas.
 */
export const NGINX_UPSTREAM_ADDRESS = '127.0.0.2'

export const BACKEND_PORT = 18080
/** El puerto del storefront: Vite en la modalidad `vite`, nginx en `dist`. */
export const VITE_PORT = 15173
export const DOUBLES_HTTP_PORT = 18089
export const DOUBLES_SMTP_PORT = 12525

export const BACKEND_URL = `http://127.0.0.1:${BACKEND_PORT}`
export const DOUBLES_URL = `http://127.0.0.1:${DOUBLES_HTTP_PORT}`

/**
 * IMP-04. Sentry del build de la modalidad `dist`: el DSN apunta al doble (e2e/doubles/server.ts), así que su
 * origen entra en la CSP del build como entraría el de Sentry, y los eventos se pueden leer en /__doubles/sentry.
 * Lo comparten el lanzador (que compila con estos valores) y los tests (que los esperan en el evento).
 */
export const SENTRY_DOUBLE_DSN = `http://e2e@127.0.0.1:${DOUBLES_HTTP_PORT}/1`
export const E2E_SENTRY_ENVIRONMENT = 'e2e'
export const E2E_SENTRY_RELEASE = `e2e-${(process.env.GITHUB_SHA ?? 'local').slice(0, 7)}`

/** URL del storefront de una tienda (Chromium resuelve `*.localhost` solo). */
export function storeUrl(slug: string, path = ''): string {
  return `http://${slug}.localhost:${VITE_PORT}${path}`
}

/** El formulario de plataforma no depende de la tienda: va por `localhost` a secas. */
export const PLATFORM_URL = `http://localhost:${VITE_PORT}`

export const E2E_JWT_SECRET = 'e2e-only-jwt-secret-for-local-browser-tests-0123456789'
export const E2E_ENCRYPTION_KEY = Buffer.from('e2e-only-encryption-key-32-bytes').toString('base64')
export const E2E_PLATFORM_API_KEY = 'e2e-only-platform-key-for-local-browser-tests'
/** GAP-08. El secreto de los webhooks de Stripe es de la plataforma (STRIPE_WEBHOOK_SECRET), no de la tienda. */
export const E2E_STRIPE_WEBHOOK_SECRET = 'whsec_e2e_only_for_local_browser_tests'

/**
 * IMP-07. La homologación (`npm run e2e:homologacion`): qué pasarelas hablan con el proveedor real en vez de con su
 * doble. Vacío en el E2E de siempre. Ver playwright.homologacion.config.ts y docs/homologacion-pagos.md.
 */
export type HomologatedGateway = 'webpay' | 'mercadopago'
export const HOMOLOGACION: HomologatedGateway[] = parseHomologacion(process.env.HOMOLOGACION)

function parseHomologacion(value: string | undefined): HomologatedGateway[] {
  if (!value?.trim()) return []
  return value.split(',').map((raw) => {
    const gateway = raw.trim()
    if (gateway === 'webpay' || gateway === 'mercadopago') return gateway
    throw new Error(`HOMOLOGACION admite webpay y mercadopago, separados por coma; llegó «${gateway}»`)
  })
}

/**
 * IMP-07. Con MercadoPago real, el aviso de pago tiene que llegar desde Internet: el host público de un túnel HTTPS
 * hacia el backend (sin esquema), que la tienda usa como dominio. Vacío si no hay túnel.
 */
export const HOMOLOGACION_PUBLIC_HOST = process.env.HOMOLOGACION_PUBLIC_HOST?.trim() ?? ''

/** La base de la red. El lanzador la borra y la crea en cada corrida, así que el nombre tiene que delatarla. */
export const E2E_DB_NAME = process.env.E2E_DB_NAME ?? 'uvostore_e2e'
