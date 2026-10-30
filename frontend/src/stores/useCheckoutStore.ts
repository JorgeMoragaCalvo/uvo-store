import { create } from 'zustand'
import api from '../services/api'
import { friendlyError } from '../services/errors'
import type { ApiFailure } from '../services/errors'
import { useCartStore } from './useCartStore'
import type { CheckoutAddress, CheckoutConfig, CheckoutCustomer, CheckoutRequestPayload } from '../types/api'

const EMPTY_CUSTOMER: CheckoutCustomer = { email: '', firstName: '', lastName: '', phone: '' }
const EMPTY_ADDRESS: CheckoutAddress = { addressLine1: '', addressLine2: '', city: '', state: '', postalCode: '', country: 'CL' }

export type PaymentMethod = 'manual' | 'stripe' | 'webpay' | 'mercadopago'

type GatewayMethod = Exclude<PaymentMethod, 'manual'>

type PaymentStart = { redirectUrl: string } | { webpayForm: { url: string; token: string } }

// PROD-24b. La orden que se creó antes de salir a una pasarela, con lo que el comprador escribió.
//
// Ir a Stripe, Webpay o MercadoPago es una navegación de página completa: al volver sin pagar
// (`/checkout?canceled=1`, `?error=…`) este store nace vacío y el comprador tenía que escribir todo de
// nuevo. Y al confirmar otra vez se creaba OTRA orden pendiente, que retiene stock y cupón hasta que
// AbandonedOrderJob la cancela (24 h): con poco stock, el reintento podía fallar por la reserva de su
// propio intento anterior. Con esto el reintento paga la misma orden, que el backend acepta mientras su
// pago siga en PENDING.
//
// En sessionStorage y no en localStorage: es de esta pestaña y de esta compra, y no debe sobrevivir a
// cerrar el navegador.
const PENDING_PAYMENT_KEY = 'uvostore_pending_payment'

export interface PendingPayment {
  orderId: number
  orderNumber: string
  paymentMethod: GatewayMethod
  // Lo que define la orden: si cambió, la orden guardada ya no es la del carrito y no se reutiliza.
  cartSignature: string
  region: string
  commune: string
  couponCode: string
  customer: CheckoutCustomer
  shippingAddress: CheckoutAddress
  customerNotes: string
}

function cartSignature() {
  const { items, region, commune, couponCode } = useCartStore.getState()
  return JSON.stringify({
    items: (items ?? []).map((item) => [item.type, item.id, item.quantity]),
    region: region ?? '',
    commune: commune ?? '',
    couponCode: couponCode ?? '',
  })
}

// Cada acceso va en try/catch: con el almacenamiento bloqueado (modo privado, política del navegador)
// la compra tiene que seguir funcionando igual, solo sin poder retomarse.
export function loadPendingPayment(): PendingPayment | null {
  try {
    const raw = sessionStorage.getItem(PENDING_PAYMENT_KEY)
    return raw ? (JSON.parse(raw) as PendingPayment) : null
  } catch {
    return null
  }
}

function savePendingPayment(pending: PendingPayment) {
  try {
    sessionStorage.setItem(PENDING_PAYMENT_KEY, JSON.stringify(pending))
  } catch {
    // Sin almacenamiento, el reintento crea una orden nueva, como antes.
  }
}

export function clearPendingPayment() {
  try {
    sessionStorage.removeItem(PENDING_PAYMENT_KEY)
  } catch {
    // Nada que limpiar.
  }
}

// PROD-04: las tres pasarelas van sin URLs de retorno. Las resuelve el backend desde la tienda de la
// orden (StorePublicUrlResolver), que es el único que sabe cuál es su dominio público verificado y cuál
// es el origen de la API — y no el navegador, cuyo window.location.origin era además una entrada sin
// validar que llegaba tal cual a Stripe y a MercadoPago.
async function startPayment(method: GatewayMethod, orderId: number): Promise<PaymentStart> {
  if (method === 'stripe') {
    const session = await api.payment.createCheckoutSession(orderId)
    return { redirectUrl: session.url }
  }
  if (method === 'webpay') {
    // Transbank tiene que volver a la API que puede confirmar la transacción en el servidor, no al
    // origen del frontend, que en la mayoría de los despliegues es otro host (como aquí en desarrollo).
    // Webpay Plus needs an actual form POST (token_ws field), not a GET redirect — see Checkout.tsx,
    // which builds and submits the hidden form using this payload.
    const result = await api.webpay.create(orderId)
    return { webpayForm: { url: result.url, token: result.token } }
  }
  const preference = await api.mercadopago.createPreference(orderId)
  return { redirectUrl: preference.initPoint }
}

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
  resumePendingPayment: () => Promise<PendingPayment | null>
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
      // Si se retomó un pago, se respeta el medio que el comprador había elegido.
      const resumed = loadPendingPayment()
      set({ config, paymentMethod: resumed?.paymentMethod ?? (config.stripeEnabled ? 'stripe' : 'manual') })
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
      const signature = cartSignature()

      if (paymentMethod !== 'manual') {
        // Reintento tras volver de la pasarela sin pagar: misma orden, mismo medio. Con otro medio se crea
        // una orden nueva, porque la guardada ya registra el que se eligió (reportes, conciliación).
        const pending = loadPendingPayment()
        if (pending && pending.paymentMethod === paymentMethod && pending.cartSignature === signature) {
          try {
            // `loading` sigue en true: la página se va a la pasarela, y si volviera a false el botón
            // quedaría activo durante la navegación y un segundo clic crearía otra orden.
            return { success: true, ...(await startPayment(paymentMethod, pending.orderId)) }
          } catch (error) {
            // 400 = la orden ya no está pendiente (por ejemplo, Webpay la marcó fallida al rechazar el
            // pago). Se sigue con una orden nueva; cualquier otro error se informa.
            if ((error as ApiFailure).status !== 400) throw error
            clearPendingPayment()
          }
        }
      }

      const payload: CheckoutRequestPayload = {
        customer,
        shippingAddress,
        // A7: region/commune/couponCode were declared on this type from the start but never
        // populated, so the backend priced every order with no destination — no shipping zone could
        // match and shipping was free on all of them. They live in the cart store, which is what
        // calls /cart/calculate, so the totals the customer saw and the ones the order is built
        // from come from the same values.
        region: cartState.region || undefined,
        commune: cartState.commune || undefined,
        couponCode: cartState.couponCode || undefined,
        items: cartState.items.map((item) => ({ id: item.id, type: item.type, quantity: item.quantity })),
        paymentMethod,
        customerNotes: customerNotes || undefined,
      }

      const confirmation = await api.checkout.createOrder(payload)

      if (paymentMethod === 'manual') {
        clearPendingPayment()
        cartState.clearCart()
        set({ loading: false })
        return { success: true, orderNumber: confirmation.orderNumber }
      }

      // Se guarda antes de pedir la sesión de pago: si esa llamada falla, el reintento usa esta orden.
      savePendingPayment({
        orderId: confirmation.orderId,
        orderNumber: confirmation.orderNumber,
        paymentMethod,
        cartSignature: signature,
        region: cartState.region ?? '',
        commune: cartState.commune ?? '',
        couponCode: cartState.couponCode ?? '',
        customer,
        shippingAddress,
        customerNotes,
      })
      return { success: true, ...(await startPayment(paymentMethod, confirmation.orderId)) }
    } catch (error) {
      // C5: el 409 de stock trae el problema de cada línea; se pinta en la línea del carrito.
      const failure = error as ApiFailure
      if (failure.status === 409 && failure.fieldErrors) {
        useCartStore.getState().applyStockErrors(failure.fieldErrors)
      }
      set({ loading: false, error: friendlyError(error, 'No pudimos procesar tu pedido. Inténtalo de nuevo.') })
      return { success: false }
    }
  },

  // Al volver de la pasarela sin pagar: deja el checkout como estaba al salir. Devuelve lo guardado para
  // que la página rellene sus formularios, o null si no hay nada que retomar.
  async resumePendingPayment() {
    const pending = loadPendingPayment()
    if (!pending) return null
    set({
      customer: pending.customer,
      shippingAddress: pending.shippingAddress,
      customerNotes: pending.customerNotes,
      paymentMethod: pending.paymentMethod,
      loading: false,
    })
    useCartStore.setState({ region: pending.region, commune: pending.commune, couponCode: pending.couponCode })
    await useCartStore.getState().calculateTotals()
    return pending
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
