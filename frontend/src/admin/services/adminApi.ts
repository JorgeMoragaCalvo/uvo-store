import axios from 'axios'
import type { Page } from '@/types/api'
import { useAdminAuthStore } from '@/admin/stores/useAdminAuthStore'
import type {
  AdminCustomerDetailDto,
  AdminCustomerStatsDto,
  AdminCustomerSummaryDto,
  AdminLoginResponse,
  AdminOrderDetail,
  AdminOrderStats,
  AdminOrderSummary,
  AdminProductStats,
  AdminUserDto,
  CategoryDto,
  CategoryRevenueDto,
  CouponDto,
  CouponRequest,
  GeneralSettingsDto,
  GeneralSettingsUpdateRequest,
  HomeBannerDto,
  PaymentGateway,
  PaymentGatewayConfigDto,
  PaymentMethodDetailDto,
  PaymentMethodRevenueDto,
  PaymentMethodsSummaryDto,
  PaymentStatusDistributionDto,
  PermissionDto,
  ProductDto,
  ProductReportRowDto,
  ProductsSummaryDto,
  RoleDto,
  RoleRequest,
  SalesByDayDto,
  SalesSummaryDto,
  ShippingAddressDto,
  ShippingMethodDto,
  ShippingMethodRequest,
  ShippingRateDto,
  ShippingRateRequest,
  ShippingZoneDto,
  ShippingZoneRequest,
  StoreSettingsDto,
  TopProductDto,
} from '@/admin/types/admin'

const client = axios.create({
  baseURL: `${window.location.origin}/api/admin`,
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

export interface ReportDateRangeParams {
  startDate?: string
  endDate?: string
}

export interface ProductsReportListParams extends ReportDateRangeParams {
  categoryId?: number
  search?: string
  sortBy?: string
  sortDirection?: 'asc' | 'desc'
  page?: number
}

export interface ShippingRateListParams {
  methodId?: number
  zoneId?: number
}

export interface UserListParams {
  search?: string
  roleId?: number
  active?: boolean
  sortField?: string
  sortDirection?: 'asc' | 'desc'
  page?: number
}

export interface CustomerListParams {
  search?: string
  sortField?: string
  sortDirection?: 'asc' | 'desc'
  page?: number
}

export interface CouponListParams {
  search?: string
  status?: string
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
    forgotPassword: (email: string): Promise<void> => client.post('/auth/forgot-password', { email }),
    resetPassword: (token: string, password: string): Promise<void> =>
      client.post('/auth/reset-password', { token, password }),
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
    // Per-image, unlike categories.removeImage — products have a gallery. Returns the refreshed
    // product so the caller sees which image was promoted to featured.
    removeImage: (id: number, imageId: number): Promise<ProductDto> =>
      client.delete(`/products/${id}/images/${imageId}`),
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
  reports: {
    sales: {
      summary: (params: ReportDateRangeParams & { paymentStatus?: string }): Promise<SalesSummaryDto> =>
        client.get('/reports/sales/summary', { params }),
      byDay: (params: ReportDateRangeParams): Promise<SalesByDayDto[]> => client.get('/reports/sales/by-day', { params }),
      topProducts: (params: ReportDateRangeParams): Promise<TopProductDto[]> => client.get('/reports/sales/top-products', { params }),
      byPaymentMethod: (params: ReportDateRangeParams): Promise<PaymentMethodRevenueDto[]> =>
        client.get('/reports/sales/by-payment-method', { params }),
    },
    products: {
      summary: (params: ReportDateRangeParams & { categoryId?: number; search?: string }): Promise<ProductsSummaryDto> =>
        client.get('/reports/products/summary', { params }),
      list: (params: ProductsReportListParams): Promise<Page<ProductReportRowDto>> => client.get('/reports/products', { params }),
      topByRevenue: (params: ReportDateRangeParams): Promise<ProductReportRowDto[]> =>
        client.get('/reports/products/top-by-revenue', { params }),
      topByQuantity: (params: ReportDateRangeParams): Promise<ProductReportRowDto[]> =>
        client.get('/reports/products/top-by-quantity', { params }),
      byCategory: (params: ReportDateRangeParams): Promise<CategoryRevenueDto[]> => client.get('/reports/products/by-category', { params }),
    },
    paymentMethods: {
      summary: (params: ReportDateRangeParams & { paymentStatus?: string }): Promise<PaymentMethodsSummaryDto> =>
        client.get('/reports/payment-methods/summary', { params }),
      list: (params: ReportDateRangeParams): Promise<PaymentMethodDetailDto[]> => client.get('/reports/payment-methods', { params }),
      statusDistribution: (params: ReportDateRangeParams): Promise<PaymentStatusDistributionDto[]> =>
        client.get('/reports/payment-methods/status-distribution', { params }),
    },
    exportCsv: (path: string, params: object): Promise<Blob> => client.get(path, { params, responseType: 'blob' }),
  },
  shippingZones: {
    list: (search?: string): Promise<ShippingZoneDto[]> => client.get('/shipping/zones', { params: { search } }),
    getById: (id: number): Promise<ShippingZoneDto> => client.get(`/shipping/zones/${id}`),
    create: (request: ShippingZoneRequest): Promise<ShippingZoneDto> => client.post('/shipping/zones', request),
    update: (id: number, request: ShippingZoneRequest): Promise<ShippingZoneDto> => client.put(`/shipping/zones/${id}`, request),
    delete: (id: number): Promise<void> => client.delete(`/shipping/zones/${id}`),
    toggleStatus: (id: number): Promise<ShippingZoneDto> => client.post(`/shipping/zones/${id}/toggle-status`),
  },
  shippingMethods: {
    list: (): Promise<ShippingMethodDto[]> => client.get('/shipping/methods'),
    getById: (id: number): Promise<ShippingMethodDto> => client.get(`/shipping/methods/${id}`),
    create: (request: ShippingMethodRequest): Promise<ShippingMethodDto> => client.post('/shipping/methods', request),
    update: (id: number, request: ShippingMethodRequest): Promise<ShippingMethodDto> =>
      client.put(`/shipping/methods/${id}`, request),
    delete: (id: number): Promise<void> => client.delete(`/shipping/methods/${id}`),
    toggleStatus: (id: number): Promise<ShippingMethodDto> => client.post(`/shipping/methods/${id}/toggle-status`),
    updateCredentials: (id: number, credentials: Record<string, string>): Promise<ShippingMethodDto> =>
      client.put(`/shipping/methods/${id}/credentials`, credentials),
  },
  shippingRates: {
    list: (params: ShippingRateListParams): Promise<ShippingRateDto[]> => client.get('/shipping/rates', { params }),
    getById: (id: number): Promise<ShippingRateDto> => client.get(`/shipping/rates/${id}`),
    create: (request: ShippingRateRequest): Promise<ShippingRateDto> => client.post('/shipping/rates', request),
    update: (id: number, request: ShippingRateRequest): Promise<ShippingRateDto> => client.put(`/shipping/rates/${id}`, request),
    delete: (id: number): Promise<void> => client.delete(`/shipping/rates/${id}`),
    toggleStatus: (id: number): Promise<ShippingRateDto> => client.post(`/shipping/rates/${id}/toggle-status`),
  },
  users: {
    list: (params: UserListParams): Promise<Page<AdminUserDto>> => client.get('/users', { params }),
    getById: (id: number): Promise<AdminUserDto> => client.get(`/users/${id}`),
    create: (formData: FormData): Promise<AdminUserDto> =>
      client.post('/users', formData, { headers: { 'Content-Type': 'multipart/form-data' } }),
    update: (id: number, formData: FormData): Promise<AdminUserDto> =>
      client.put(`/users/${id}`, formData, { headers: { 'Content-Type': 'multipart/form-data' } }),
    delete: (id: number): Promise<void> => client.delete(`/users/${id}`),
    toggleStatus: (id: number): Promise<AdminUserDto> => client.post(`/users/${id}/toggle-status`),
  },
  roles: {
    list: (): Promise<RoleDto[]> => client.get('/roles'),
    getById: (id: number): Promise<RoleDto> => client.get(`/roles/${id}`),
    permissions: (): Promise<Record<string, PermissionDto[]>> => client.get('/roles/permissions'),
    create: (request: RoleRequest): Promise<RoleDto> => client.post('/roles', request),
    update: (id: number, request: RoleRequest): Promise<RoleDto> => client.put(`/roles/${id}`, request),
    delete: (id: number): Promise<void> => client.delete(`/roles/${id}`),
  },
  customers: {
    list: (params: CustomerListParams): Promise<Page<AdminCustomerSummaryDto>> => client.get('/customers', { params }),
    stats: (): Promise<AdminCustomerStatsDto> => client.get('/customers/stats'),
    getById: (id: number): Promise<AdminCustomerDetailDto> => client.get(`/customers/${id}`),
    getOrders: (id: number, page: number): Promise<Page<AdminOrderSummary>> =>
      client.get(`/customers/${id}/orders`, { params: { page } }),
    delete: (id: number): Promise<void> => client.delete(`/customers/${id}`),
    deleteAddress: (customerId: number, addressId: number): Promise<void> =>
      client.delete(`/customers/${customerId}/addresses/${addressId}`),
    setDefaultAddress: (customerId: number, addressId: number): Promise<ShippingAddressDto> =>
      client.post(`/customers/${customerId}/addresses/${addressId}/default`),
  },
  coupons: {
    list: (params: CouponListParams): Promise<Page<CouponDto>> => client.get('/coupons', { params }),
    getById: (id: number): Promise<CouponDto> => client.get(`/coupons/${id}`),
    create: (request: CouponRequest): Promise<CouponDto> => client.post('/coupons', request),
    update: (id: number, request: CouponRequest): Promise<CouponDto> => client.put(`/coupons/${id}`, request),
    delete: (id: number): Promise<void> => client.delete(`/coupons/${id}`),
    toggleStatus: (id: number): Promise<CouponDto> => client.post(`/coupons/${id}/toggle-status`),
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
    update: (settings: GeneralSettingsUpdateRequest): Promise<GeneralSettingsDto> => client.put('/settings/general', settings),
  },
  storeSettings: {
    get: (): Promise<StoreSettingsDto> => client.get('/settings/store'),
    update: (formData: FormData): Promise<StoreSettingsDto> =>
      client.put('/settings/store', formData, { headers: { 'Content-Type': 'multipart/form-data' } }),
  },
}

export default adminApi
