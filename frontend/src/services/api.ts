import axios from 'axios'
import type {
  Attribute,
  CartCalculationResult,
  CartItemInput,
  CartValidationResult,
  Category,
  CheckoutConfig,
  CheckoutRequestPayload,
  CheckoutSessionResult,
  HomeBanner,
  MercadoPagoPreferenceResult,
  OrderConfirmation,
  OrderTracking,
  Page,
  PaymentVerificationResult,
  Product,
  ProductSearchParams,
  PublicStoreSettings,
  ShippingCoverage,
  WebpayCreateResult,
} from '../types/api'

// Resolved at RUNTIME from the page's own origin, not baked into the build — this SPA is one
// shared bundle serving every tenant (subdomain or custom domain), and the backend resolves the
// store from that same Host (see TenantResolutionFilter). In dev, Vite's own proxy (vite.config.ts)
// forwards /api/* to VITE_DEV_PROXY_TARGET so this still resolves to a real backend without a
// same-origin production reverse proxy.
const client = axios.create({
  baseURL: `${window.location.origin}/api/v1`,
  headers: {
    'Content-Type': 'application/json',
    Accept: 'application/json',
  },
})

client.interceptors.response.use(
  (response) => response.data,
  (error) => {
    const data = error.response?.data
    return Promise.reject({
      message: data?.message ?? error.message,
      ...data,
    })
  },
)

export const api = {
  products: {
    getAll: (params?: ProductSearchParams): Promise<Page<Product>> => client.get('/products', { params }),
    getFeatured: (): Promise<Product[]> => client.get('/products/featured'),
    getBySlug: (slug: string): Promise<Product> => client.get(`/products/${slug}`),
    getRelated: (slug: string): Promise<Product[]> => client.get(`/products/${slug}/related`),
  },
  categories: {
    getAll: (): Promise<Category[]> => client.get('/categories'),
    getBySlug: (slug: string): Promise<Category> => client.get(`/categories/${slug}`),
  },
  attributes: {
    getAll: (): Promise<Attribute[]> => client.get('/attributes'),
  },
  cart: {
    validate: (items: CartItemInput[]): Promise<CartValidationResult> => client.post('/cart/validate', { items }),
    calculate: (
      items: CartItemInput[],
      region?: string,
      commune?: string,
      couponCode?: string,
    ): Promise<CartCalculationResult> => client.post('/cart/calculate', { items, region, commune, couponCode }),
  },
  shipping: {
    // A7: the regions/communes the store actually delivers to. Zone matching is an exact string
    // compare against free text an admin typed, so the checkout has to offer these verbatim.
    coverage: (): Promise<ShippingCoverage[]> => client.get('/shipping/coverage'),
  },
  checkout: {
    getConfig: (): Promise<CheckoutConfig> => client.get('/checkout/config'),
    createOrder: (payload: CheckoutRequestPayload): Promise<OrderConfirmation> => client.post('/checkout', payload),
  },
  payment: {
    createCheckoutSession: (
      orderId: number,
      successUrl: string,
      cancelUrl: string,
    ): Promise<CheckoutSessionResult> => client.post('/create-checkout-session', { orderId, successUrl, cancelUrl }),
    verify: (sessionId: string): Promise<PaymentVerificationResult> => client.post('/verify-payment', { sessionId }),
  },
  webpay: {
    create: (orderId: number, returnUrl?: string): Promise<WebpayCreateResult> =>
      client.post('/webpay/create', { orderId, returnUrl }),
  },
  mercadopago: {
    createPreference: (
      orderId: number,
      successUrl?: string,
      failureUrl?: string,
      pendingUrl?: string,
    ): Promise<MercadoPagoPreferenceResult> =>
      client.post('/mercadopago/create-preference', { orderId, successUrl, failureUrl, pendingUrl }),
  },
  storeSettings: {
    get: (): Promise<PublicStoreSettings> => client.get('/store-settings'),
  },
  homeBanners: {
    getAll: (): Promise<HomeBanner[]> => client.get('/home-banners'),
  },
  orders: {
    track: (orderNumber: string): Promise<OrderTracking> => client.get('/orders/track', { params: { order_number: orderNumber } }),
  },
}

export default api
