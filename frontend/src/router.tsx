import { createBrowserRouter } from 'react-router-dom'
import RootLayout from './layouts/RootLayout'
import Home from './pages/Home'
import Shop from './pages/Shop'
import ProductDetail from './pages/ProductDetail'
import Cart from './pages/Cart'
import OrderSuccess from './pages/OrderSuccess'
import TrackOrder from './pages/TrackOrder'
import Terms from './pages/legal/Terms'
import Privacy from './pages/legal/Privacy'
import ShippingPolicy from './pages/legal/ShippingPolicy'
import ReturnsPolicy from './pages/legal/ReturnsPolicy'

// A6: the whole app used to be one 1.14 MB chunk — someone landing on the storefront downloaded the
// entire admin panel, recharts and the store-onboarding page before seeing a product.
//
// The split is by audience, not by route count. The storefront stays statically imported: it's the
// hot path, its pages are small, and making them lazy would add a network round trip in the middle
// of buying. Everything behind /admin and /plataforma is loaded on demand through React Router 7's
// own `lazy` (the idiomatic form with createBrowserRouter — the router handles the pending state,
// so no React.lazy or Suspense wrappers are needed).
const lazyRoute = (load: () => Promise<{ default: React.ComponentType }>) =>
  async () => ({ Component: (await load()).default })

export const router = createBrowserRouter([
  {
    path: '/',
    element: <RootLayout />,
    children: [
      { index: true, element: <Home /> },
      { path: 'shop', element: <Shop /> },
      { path: 'category/:slug', element: <Shop /> },
      { path: 'product/:slug', element: <ProductDetail /> },
      { path: 'cart', element: <Cart /> },
      // The one storefront route loaded on demand: zod + react-hook-form live here and
      // nowhere else, and someone browsing the catalogue shouldn't pay for them. The chunk
      // arrives while the customer is still filling in the first step.
      { path: 'checkout', lazy: lazyRoute(() => import('./pages/Checkout')) },
      { path: 'order-success', element: <OrderSuccess /> },
      { path: 'track-order', element: <TrackOrder /> },
      { path: 'terminos-y-condiciones', element: <Terms /> },
      { path: 'politica-de-privacidad', element: <Privacy /> },
      { path: 'politica-de-envios', element: <ShippingPolicy /> },
      { path: 'politica-de-devoluciones', element: <ReturnsPolicy /> },
    ],
  },
  { path: '/admin/login', lazy: lazyRoute(() => import('./admin/pages/Login')) },
  { path: '/admin/forgot-password', lazy: lazyRoute(() => import('./admin/pages/ForgotPassword')) },
  { path: '/admin/reset-password', lazy: lazyRoute(() => import('./admin/pages/ResetPassword')) },
  { path: '/plataforma/nueva-tienda', lazy: lazyRoute(() => import('./platform/pages/NewStore')) },
  {
    path: '/admin',
    // The guarded shell itself is lazy too, so the storefront never pulls in AdminLayout, its nav,
    // or the auth store.
    lazy: async () => {
      const [{ default: RequireAdminAuth }, { default: AdminLayout }] = await Promise.all([
        import('./admin/components/RequireAdminAuth'),
        import('./admin/layouts/AdminLayout'),
      ])
      return {
        Component: () => (
          <RequireAdminAuth>
            <AdminLayout />
          </RequireAdminAuth>
        ),
      }
    },
    children: [
      // A1: `handle.permission` is what AdminLayout checks before rendering the outlet, so typing a
      // URL is guarded the same as clicking the menu. Dashboard has none — every admin lands there.
      { index: true, lazy: lazyRoute(() => import('./admin/pages/Dashboard')) },
      { path: 'products', lazy: lazyRoute(() => import('./admin/pages/products/ProductsList')), handle: { permission: 'products.view' } },
      { path: 'products/new', lazy: lazyRoute(() => import('./admin/pages/products/ProductForm')), handle: { permission: 'products.manage' } },
      { path: 'products/:id/edit', lazy: lazyRoute(() => import('./admin/pages/products/ProductForm')), handle: { permission: 'products.manage' } },
      { path: 'categories', lazy: lazyRoute(() => import('./admin/pages/categories/CategoriesList')), handle: { permission: 'categories.view' } },
      { path: 'categories/new', lazy: lazyRoute(() => import('./admin/pages/categories/CategoryForm')), handle: { permission: 'categories.manage' } },
      { path: 'categories/:id/edit', lazy: lazyRoute(() => import('./admin/pages/categories/CategoryForm')), handle: { permission: 'categories.manage' } },
      { path: 'orders', lazy: lazyRoute(() => import('./admin/pages/orders/OrdersList')), handle: { permission: 'orders.view' } },
      { path: 'orders/:id', lazy: lazyRoute(() => import('./admin/pages/orders/OrderDetail')), handle: { permission: 'orders.view' } },
      { path: 'coupons', lazy: lazyRoute(() => import('./admin/pages/coupons/CouponsList')), handle: { permission: 'coupons.view' } },
      { path: 'coupons/new', lazy: lazyRoute(() => import('./admin/pages/coupons/CouponForm')), handle: { permission: 'coupons.manage' } },
      { path: 'coupons/:id/edit', lazy: lazyRoute(() => import('./admin/pages/coupons/CouponForm')), handle: { permission: 'coupons.manage' } },
      { path: 'customers', lazy: lazyRoute(() => import('./admin/pages/customers/CustomersList')), handle: { permission: 'customers.view' } },
      { path: 'customers/:id', lazy: lazyRoute(() => import('./admin/pages/customers/CustomerDetail')), handle: { permission: 'customers.view' } },
      { path: 'users', lazy: lazyRoute(() => import('./admin/pages/users/UsersList')), handle: { permission: 'users.view' } },
      { path: 'users/new', lazy: lazyRoute(() => import('./admin/pages/users/UserForm')), handle: { permission: 'users.manage' } },
      { path: 'users/:id/edit', lazy: lazyRoute(() => import('./admin/pages/users/UserForm')), handle: { permission: 'users.manage' } },
      { path: 'roles', lazy: lazyRoute(() => import('./admin/pages/roles/RolesList')), handle: { permission: 'roles.view' } },
      { path: 'roles/new', lazy: lazyRoute(() => import('./admin/pages/roles/RoleForm')), handle: { permission: 'roles.manage' } },
      { path: 'roles/:id/edit', lazy: lazyRoute(() => import('./admin/pages/roles/RoleForm')), handle: { permission: 'roles.manage' } },
      { path: 'shipping/zones', lazy: lazyRoute(() => import('./admin/pages/shipping/ZonesList')), handle: { permission: 'shipping.view' } },
      { path: 'shipping/zones/new', lazy: lazyRoute(() => import('./admin/pages/shipping/ZoneForm')), handle: { permission: 'shipping.manage' } },
      { path: 'shipping/zones/:id/edit', lazy: lazyRoute(() => import('./admin/pages/shipping/ZoneForm')), handle: { permission: 'shipping.manage' } },
      { path: 'shipping/methods', lazy: lazyRoute(() => import('./admin/pages/shipping/MethodsList')), handle: { permission: 'shipping.view' } },
      { path: 'shipping/methods/new', lazy: lazyRoute(() => import('./admin/pages/shipping/MethodForm')), handle: { permission: 'shipping.manage' } },
      { path: 'shipping/methods/:id/edit', lazy: lazyRoute(() => import('./admin/pages/shipping/MethodForm')), handle: { permission: 'shipping.manage' } },
      { path: 'shipping/rates', lazy: lazyRoute(() => import('./admin/pages/shipping/RatesList')), handle: { permission: 'shipping.view' } },
      { path: 'shipping/rates/new', lazy: lazyRoute(() => import('./admin/pages/shipping/RateForm')), handle: { permission: 'shipping.manage' } },
      { path: 'shipping/rates/:id/edit', lazy: lazyRoute(() => import('./admin/pages/shipping/RateForm')), handle: { permission: 'shipping.manage' } },
      // The only route that pulls in recharts — with this split it lands in its own chunk.
      { path: 'reports', lazy: lazyRoute(() => import('./admin/pages/reports/Reports')), handle: { permission: 'reports.view' } },
      { path: 'payment-gateways', lazy: lazyRoute(() => import('./admin/pages/payment-gateways/PaymentGateways')), handle: { permission: 'payments.view' } },
      { path: 'banners', lazy: lazyRoute(() => import('./admin/pages/banners/BannersList')), handle: { permission: 'banners.view' } },
      { path: 'banners/new', lazy: lazyRoute(() => import('./admin/pages/banners/BannerForm')), handle: { permission: 'banners.manage' } },
      { path: 'banners/:id/edit', lazy: lazyRoute(() => import('./admin/pages/banners/BannerForm')), handle: { permission: 'banners.manage' } },
      { path: 'settings/general', lazy: lazyRoute(() => import('./admin/pages/settings/GeneralSettings')), handle: { permission: 'settings.view' } },
      { path: 'settings/store', lazy: lazyRoute(() => import('./admin/pages/settings/StoreSettingsPage')), handle: { permission: 'settings.view' } },
    ],
  },
])

export default router
