import type { HtmlTagDescriptor, Plugin } from 'vite'

/**
 * GAP-13. La Content-Security-Policy de la SPA.
 *
 * El token del panel vive en localStorage (useAdminAuthStore) y la tienda y el panel comparten origen, así que
 * un script inyectado en cualquier página lo podría leer. Esta política es lo que impide que ese script corra o
 * mande algo fuera; la decisión completa está en docs/decision-sesion-panel.md.
 *
 * Va en una <meta> del index.html y no como cabecera: el index.html lo sirve el proxy de la plataforma, no
 * Spring, y así la política viaja con el módulo la sirva quien la sirva. Lo que una <meta> no admite
 * (frame-ancestors) lo tiene que poner el proxy como cabecera.
 */

// El POST de formulario a Transbank (Checkout.submitWebpayForm): integración y producción. Stripe y
// MercadoPago salen por window.location, que la CSP no controla.
const WEBPAY_HOSTS = ['https://webpay3g.transbank.cl', 'https://webpay3gint.transbank.cl']

export function buildContentSecurityPolicy({ sentryDsn }: { sentryDsn?: string }): string {
  const connect = ["'self'"]
  const sentryOrigin = originOf(sentryDsn)
  if (sentryOrigin) connect.push(sentryOrigin)

  return [
    "default-src 'self'",
    // Lo que protege el token: sin 'unsafe-inline' ni 'unsafe-eval', solo corre el bundle propio.
    "script-src 'self'",
    // sonner inyecta un <style>. Los colores del tema (useStoreSettingsStore) y los style={{}} de React van por
    // CSSOM, que la CSP no bloquea.
    "style-src 'self' 'unsafe-inline'",
    // https: por las imágenes en almacenamiento de objetos (app.storage.driver=s3), que no son de este origen.
    "img-src 'self' data: blob: https:",
    "font-src 'self' data:",
    // La API y /uploads son del mismo origen (window.location.origin en api.ts y adminApi.ts).
    `connect-src ${connect.join(' ')}`,
    `form-action 'self' ${WEBPAY_HOSTS.join(' ')}`,
    "base-uri 'self'",
    "object-src 'none'",
  ].join('; ')
}

/** Solo el origen del DSN: la clave que lleva delante de la @ no tiene nada que hacer en una cabecera. */
function originOf(dsn: string | undefined): string | null {
  if (!dsn?.trim()) return null
  try {
    return new URL(dsn.trim()).origin
  } catch {
    return null
  }
}

/**
 * Inyecta la CSP y la Referrer-Policy al principio del <head>, antes de cualquier <script>. Solo en el build: en
 * desarrollo @vitejs/plugin-react mete un script en línea (el preámbulo del refresco) que esta política bloquearía.
 * Referrer-Policy porque los enlaces de invitación y de reset llevan el token en la URL.
 */
export function contentSecurityPolicyPlugin(options: { sentryDsn?: string }): Plugin {
  return {
    name: 'uvostore-content-security-policy',
    apply: 'build',
    transformIndexHtml: (): HtmlTagDescriptor[] => [
      {
        tag: 'meta',
        attrs: { 'http-equiv': 'Content-Security-Policy', content: buildContentSecurityPolicy(options) },
        injectTo: 'head-prepend',
      },
      { tag: 'meta', attrs: { name: 'referrer', content: 'strict-origin-when-cross-origin' }, injectTo: 'head-prepend' },
    ],
  }
}
