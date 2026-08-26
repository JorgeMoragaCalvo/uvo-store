export interface AdminLoginResponse {
  token: string
  id: number
  name: string
  email: string
  type: string
}

export interface CategoryRef {
  id: number
  name: string
  slug: string
}

export interface CategoryDto {
  id: number
  name: string
  slug: string
  description: string | null
  image: string | null
  parentId: number | null
  active: boolean
  sortOrder: number
  parent: CategoryRef | null
  children: CategoryRef[]
  productsCount: number | null
}

export interface ProductImageDto {
  id: number
  url: string
  thumbnail: string
  alt: string | null
  isFeatured: boolean
}

export interface ProductVariationDto {
  id: number
  productId: number
  sku: string
  price: number
  compareAtPrice: number | null
  formattedPrice: string
  stock: number
  inStock: boolean
  weight: number | null
  image: string | null
  active: boolean
  attributes: Record<string, string>
  attributeIds: Record<string, number>
  createdAt: string
}

export interface ProductDto {
  id: number
  name: string
  slug: string
  shortDescription: string | null
  description: string | null
  productType: 'simple' | 'variable'
  sku: string | null
  price: number | null
  formattedPrice: string
  stock: number
  inStock: boolean
  manageStock: boolean
  featuredImage: string | null
  images: ProductImageDto[]
  active: boolean
  featured: boolean
  metaTitle: string | null
  metaDescription: string | null
  category: CategoryRef | null
  variations: ProductVariationDto[]
  variationsCount: number
  createdAt: string
  updatedAt: string
}

export interface AdminProductStats {
  total: number
  active: number
  outOfStock: number
  lowStock: number
}

export interface AddressDto {
  firstName: string | null
  lastName: string | null
  company: string | null
  addressLine1: string | null
  addressLine2: string | null
  city: string | null
  state: string | null
  postalCode: string | null
  country: string | null
  phone: string | null
}

export interface AdminOrderSummary {
  id: number
  orderNumber: string
  customerEmail: string
  customerFirstName: string
  customerLastName: string
  status: string
  paymentStatus: string
  fulfillmentStatus: string
  total: number
  itemsCount: number
  createdAt: string
}

export interface AdminOrderItem {
  id: number
  productId: number | null
  productName: string
  productSku: string | null
  variationId: number | null
  quantity: number
  price: number
  subtotal: number
}

export interface AdminOrderDetail {
  id: number
  orderNumber: string
  customerId: number | null
  customerEmail: string
  customerFirstName: string
  customerLastName: string
  customerPhone: string | null
  status: string
  paymentStatus: string
  fulfillmentStatus: string
  subtotal: number
  discountAmount: number
  shippingCost: number
  taxAmount: number
  total: number
  paymentMethod: string | null
  trackingNumber: string | null
  trackingUrl: string | null
  shippingAddress: AddressDto | null
  billingAddress: AddressDto | null
  shippingRegion: string | null
  shippingCommune: string | null
  shippingPostalCode: string | null
  customerNotes: string | null
  notes: string | null
  items: AdminOrderItem[]
  createdAt: string
  shippedAt: string | null
  deliveredAt: string | null
}

export interface AdminOrderStats {
  all: number
  pending: number
  paid: number
  processing: number
  shipped: number
  cancelled: number
}

export type PaymentGateway = 'STRIPE' | 'WEBPAY' | 'MERCADOPAGO'

export interface PaymentGatewayConfigDto {
  id: number
  gateway: PaymentGateway
  enabled: boolean
  credentialsSet: Record<string, boolean>
}
