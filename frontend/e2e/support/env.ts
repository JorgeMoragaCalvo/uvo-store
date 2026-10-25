// PROD-14a. Lo que comparten el lanzador, los dobles, la configuración de Playwright y los tests.
//
// Los puertos son propios para no chocar con el entorno de desarrollo (8080 y 5173), así que la red puede
// correr con el backend y Vite de siempre levantados.
//
// Los secretos son fijos y SOLO de prueba: el backend de esta red corre contra una base que se borra en cada
// corrida, y los tests y el lanzador corren en procesos distintos, así que un valor aleatorio no se podría
// compartir sin escribirlo a disco. No sirven para nada fuera de aquí.

export const BACKEND_PORT = 18080
export const VITE_PORT = 15173
export const DOUBLES_HTTP_PORT = 18089
export const DOUBLES_SMTP_PORT = 12525

export const BACKEND_URL = `http://127.0.0.1:${BACKEND_PORT}`
export const DOUBLES_URL = `http://127.0.0.1:${DOUBLES_HTTP_PORT}`

/** URL del storefront de una tienda a través de Vite (Chromium resuelve `*.localhost` solo). */
export function storeUrl(slug: string, path = ''): string {
  return `http://${slug}.localhost:${VITE_PORT}${path}`
}

/** El formulario de plataforma no depende de la tienda: va por `localhost` a secas. */
export const PLATFORM_URL = `http://localhost:${VITE_PORT}`

export const E2E_JWT_SECRET = 'e2e-only-jwt-secret-for-local-browser-tests-0123456789'
export const E2E_ENCRYPTION_KEY = Buffer.from('e2e-only-encryption-key-32-bytes').toString('base64')
export const E2E_PLATFORM_API_KEY = 'e2e-only-platform-key-for-local-browser-tests'

/** La base de la red. El lanzador la borra y la crea en cada corrida, así que el nombre tiene que delatarla. */
export const E2E_DB_NAME = process.env.E2E_DB_NAME ?? 'uvostore_e2e'
