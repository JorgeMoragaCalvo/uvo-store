import { create } from 'zustand'
import api from '../services/api'
import type { CartCalculationResult, CartItemInput, Product, ProductVariation } from '../types/api'

// Shared with the legacy Blade app.js cart's localStorage key, so a customer's in-progress
// cart isn't lost when this React app replaces it.
const STORAGE_KEY = 'uvostore_cart'

export interface CartLine {
  id: number
  type: 'product' | 'variation'
  product: Product
  variation: ProductVariation | null
  quantity: number
}

export interface CartTotals {
  subtotal: number
  shippingCost: number
  taxAmount: number
  discountAmount: number
  total: number
  pricesIncludeTax: boolean
  taxRate: number
  freeShippingThreshold: number | null
  shippingEnabled: boolean
  shippingAvailable: boolean
  couponApplied: boolean
}

const EMPTY_TOTALS: CartTotals = {
  subtotal: 0,
  shippingCost: 0,
  taxAmount: 0,
  discountAmount: 0,
  total: 0,
  pricesIncludeTax: false,
  taxRate: 0,
  freeShippingThreshold: null,
  shippingEnabled: false,
  // Optimistic until a real calculation says otherwise: an empty cart shouldn't render as
  // "we don't deliver here".
  shippingAvailable: true,
  couponApplied: false,
}

function mapTotals(result: CartCalculationResult): CartTotals {
  return {
    subtotal: result.pricesIncludeTax ? result.subtotalWithTax : result.subtotalWithoutTax,
    shippingCost: result.shippingCost,
    taxAmount: result.taxAmount,
    discountAmount: result.discountAmount,
    total: result.total,
    pricesIncludeTax: result.pricesIncludeTax,
    taxRate: result.taxRate,
    freeShippingThreshold: result.freeShippingThreshold,
    shippingEnabled: result.shippingEnabled,
    shippingAvailable: result.shippingAvailable,
    couponApplied: result.couponApplied,
  }
}

// A variation is its own cart line; a simple product is keyed by the product itself.
function lineKey(product: Product, variation: ProductVariation | null) {
  return variation
    ? { id: variation.id, type: 'variation' as const }
    : { id: product.id, type: 'product' as const }
}

export function cartLineKey(line: { id: number; type: string }) {
  return `${line.type}:${line.id}`
}

// El backend responde los problemas por posición ("items.0", "items.1", …) —así en /cart/validate como en
// el 409 de stock del checkout (OutOfStockException)—; aquí se pasan a la clave de cada línea, que no se
// corre si después se quita otra.
function errorsByLine(items: CartLine[], errors: Record<string, string> | null | undefined) {
  const byLine: Record<string, string> = {}
  for (const [key, message] of Object.entries(errors ?? {})) {
    const item = items[Number(key.split('.')[1])]
    if (item) byLine[cartLineKey(item)] = message
  }
  return byLine
}

function saveToLocalStorage(items: CartLine[]) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(items))
}

function loadFromLocalStorage(): CartLine[] {
  const raw = localStorage.getItem(STORAGE_KEY)
  if (!raw) return []
  try {
    return JSON.parse(raw) as CartLine[]
  } catch {
    return []
  }
}

interface CartState {
  items: CartLine[]
  totals: CartTotals
  // A7: destination and coupon live here, next to the totals they determine, rather than in
  // useCheckoutStore — this store is the one that calls /cart/calculate, and duplicating them
  // across both is how they'd drift apart. The checkout reads and writes them through the setters.
  region: string
  commune: string
  couponCode: string
  loading: boolean
  // PROD-24b. Lo que el carrito guardado en localStorage no sabe: si el producto sigue a la venta y
  // cuánto hay. Lo llena validateCart() (o el 409 de stock del checkout) y lo pinta cada línea.
  lineErrors: Record<string, string>
  maxQuantities: Record<string, number>
  // El último /cart/calculate falló: los totales en pantalla pueden ya no ser verdad.
  totalsError: boolean
  isSidebarOpen: boolean
  openSidebar: () => void
  closeSidebar: () => void
  toggleSidebar: () => void
  addItem: (product: Product, quantity?: number, variation?: ProductVariation | null) => void
  ensureItem: (product: Product, quantity: number, variation?: ProductVariation | null) => void
  updateQuantity: (id: number, type: 'product' | 'variation', quantity: number) => void
  removeItem: (id: number, type: 'product' | 'variation') => void
  clearCart: () => void
  setDestination: (region: string, commune: string) => Promise<void>
  setCouponCode: (couponCode: string) => Promise<void>
  calculateTotals: () => Promise<void>
  validateCart: () => Promise<{ valid: boolean }>
  applyStockErrors: (errors: Record<string, string> | null | undefined) => void
}

export const useCartStore = create<CartState>((set, get) => ({
  items: loadFromLocalStorage(),
  totals: EMPTY_TOTALS,
  region: '',
  commune: '',
  couponCode: '',
  loading: false,
  lineErrors: {},
  maxQuantities: {},
  totalsError: false,
  isSidebarOpen: false,

  openSidebar: () => set({ isSidebarOpen: true }),
  closeSidebar: () => set({ isSidebarOpen: false }),
  toggleSidebar: () => set((state) => ({ isSidebarOpen: !state.isSidebarOpen })),

  // Adds to whatever is already there: pressing "Agregar al Carrito" three times must leave three.
  addItem(product, quantity = 1, variation = null) {
    const { id, type } = lineKey(product, variation)
    const current = get().items
    const existing = current.find((item) => item.id === id && item.type === type)
    // Rebuilt rather than mutated: `[...items]` copies the array but not the line objects, so
    // `existing.quantity += ...` would also change the quantity held by the previous state. Nothing
    // compares lines by reference today, so it doesn't show — but it would the moment something did.
    const items = existing
      ? current.map((item) =>
          item.id === id && item.type === type ? { ...item, quantity: item.quantity + quantity } : item,
        )
      : [...current, { id, type, product, variation, quantity }]

    saveToLocalStorage(items)
    set({ items })
    get().calculateTotals()
  },

  // Guarantees the product is in the cart without changing what's already there — what "Comprar
  // Ahora" needs. It must neither add nor subtract: addItem() would double a product that was
  // already added, and setting it to the quantity selector's value would silently REDUCE a cart the
  // customer had built by pressing "Agregar al Carrito" more than once, since the selector stays at
  // 1 throughout.
  ensureItem(product, quantity, variation = null) {
    const { id, type } = lineKey(product, variation)
    const current = get().items
    // Already there: leave the cart exactly as the customer left it. No set(), no recalculation —
    // there's nothing to recalculate, and firing one would cost a request and a re-render for
    // nothing.
    if (current.some((item) => item.id === id && item.type === type)) {
      return
    }

    const items = [...current, { id, type, product, variation, quantity }]
    saveToLocalStorage(items)
    set({ items })
    get().calculateTotals()
  },

  updateQuantity(id, type, quantity) {
    const items = get().items.map((item) =>
      item.id === id && item.type === type ? { ...item, quantity } : item,
    )
    saveToLocalStorage(items)
    set({ items })
    get().calculateTotals()
    // Si la línea tenía un aviso de stock, se vuelve a preguntar: la cantidad nueva puede resolverlo.
    if (Object.keys(get().lineErrors).length > 0) get().validateCart()
  },

  removeItem(id, type) {
    const items = get().items.filter((item) => !(item.id === id && item.type === type))
    saveToLocalStorage(items)
    set({ items })
    get().calculateTotals()
    if (Object.keys(get().lineErrors).length > 0) get().validateCart()
  },

  clearCart() {
    saveToLocalStorage([])
    set({
      items: [],
      totals: EMPTY_TOTALS,
      region: '',
      commune: '',
      couponCode: '',
      lineErrors: {},
      maxQuantities: {},
      totalsError: false,
    })
  },

  // Both re-price immediately: destination decides the shipping cost and the coupon decides the
  // discount, so leaving the old totals on screen would show a number that is no longer true.
  async setDestination(region, commune) {
    set({ region, commune })
    await get().calculateTotals()
  },

  async setCouponCode(couponCode) {
    set({ couponCode })
    await get().calculateTotals()
  },

  async calculateTotals() {
    const items = get().items
    if (items.length === 0) {
      set({ totals: EMPTY_TOTALS })
      return
    }

    try {
      const payload: CartItemInput[] = items.map((item) => ({ id: item.id, type: item.type, quantity: item.quantity }))
      // A7: these three were never passed, so the backend priced every cart with region=null —
      // no shipping zone could ever match and shipping came out free on every single order.
      const { region, commune, couponCode } = get()
      const result = await api.cart.calculate(payload, region || undefined, commune || undefined, couponCode || undefined)
      set({ totals: mapTotals(result), totalsError: false })
    } catch (error) {
      // PROD-24b. Antes solo quedaba en la consola, y en pantalla seguían los totales anteriores como si
      // fueran los de este carrito. Pasa, por ejemplo, con un producto que se eliminó de la tienda.
      console.error('Error calculating cart totals', error)
      set({ totalsError: true })
    }
  },

  // PROD-24b. Pregunta al backend por cada línea: si sigue a la venta y cuánto stock hay. No cambia el
  // carrito por su cuenta —el comprador ve el aviso en la línea y decide—, pero sí guarda el máximo de
  // cada una para que el «+» no deje pedir más de lo que hay. Antes este método existía y nadie lo usaba,
  // y además nunca ajustaba nada: las líneas sin stock suficiente no vienen en `items`, sino en `errors`.
  async validateCart() {
    const items = get().items
    if (items.length === 0) {
      set({ lineErrors: {}, maxQuantities: {} })
      return { valid: true }
    }

    set({ loading: true })
    try {
      const payload: CartItemInput[] = items.map((item) => ({ id: item.id, type: item.type, quantity: item.quantity }))
      const result = await api.cart.validate(payload)

      const maxQuantities: Record<string, number> = {}
      for (const validated of result.items) maxQuantities[cartLineKey(validated)] = validated.maxQuantity
      set({ lineErrors: errorsByLine(items, result.errors), maxQuantities })

      return { valid: result.valid }
    } catch (error) {
      // Sin respuesta no se sabe nada nuevo: se deja el carrito como estaba. El checkout vuelve a
      // comprobar el stock en el servidor antes de crear la orden.
      console.error('Error validating cart', error)
      return { valid: true }
    } finally {
      set({ loading: false })
    }
  },

  applyStockErrors(errors) {
    set({ lineErrors: errorsByLine(get().items, errors) })
  },
}))

export function selectItemCount(state: CartState): number {
  return state.items.reduce((sum, item) => sum + item.quantity, 0)
}

// Recalculate totals for whatever was persisted from a previous session.
useCartStore.getState().calculateTotals()
