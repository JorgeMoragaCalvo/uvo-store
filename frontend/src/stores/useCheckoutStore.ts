import { create } from 'zustand'
import api from '../services/api'
import { useCartStore } from './useCartStore'
import type { CheckoutAddress, CheckoutConfig, CheckoutCustomer, CheckoutRequestPayload } from '../types/api'

const EMPTY_CUSTOMER: CheckoutCustomer = { email: '', firstName: '', lastName: '', phone: '' }
const EMPTY_ADDRESS: CheckoutAddress = { addressLine1: '', addressLine2: '', city: '', state: '', postalCode: '', country: 'CL' }

interface CheckoutState {
  config: CheckoutConfig | null
  customer: CheckoutCustomer
  shippingAddress: CheckoutAddress
  paymentMethod: 'manual' | 'stripe'
  customerNotes: string
  loading: boolean
  error: string | null
  fetchConfig: () => Promise<void>
  setCustomer: (customer: Partial<CheckoutCustomer>) => void
  setShippingAddress: (address: Partial<CheckoutAddress>) => void
  setPaymentMethod: (method: 'manual' | 'stripe') => void
  setCustomerNotes: (notes: string) => void
  processCheckout: () => Promise<{ success: boolean; redirectUrl?: string; orderNumber?: string }>
  reset: () => void
}

export const useCheckoutStore = create<CheckoutState>((set, get) => ({
  config: null,
  customer: EMPTY_CUSTOMER,
  shippingAddress: EMPTY_ADDRESS,
  paymentMethod: 'manual',
  customerNotes: '',
  loading: false,
  error: null,

  async fetchConfig() {
    try {
      const config = await api.checkout.getConfig()
      set({ config, paymentMethod: config.stripeEnabled ? 'stripe' : 'manual' })
    } catch (error) {
      console.error('Error loading checkout config', error)
    }
  },

  setCustomer: (customer) => set((state) => ({ customer: { ...state.customer, ...customer } })),
  setShippingAddress: (address) => set((state) => ({ shippingAddress: { ...state.shippingAddress, ...address } })),
  setPaymentMethod: (paymentMethod) => set({ paymentMethod }),
  setCustomerNotes: (customerNotes) => set({ customerNotes }),

  async processCheckout() {
    const { customer, shippingAddress, paymentMethod, customerNotes } = get()
    const cartState = useCartStore.getState()

    if (cartState.items.length === 0) {
      return { success: false }
    }

    set({ loading: true, error: null })

    try {
      const payload: CheckoutRequestPayload = {
        customer,
        shippingAddress,
        items: cartState.items.map((item) => ({ id: item.id, type: item.type, quantity: item.quantity })),
        paymentMethod,
        customerNotes: customerNotes || undefined,
      }

      const confirmation = await api.checkout.createOrder(payload)

      if (paymentMethod === 'stripe') {
        const successUrl = `${window.location.origin}/order-success?session_id={CHECKOUT_SESSION_ID}`
        const cancelUrl = `${window.location.origin}/checkout?canceled=1`
        const session = await api.payment.createCheckoutSession(confirmation.orderId, successUrl, cancelUrl)
        set({ loading: false })
        return { success: true, redirectUrl: session.url }
      }

      cartState.clearCart()
      set({ loading: false })
      return { success: true, orderNumber: confirmation.orderNumber }
    } catch (error) {
      set({ loading: false, error: (error as { message?: string }).message ?? 'Error procesando el pedido' })
      return { success: false }
    }
  },

  reset: () =>
    set({
      customer: EMPTY_CUSTOMER,
      shippingAddress: EMPTY_ADDRESS,
      paymentMethod: 'manual',
      customerNotes: '',
      error: null,
    }),
}))
