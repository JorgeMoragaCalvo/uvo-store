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
  isSidebarOpen: boolean
  openSidebar: () => void
  closeSidebar: () => void
  toggleSidebar: () => void
  addItem: (product: Product, quantity?: number, variation?: ProductVariation | null) => void
  updateQuantity: (id: number, type: 'product' | 'variation', quantity: number) => void
  removeItem: (id: number, type: 'product' | 'variation') => void
  clearCart: () => void
  setDestination: (region: string, commune: string) => Promise<void>
  setCouponCode: (couponCode: string) => Promise<void>
  calculateTotals: () => Promise<void>
  validateCart: () => Promise<{ valid: boolean }>
}

export const useCartStore = create<CartState>((set, get) => ({
  items: loadFromLocalStorage(),
  totals: EMPTY_TOTALS,
  region: '',
  commune: '',
  couponCode: '',
  loading: false,
  isSidebarOpen: false,

  openSidebar: () => set({ isSidebarOpen: true }),
  closeSidebar: () => set({ isSidebarOpen: false }),
  toggleSidebar: () => set((state) => ({ isSidebarOpen: !state.isSidebarOpen })),

  addItem(product, quantity = 1, variation = null) {
    const type = variation ? 'variation' : 'product'
    const id = variation ? variation.id : product.id
    const items = [...get().items]
    const existing = items.find((item) => item.id === id && item.type === type)

    if (existing) {
      existing.quantity += quantity
    } else {
      items.push({ id, type, product, variation, quantity })
    }

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
  },

  removeItem(id, type) {
    const items = get().items.filter((item) => !(item.id === id && item.type === type))
    saveToLocalStorage(items)
    set({ items })
    get().calculateTotals()
  },

  clearCart() {
    saveToLocalStorage([])
    set({ items: [], totals: EMPTY_TOTALS, region: '', commune: '', couponCode: '' })
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
      set({ totals: mapTotals(result) })
    } catch (error) {
      console.error('Error calculating cart totals', error)
    }
  },

  async validateCart() {
    const items = get().items
    if (items.length === 0) return { valid: true }

    set({ loading: true })
    try {
      const payload: CartItemInput[] = items.map((item) => ({ id: item.id, type: item.type, quantity: item.quantity }))
      const result = await api.cart.validate(payload)

      if (!result.valid) {
        const updated = get().items.map((item) => {
          const validated = result.items.find((v) => v.id === item.id && v.type === item.type)
          return validated && item.quantity > validated.maxQuantity
            ? { ...item, quantity: validated.maxQuantity }
            : item
        })
        saveToLocalStorage(updated)
        set({ items: updated })
      }

      return { valid: result.valid }
    } catch (error) {
      console.error('Error validating cart', error)
      return { valid: false }
    } finally {
      set({ loading: false })
    }
  },
}))

export function selectItemCount(state: CartState): number {
  return state.items.reduce((sum, item) => sum + item.quantity, 0)
}

// Recalculate totals for whatever was persisted from a previous session.
useCartStore.getState().calculateTotals()
