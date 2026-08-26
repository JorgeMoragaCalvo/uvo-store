// Mirrors the Spring Boot DTOs (Jackson serializes records field-by-field, same camelCase names) —
// see C:\Users\jorgemc\IdeaProjects\uvo-store\src\main\java\org\uvo\uvostore\service\**\*Dto.java

export interface CategoryRef {
  id: number
  name: string
  slug: string
}

export interface ProductImage {
  id: number
  url: string
  thumbnail: string | null
  alt: string | null
  isFeatured: boolean
}

export interface ProductVariation {
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

export interface Product {
  id: number
  name: string
  slug: string
  shortDescription: string | null
  description: string | null
  productType: 'simple' | 'variable' // backend serializes Product.productType.name().toLowerCase()
  sku: string
  price: number
  formattedPrice: string
  stock: number
  inStock: boolean
  manageStock: boolean
  featuredImage: string | null
  images: ProductImage[]
  active: boolean
  featured: boolean
  metaTitle: string | null
  metaDescription: string | null
  category: CategoryRef | null
  variations: ProductVariation[]
  variationsCount: number | null
  createdAt: string
  updatedAt: string
}

export interface Page<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export interface Category {
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

export interface AttributeValue {
  id: number
  attributeId: number
  value: string
  slug: string
  colorHex: string | null
  sortOrder: number
}

export interface Attribute {
  id: number
  name: string
  slug: string
  type: string
  values: AttributeValue[]
}

export interface ProductSearchParams {
  search?: string
  category?: string
  type?: 'SIMPLE' | 'VARIABLE'
  featured?: boolean
  in_stock?: boolean
  is_new?: boolean
  on_sale?: boolean
  min_price?: number
  max_price?: number
  sort_by?: 'name' | 'price' | 'createdAt' | 'stock'
  sort_order?: 'asc' | 'desc'
  per_page?: number
  page?: number
}

export interface CartItemInput {
  id: number
  type: 'product' | 'variation'
  quantity: number
}

export interface CartCalculationResult {
  subtotalWithoutTax: number
  subtotalWithTax: number
  shippingCost: number
  taxAmount: number
  discountAmount: number
  total: number
  pricesIncludeTax: boolean
  taxRate: number
  freeShippingThreshold: number | null
  shippingEnabled: boolean
}

export interface CartValidatedItem {
  id: number
  type: string
  name: string
  variation: Record<string, string> | null
  price: number
  stock: number
  image: string | null
  maxQuantity: number
}

export interface CartValidationResult {
  valid: boolean
  items: CartValidatedItem[]
  errors: Record<string, string>
}

export interface CheckoutCustomer {
  email: string
  firstName: string
  lastName: string
  phone: string
}

export interface CheckoutAddress {
  addressLine1: string
  addressLine2?: string
  city: string
  state: string
  postalCode: string
  country: string
}

export interface CheckoutRequestPayload {
  customer: CheckoutCustomer
  shippingAddress: CheckoutAddress
  region?: string
  commune?: string
  items: CartItemInput[]
  couponCode?: string
  paymentMethod: 'manual' | 'stripe' | 'webpay' | 'mercadopago'
  customerNotes?: string
}

export interface OrderConfirmation {
  orderId: number
  orderNumber: string
  total: number
}

export interface CheckoutConfig {
  stripePublicKey: string | null
  stripeEnabled: boolean
  webpayEnabled: boolean
  mercadopagoEnabled: boolean
  shippingEnabled: boolean
  defaultShippingCost: number
  freeShippingEnabled: boolean
  freeShippingThreshold: number | null
  allowGuestCheckout: boolean
  requirePhone: boolean
  taxRate: number
  currency: string
  currencySymbol: string
}

export interface CheckoutSessionResult {
  sessionId: string
  url: string
}

export interface WebpayCreateResult {
  token: string
  url: string
}

export interface MercadoPagoPreferenceResult {
  preferenceId: string
  initPoint: string
}

export interface PaymentVerificationResult {
  sessionPaymentStatus: string
  orderId: number
  orderNumber: string
  orderPaymentStatus: string
}

export interface PublicStoreSettings {
  storeName: string
  storeDescription: string | null
  storeLogo: string | null
  storeFavicon: string | null
  primaryColor: string
  secondaryColor: string
  accentColor: string
  darkColor: string
  showHero: boolean
  heroAutoplaySpeed: number
  showCategories: boolean
  categoriesTitle: string
  categoriesLimit: number
  showNewProducts: boolean
  newProductsTitle: string
  newProductsLimit: number
  newProductsDays: number
  showFeaturedProducts: boolean
  featuredProductsTitle: string
  featuredProductsLimit: number
  showDeals: boolean
  dealsTitle: string
  dealsLimit: number
  showBenefits: boolean
  benefit1Icon: string | null
  benefit1Title: string | null
  benefit1Description: string | null
  benefit2Icon: string | null
  benefit2Title: string | null
  benefit2Description: string | null
  benefit3Icon: string | null
  benefit3Title: string | null
  benefit3Description: string | null
  benefit4Icon: string | null
  benefit4Title: string | null
  benefit4Description: string | null
  contactEmail: string | null
  contactPhone: string | null
  whatsappNumber: string | null
  facebookUrl: string | null
  instagramUrl: string | null
  twitterUrl: string | null
  tiktokUrl: string | null
  metaTitle: string | null
  metaDescription: string | null
  metaKeywords: string | null
  currency: string
  currencySymbol: string
  taxRate: string
  pricesIncludeTax: boolean
  shippingEnabled: boolean
  defaultShippingCost: string
  freeShippingEnabled: boolean
  freeShippingThreshold: string
}

export interface HomeBanner {
  id: number
  title: string
  subtitle: string | null
  description: string | null
  image: string
  mobileImage: string | null
  ctaText: string | null
  ctaLink: string | null
  ctaNewTab: boolean
  ctaSecondaryText: string | null
  ctaSecondaryLink: string | null
  textPosition: 'left' | 'center' | 'right'
  textColor: 'light' | 'dark'
  overlayColor: string | null
  overlayOpacity: number
  active: boolean
  sortOrder: number
}

export interface OrderTracking {
  orderNumber: string
  status: string
  paymentStatus: string
  fulfillmentStatus: string
  trackingNumber: string | null
  trackingUrl: string | null
  total: number
  itemsCount: number
  createdAt: string
  shippedAt: string | null
  deliveredAt: string | null
}
