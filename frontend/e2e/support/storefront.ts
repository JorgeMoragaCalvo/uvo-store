// PROD-14a. Pasos del storefront: encontrar un producto, comprarlo con pago manual y seguir el pedido.

import { expect, type Page } from '@playwright/test'
import { storeUrl } from './env.ts'

export interface Buyer {
  email: string
  firstName: string
  lastName: string
  phone: string
  address: string
  city: string
  postalCode: string
  region: string
  commune: string
}

/** Busca el producto en /shop y entra a su ficha. */
export async function openProduct(page: Page, slug: string, productName: string): Promise<void> {
  await page.goto(storeUrl(slug, `/shop?search=${encodeURIComponent(productName)}`))
  await page.getByRole('link', { name: productName }).first().click()
  await expect(page.getByRole('heading', { name: productName })).toBeVisible()
}

export async function expectAvailableStock(page: Page, stock: number): Promise<void> {
  await expect(page.getByText(`${stock} disponibles`)).toBeVisible()
}

/** Agrega a carrito desde la ficha abierta y completa el checkout con «Pago contra entrega». Devuelve el número. */
export async function checkoutWithManualPayment(page: Page, buyer: Buyer): Promise<string> {
  await fillCheckoutUntilPayment(page, buyer)

  await page.getByLabel('Pago contra entrega').check()
  await page.getByRole('button', { name: 'Confirmar Pedido' }).click()

  await expect(page).toHaveURL(/\/order-success\?order=/)
  await expect(page.getByText('¡Gracias por tu compra!')).toBeVisible()
  const orderNumber = new URL(page.url()).searchParams.get('order')
  if (!orderNumber) throw new Error('La página de confirmación no trae el número de pedido')
  await expect(page.getByText(`Número de pedido: ${orderNumber}`)).toBeVisible()
  return orderNumber
}

/** Agrega a carrito desde la ficha abierta y llena el checkout hasta el paso «Pago». */
export async function fillCheckoutUntilPayment(page: Page, buyer: Buyer): Promise<void> {
  await page.getByRole('button', { name: 'Agregar al Carrito' }).click()
  await page.getByRole('button', { name: 'Abrir carrito' }).click()
  await page.getByRole('link', { name: 'Finalizar Compra' }).first().click()
  await expect(page).toHaveURL(/\/checkout$/)
  await fillCheckoutForms(page, buyer)
}

/** Contacto y dirección del checkout ya abierto, hasta el paso «Pago». */
export async function fillCheckoutForms(page: Page, buyer: Buyer): Promise<void> {
  await page.getByPlaceholder('Email').fill(buyer.email)
  await page.getByPlaceholder('Nombre').fill(buyer.firstName)
  await page.getByPlaceholder('Apellido').fill(buyer.lastName)
  await page.getByPlaceholder('Teléfono').fill(buyer.phone)
  await page.getByRole('button', { name: 'Continuar' }).click()

  await page.getByPlaceholder('Dirección', { exact: true }).fill(buyer.address)
  await page.getByPlaceholder('Ciudad').fill(buyer.city)
  await page.getByPlaceholder('Código postal').fill(buyer.postalCode)
  await page.getByLabel('Región').selectOption(buyer.region)
  await page.getByLabel('Comuna').selectOption(buyer.commune)
  await page.getByRole('button', { name: 'Continuar' }).click()
  await expect(page.getByRole('heading', { name: 'Pago', level: 2 })).toBeVisible()
}

export type GatewayOption = 'Webpay' | 'MercadoPago' | 'Tarjeta (Stripe)'

/**
 * GAP-08. En el paso «Pago», elige la pasarela y confirma: el navegador termina en la página de pago de su doble.
 * Devuelve el número de pedido, que esa página muestra (el checkout no lo enseña antes de salir).
 */
export async function confirmWith(page: Page, gateway: GatewayOption): Promise<string> {
  await page.getByLabel(gateway).check()
  await page.getByRole('button', { name: 'Confirmar Pedido' }).click()
  // Crear la orden y abrir la sesión de pago son dos llamadas, y la primera compra de la corrida va en frío.
  await expect(page).toHaveURL(/\/__gateway\/(webpay|mercadopago|stripe)\/pay/, { timeout: 30_000 })
  const text = await page.locator('p').first().innerText()
  const orderNumber = /(ORD-[A-Z0-9]+)/.exec(text)?.[1]
  if (!orderNumber) throw new Error(`La página de pago no trae el número de pedido: ${text}`)
  return orderNumber
}

/** El total que la página de pago del doble dice que va a cobrar, en pesos. */
export async function amountOnGatewayPage(page: Page): Promise<number> {
  const text = await page.locator('p').first().innerText()
  return Number((/\$([\d.]+)/.exec(text)?.[1] ?? '').replace(/\./g, ''))
}

/** Sigue el pedido en /track-order (el parámetro solo rellena el campo; hay que pulsar «Buscar»). */
export async function trackOrder(page: Page, slug: string, orderNumber: string): Promise<void> {
  await page.goto(storeUrl(slug, `/track-order?order=${encodeURIComponent(orderNumber)}`))
  await expect(page.getByPlaceholder('ej: ORD-AAXFD5GP')).toHaveValue(orderNumber)
  await page.getByRole('button', { name: 'Buscar' }).click()
}
