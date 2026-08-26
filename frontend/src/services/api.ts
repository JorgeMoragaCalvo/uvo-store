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
  WebpayCreateResult,
} from '../types/api'

const client = axios.create({
  baseURL: import.meta.env.VITE_API_URL,
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
