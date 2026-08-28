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

export type CustomerAccountStatus = 'GUEST' | 'INVITED' | 'ACTIVE'

export interface AdminCustomerSummaryDto {
  id: number
  email: string
  firstName: string
  lastName: string
  phone: string | null
  accountStatus: CustomerAccountStatus
  ordersCount: number
  createdAt: string
}

export interface AdminCustomerStatsDto {
  totalCustomers: number
  withOrders: number
  newThisMonth: number
}

export interface ShippingAddressDto {
  id: number
  firstName: string
  lastName: string
  company: string | null
  addressLine1: string
  addressLine2: string | null
  city: string
  state: string | null
  postalCode: string | null
  country: string
  phone: string | null
  isDefault: boolean
}

export interface AdminCustomerOrderStatsDto {
  totalOrders: number
  totalSpent: number
  averageOrder: number
  completedOrders: number
}

export interface AdminCustomerDetailDto {
  id: number
  email: string
  firstName: string
  lastName: string
  phone: string | null
  accountStatus: CustomerAccountStatus
  addresses: ShippingAddressDto[]
  stats: AdminCustomerOrderStatsDto
  createdAt: string
}

export type CouponType = 'percentage' | 'fixed'

export interface CouponDto {
  id: number
  code: string
  name: string
  description: string | null
  type: CouponType
  value: number
  minimumPurchase: number | null
  maximumDiscount: number | null
  startsAt: string | null
  expiresAt: string | null
  usageLimit: number | null
  usageLimitPerCustomer: number | null
  timesUsed: number
  active: boolean
  createdAt: string
}

export interface CouponRequest {
  code: string
  name: string
  description: string | null
  type: CouponType
  value: number
  minimumPurchase: number | null
  maximumDiscount: number | null
  startsAt: string | null
  expiresAt: string | null
  usageLimit: number | null
  usageLimitPerCustomer: number | null
  active: boolean
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

export interface HomeBannerDto {
  id: number
  title: string | null
  subtitle: string | null
  description: string | null
  image: string | null
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

// Mirrors GeneralSettingsDto — general/currency/shipping/checkout/stripe/pos/seo/social settings,
// stored as flat Setting rows keyed by store.
export interface GeneralSettingsDto {
  storeName: string
  storeEmail: string
  storePhone: string
  adminEmail: string
  currency: string
  currencySymbol: string
  taxRate: string
  pricesIncludeTax: boolean
  shippingEnabled: boolean
  defaultShippingCost: string
  freeShippingEnabled: boolean
  freeShippingThreshold: string
  allowGuestCheckout: boolean
  requirePhone: boolean
  requireCompany: boolean
  stripePublicKey: string
  stripeSecretKey: string
  stripeEnabled: boolean
  posApiUrl: string
  posApiToken: string
  posWebhookSecret: string
  posSyncEnabled: boolean
  metaTitle: string
  metaDescription: string
  metaKeywords: string
  facebookUrl: string
  instagramUrl: string
  twitterUrl: string
}

// Mirrors StoreSettingsDto — branding (logo/colors) + home page section toggles.
export interface StoreSettingsDto {
  id: number | null
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
  categoriesTitle: string | null
  categoriesLimit: number
  showNewProducts: boolean
  newProductsTitle: string | null
  newProductsLimit: number
  newProductsDays: number
  showFeaturedProducts: boolean
  featuredProductsTitle: string | null
  featuredProductsLimit: number
  showDeals: boolean
  dealsTitle: string | null
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
}
