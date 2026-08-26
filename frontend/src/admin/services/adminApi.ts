import axios from 'axios'
import type { Page } from '@/types/api'
import { useAdminAuthStore } from '@/admin/stores/useAdminAuthStore'
import type {
  AdminLoginResponse,
  AdminOrderDetail,
  AdminOrderStats,
  AdminOrderSummary,
  AdminProductStats,
  CategoryDto,
  GeneralSettingsDto,
  HomeBannerDto,
  PaymentGateway,
  PaymentGatewayConfigDto,
  ProductDto,
  StoreSettingsDto,
} from '@/admin/types/admin'

const client = axios.create({
  baseURL: import.meta.env.VITE_ADMIN_API_URL,
  headers: { Accept: 'application/json' },
})

client.interceptors.request.use((config) => {
  const token = useAdminAuthStore.getState().token
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

client.interceptors.response.use(
  (response) => response.data,
  (error) => {
    if (error.response?.status === 401) {
      useAdminAuthStore.getState().logout()
    }
    const data = error.response?.data
    return Promise.reject({ message: data?.message ?? error.message, ...data })
  },
)

export interface ProductListParams {
  search?: string
  type?: string
  categoryId?: number
  active?: boolean
  featured?: boolean
  stockStatus?: string
  priceRange?: string
  sortField?: string
  sortDirection?: 'asc' | 'desc'
  perPage?: number
  page?: number
}

export interface OrderListParams {
  tab?: string
  search?: string
  paymentStatus?: string
  sortField?: string
  sortDirection?: 'asc' | 'desc'
  page?: number
}

export const adminApi = {
  auth: {
    login: (email: string, password: string): Promise<AdminLoginResponse> =>
      client.post('/auth/login', { email, password }),
  },
  products: {
    list: (params: ProductListParams): Promise<Page<ProductDto>> => client.get('/products', { params }),
    stats: (): Promise<AdminProductStats> => client.get('/products/stats'),
    getById: (id: number): Promise<ProductDto> => client.get(`/products/${id}`),
    create: (formData: FormData): Promise<ProductDto> =>
      client.post('/products', formData, { headers: { 'Content-Type': 'multipart/form-data' } }),
    update: (id: number, formData: FormData): Promise<ProductDto> =>
      client.put(`/products/${id}`, formData, { headers: { 'Content-Type': 'multipart/form-data' } }),
    delete: (id: number): Promise<void> => client.delete(`/products/${id}`),
    toggleActive: (id: number): Promise<ProductDto> => client.post(`/products/${id}/toggle-active`),
    toggleFeatured: (id: number): Promise<ProductDto> => client.post(`/products/${id}/toggle-featured`),
  },
  categories: {
    list: (): Promise<CategoryDto[]> => client.get('/categories'),
    getById: (id: number): Promise<CategoryDto> => client.get(`/categories/${id}`),
    create: (formData: FormData): Promise<CategoryDto> =>
      client.post('/categories', formData, { headers: { 'Content-Type': 'multipart/form-data' } }),
    update: (id: number, formData: FormData): Promise<CategoryDto> =>
      client.put(`/categories/${id}`, formData, { headers: { 'Content-Type': 'multipart/form-data' } }),
    delete: (id: number): Promise<void> => client.delete(`/categories/${id}`),
    removeImage: (id: number): Promise<CategoryDto> => client.delete(`/categories/${id}/image`),
  },
  orders: {
    list: (params: OrderListParams): Promise<Page<AdminOrderSummary>> => client.get('/orders', { params }),
    stats: (): Promise<AdminOrderStats> => client.get('/orders/stats'),
    getById: (id: number): Promise<AdminOrderDetail> => client.get(`/orders/${id}`),
    markProcessing: (id: number): Promise<AdminOrderDetail> => client.post(`/orders/${id}/mark-processing`),
    markShipped: (id: number): Promise<AdminOrderDetail> => client.post(`/orders/${id}/mark-shipped`),
    markDelivered: (id: number): Promise<AdminOrderDetail> => client.post(`/orders/${id}/mark-delivered`),
    cancel: (id: number): Promise<AdminOrderDetail> => client.post(`/orders/${id}/cancel`),
    updateStatus: (id: number, status: string): Promise<AdminOrderDetail> =>
      client.put(`/orders/${id}/status`, { status }),
    updatePaymentStatus: (id: number, status: string): Promise<AdminOrderDetail> =>
      client.put(`/orders/${id}/payment-status`, { status }),
    saveTracking: (id: number, trackingNumber: string): Promise<AdminOrderDetail> =>
      client.post(`/orders/${id}/tracking`, { trackingNumber }),
  },
  paymentGateways: {
    list: (): Promise<PaymentGatewayConfigDto[]> => client.get('/payment-gateways'),
    update: (gateway: PaymentGateway, enabled: boolean, credentials: Record<string, string>): Promise<PaymentGatewayConfigDto> =>
      client.put(`/payment-gateways/${gateway}`, { enabled, credentials }),
  },
  banners: {
    list: (search?: string): Promise<HomeBannerDto[]> => client.get('/home/banners', { params: { search } }),
    getById: (id: number): Promise<HomeBannerDto> => client.get(`/home/banners/${id}`),
    create: (formData: FormData): Promise<HomeBannerDto> =>
      client.post('/home/banners', formData, { headers: { 'Content-Type': 'multipart/form-data' } }),
    update: (id: number, formData: FormData): Promise<HomeBannerDto> =>
      client.put(`/home/banners/${id}`, formData, { headers: { 'Content-Type': 'multipart/form-data' } }),
    delete: (id: number): Promise<void> => client.delete(`/home/banners/${id}`),
    toggle: (id: number): Promise<HomeBannerDto> => client.post(`/home/banners/${id}/toggle`),
    reorder: (orderedIds: number[]): Promise<void> => client.post('/home/banners/reorder', { orderedIds }),
  },
  generalSettings: {
    get: (): Promise<GeneralSettingsDto> => client.get('/settings/general'),
    update: (settings: GeneralSettingsDto): Promise<GeneralSettingsDto> => client.put('/settings/general', settings),
  },
  storeSettings: {
    get: (): Promise<StoreSettingsDto> => client.get('/settings/store'),
    update: (formData: FormData): Promise<StoreSettingsDto> =>
      client.put('/settings/store', formData, { headers: { 'Content-Type': 'multipart/form-data' } }),
  },
}

export default adminApi
