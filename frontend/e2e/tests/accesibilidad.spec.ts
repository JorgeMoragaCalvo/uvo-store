// GAP-19. Revisión automática de accesibilidad (axe) del storefront, para que lo que arregló PROD-24b no vuelva a
// romperse sin que nadie lo note. No reemplaza el recorrido manual con teclado y lector de pantalla de
// docs/pruebas-manuales-gap-19.md: axe ve etiquetas, roles, nombres y contraste, pero no si el orden del foco
// tiene sentido ni si lo que anuncia un lector se entiende.
//
// Falla ante violaciones `serious` o `critical` de WCAG 2.1 A/AA. Las de impacto menor y las que axe no puede
// decidir (p. ej. el contraste del texto sobre la imagen del banner) quedan en el adjunto `axe-<página>.json`
// del reporte, para revisarlas a mano.
//
// Solo en escritorio: el proyecto móvil corre únicamente las pasarelas (playwright.config.ts).

import { AxeBuilder } from '@axe-core/playwright'
import { expect, test, type Page, type TestInfo } from '@playwright/test'
import { queryE2e } from '../support/database.ts'
import { storeUrl } from '../support/env.ts'
import { linkIn, waitForMail } from '../support/mail.ts'
import { acceptInvitation, createProduct, createShippingZone, disableShipping } from '../support/panel.ts'
import { createStoreViaApi } from '../support/platform.ts'
import { fillCheckoutForms, openProduct, trackOrder, type Buyer } from '../support/storefront.ts'

const SLUG = 'accesibilidad'
const STORE_NAME = 'Tienda Accesible'
const OWNER_EMAIL = `duena-${SLUG}@e2e.test`
const PASSWORD = 'Clave-E2e-Accesibilidad-2026'
const REGION = 'Metropolitana de Santiago'
// Destacado: así la tarjeta muestra la etiqueta sobre el color de acento, y axe revisa su contraste.
const PRODUCT = { name: 'Té accesible', sku: 'ACC-TE-01', price: 4990, stock: 20, featured: true }
const BUYER: Buyer = {
  email: `comprador-${SLUG}@e2e.test`,
  firstName: 'Valentina',
  lastName: 'Soto',
  phone: '+56933333333',
  address: 'Los Olmos 456',
  city: 'Santiago',
  postalCode: '8320000',
  region: REGION,
  commune: 'Santiago',
}
const WCAG_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']
const BLOCKING = new Set(['serious', 'critical'])

/** PNG de 1×1: el backend reconoce la imagen por su firma, no por el nombre ni el tipo declarado. */
const PNG_1X1 = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==',
  'base64',
)

const shop = { orderNumber: '' }

/**
 * Corre axe sobre la página tal como está y falla con un resumen legible si hay violaciones bloqueantes.
 * El resultado completo (también lo menor y lo incompleto) va adjunto al reporte.
 */
async function expectAccessible(page: Page, testInfo: TestInfo, label: string): Promise<void> {
  const results = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze()
  await testInfo.attach(`axe-${label}.json`, {
    body: JSON.stringify({ violations: results.violations, incomplete: results.incomplete }, null, 2),
    contentType: 'application/json',
  })
  const blocking = results.violations
    .filter((violation) => BLOCKING.has(violation.impact ?? ''))
    .map((violation) => {
      const nodes = violation.nodes.map((node) => `${node.html}\n      ${node.failureSummary ?? ''}`.trimEnd())
      return `${violation.id} (${violation.impact}): ${violation.help}\n    ${nodes.join('\n    ')}`
    })
  // Suave: el test sigue con las demás páginas y el reporte las muestra todas juntas.
  expect.soft(blocking, `${label}: violaciones de axe`).toEqual([])
}

async function createBanner(page: Page, title: string, sortOrder: number): Promise<void> {
  await page.goto(storeUrl(SLUG, '/admin/banners/new'))
  // Exactos: «Título» también está en «Subtítulo».
  await page.getByLabel('Título', { exact: true }).fill(title)
  await page.getByLabel('Descripción', { exact: true }).fill(`Descripción de ${title}`)
  await page.getByLabel('Texto botón principal', { exact: true }).fill('Ver tienda')
  await page.getByLabel('Link botón principal', { exact: true }).fill('/shop')
  await page.getByLabel('Orden', { exact: true }).fill(String(sortOrder))
  await page.getByLabel('Imagen (desktop)').setInputFiles({ name: 'banner.png', mimeType: 'image/png', buffer: PNG_1X1 })
  await page.getByRole('button', { name: 'Guardar' }).click()
  await expect(page.getByText('Banner creado')).toBeVisible()
  await expect(page).toHaveURL(/\/admin\/banners$/)
}

// La tienda se prepara una vez. Si un escaneo falla, Playwright reinicia el worker y vuelve a correr el beforeAll:
// la tienda ya existe y no se repite. Así una página con violaciones no impide revisar las demás.
test.beforeAll(async ({ browser }) => {
  test.setTimeout(120_000)
  const [existing] = await queryE2e<{ id: number }>('SELECT id FROM stores WHERE slug = $1', [SLUG])
  if (existing) return

  const page = await browser.newPage()
  try {
    const created = await createStoreViaApi({ slug: SLUG, storeName: STORE_NAME, adminName: 'Dueña Accesible', adminEmail: OWNER_EMAIL })
    expect(created.status, created.text).toBe(200)

    const invitation = await waitForMail(OWNER_EMAIL, `Te invitaron a administrar ${STORE_NAME}`)
    await acceptInvitation(page, linkIn(invitation, '/admin/aceptar-invitacion?token='), PASSWORD)

    await createShippingZone(page, SLUG, REGION)
    await disableShipping(page, SLUG)
    await createProduct(page, SLUG, PRODUCT)
    // Dos banners: con uno solo no hay flechas, puntos ni botón de pausa.
    await createBanner(page, 'Temporada de té', 0)
    await createBanner(page, 'Envíos a todo Chile', 1)
  } finally {
    await page.close()
  }
})

test('portada, tienda y producto', async ({ page }, testInfo) => {
  await page.goto(storeUrl(SLUG, '/'))
  await expect(page.getByRole('button', { name: 'Pausar el carrusel' })).toBeVisible()
  await expectAccessible(page, testInfo, 'portada')

  await page.goto(storeUrl(SLUG, '/shop'))
  await expect(page.getByRole('link', { name: PRODUCT.name }).first()).toBeVisible()
  await expect(page.getByText('Destacado', { exact: true }).first()).toBeVisible()
  await expectAccessible(page, testInfo, 'tienda')

  await openProduct(page, SLUG, PRODUCT.name)
  await expectAccessible(page, testInfo, 'producto')
})

test('carrito: diálogo y página', async ({ page }, testInfo) => {
  await openProduct(page, SLUG, PRODUCT.name)
  await page.getByRole('button', { name: 'Agregar al Carrito' }).click()
  await page.getByRole('button', { name: 'Abrir carrito' }).click()
  await expect(page.getByRole('dialog', { name: 'Tu carrito' })).toBeVisible()
  await expectAccessible(page, testInfo, 'carrito-dialogo')

  await page.keyboard.press('Escape')
  await page.goto(storeUrl(SLUG, '/cart'))
  await expect(page.getByText(PRODUCT.name).first()).toBeVisible()
  await expectAccessible(page, testInfo, 'carrito')
})

test('checkout: errores de campo, pago y confirmación', async ({ page }, testInfo) => {
  await openProduct(page, SLUG, PRODUCT.name)
  await page.getByRole('button', { name: 'Agregar al Carrito' }).click()
  await page.goto(storeUrl(SLUG, '/checkout'))
  await expect(page.getByRole('heading', { name: 'Contacto', level: 2 })).toBeVisible()
  // Sin datos: los mensajes quedan unidos a sus campos.
  await page.getByRole('button', { name: 'Continuar' }).click()
  await expect(page.locator('#email-error')).toBeVisible()
  await expectAccessible(page, testInfo, 'checkout-errores')

  await fillCheckoutForms(page, BUYER)
  await expectAccessible(page, testInfo, 'checkout-pago')

  await page.getByLabel('Pago contra entrega').check()
  await page.getByRole('button', { name: 'Confirmar Pedido' }).click()
  await expect(page.getByText('¡Gracias por tu compra!')).toBeVisible()
  shop.orderNumber = new URL(page.url()).searchParams.get('order') ?? ''
  expect(shop.orderNumber).toMatch(/^ORD-/)
  await expectAccessible(page, testInfo, 'confirmacion')

  await trackOrder(page, SLUG, shop.orderNumber)
  // El campo lo trae como valor, no como texto: lo único con ese texto es el resultado.
  await expect(page.getByText(shop.orderNumber, { exact: true })).toBeVisible()
  await expectAccessible(page, testInfo, 'seguimiento')
})

test('páginas legales y 404', async ({ page }, testInfo) => {
  const pages = [
    ['terminos', '/terminos-y-condiciones'],
    ['privacidad', '/politica-de-privacidad'],
    ['envios', '/politica-de-envios'],
    ['devoluciones', '/politica-de-devoluciones'],
  ] as const
  for (const [label, path] of pages) {
    await page.goto(storeUrl(SLUG, path))
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    await expectAccessible(page, testInfo, label)
  }

  await page.goto(storeUrl(SLUG, '/no-existe-esta-pagina'))
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  await expectAccessible(page, testInfo, '404')
})
