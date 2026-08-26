import { create } from 'zustand'
import api from '../services/api'
import { useCartStore } from './useCartStore'
import type { CheckoutAddress, CheckoutConfig, CheckoutCustomer, CheckoutRequestPayload } from '../types/api'

const EMPTY_CUSTOMER: CheckoutCustomer = { email: '', firstName: '', lastName: '', phone: '' }
const EMPTY_ADDRESS: CheckoutAddress = { addressLine1: '', addressLine2: '', city: '', state: '', postalCode: '', country: 'CL' }

export type PaymentMethod = 'manual' | 'stripe' | 'webpay' | 'mercadopago'

interface CheckoutState {
  config: CheckoutConfig | null
  customer: CheckoutCustomer
  shippingAddress: CheckoutAddress
  paymentMethod: PaymentMethod
  customerNotes: string
  loading: boolean
  error: string | null
  fetchConfig: () => Promise<void>
  setCustomer: (customer: Partial<CheckoutCustomer>) => void
  setShippingAddress: (address: Partial<CheckoutAddress>) => void
  setPaymentMethod: (method: PaymentMethod) => void
  setCustomerNotes: (notes: string) => void
  processCheckout: () => Promise<{
    success: boolean
    redirectUrl?: string
    webpayForm?: { url: string; token: string }
    orderNumber?: string
  }>
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

      if (paymentMethod === 'webpay') {
        // No returnUrl override: the backend defaults it to its OWN host's /api/v1/webpay/return
        // (WebpayController#defaultReturnUrl) — Transbank must redirect back to the API that can
        // commit the transaction server-side, not to the frontend's origin, which is a different
        // host in most deployments (as it already is here in dev).
        const result = await api.webpay.create(confirmation.orderId)
        set({ loading: false })
        // Webpay Plus needs an actual form POST (token_ws field), not a GET redirect — see
        // Checkout.tsx, which builds and submits the hidden form using this payload.
        return { success: true, webpayForm: { url: result.url, token: result.token } }
      }

      if (paymentMethod === 'mercadopago') {
        const successUrl = `${window.location.origin}/order-success?order=${confirmation.orderNumber}`
        const failureUrl = `${window.location.origin}/checkout?error=mercadopago`
        const pendingUrl = `${window.location.origin}/checkout?pending=1`
        const preference = await api.mercadopago.createPreference(confirmation.orderId, successUrl, failureUrl, pendingUrl)
        set({ loading: false })
        return { success: true, redirectUrl: preference.initPoint }
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
