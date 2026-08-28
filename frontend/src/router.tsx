import { createBrowserRouter } from 'react-router-dom'
import RootLayout from './layouts/RootLayout'
import Home from './pages/Home'
import Shop from './pages/Shop'
import ProductDetail from './pages/ProductDetail'
import Cart from './pages/Cart'
import Checkout from './pages/Checkout'
import OrderSuccess from './pages/OrderSuccess'
import TrackOrder from './pages/TrackOrder'
import Terms from './pages/legal/Terms'
import Privacy from './pages/legal/Privacy'
import ShippingPolicy from './pages/legal/ShippingPolicy'
import ReturnsPolicy from './pages/legal/ReturnsPolicy'
import AdminLayout from './admin/layouts/AdminLayout'
import RequireAdminAuth from './admin/components/RequireAdminAuth'
import AdminLogin from './admin/pages/Login'
import AdminForgotPassword from './admin/pages/ForgotPassword'
import AdminResetPassword from './admin/pages/ResetPassword'
import AdminDashboard from './admin/pages/Dashboard'
import ProductsList from './admin/pages/products/ProductsList'
import ProductForm from './admin/pages/products/ProductForm'
import CategoriesList from './admin/pages/categories/CategoriesList'
import CategoryForm from './admin/pages/categories/CategoryForm'
import OrdersList from './admin/pages/orders/OrdersList'
import OrderDetail from './admin/pages/orders/OrderDetail'
import PaymentGateways from './admin/pages/payment-gateways/PaymentGateways'
import CouponsList from './admin/pages/coupons/CouponsList'
import CouponForm from './admin/pages/coupons/CouponForm'
import BannersList from './admin/pages/banners/BannersList'
import BannerForm from './admin/pages/banners/BannerForm'
import GeneralSettings from './admin/pages/settings/GeneralSettings'
import StoreSettingsPage from './admin/pages/settings/StoreSettingsPage'
import NewStore from './platform/pages/NewStore'

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
      { path: 'checkout', element: <Checkout /> },
      { path: 'order-success', element: <OrderSuccess /> },
      { path: 'track-order', element: <TrackOrder /> },
      { path: 'terminos-y-condiciones', element: <Terms /> },
      { path: 'politica-de-privacidad', element: <Privacy /> },
      { path: 'politica-de-envios', element: <ShippingPolicy /> },
      { path: 'politica-de-devoluciones', element: <ReturnsPolicy /> },
    ],
  },
  { path: '/admin/login', element: <AdminLogin /> },
  { path: '/admin/forgot-password', element: <AdminForgotPassword /> },
  { path: '/admin/reset-password', element: <AdminResetPassword /> },
  { path: '/plataforma/nueva-tienda', element: <NewStore /> },
  {
    path: '/admin',
    element: (
      <RequireAdminAuth>
        <AdminLayout />
      </RequireAdminAuth>
    ),
    children: [
      { index: true, element: <AdminDashboard /> },
      { path: 'products', element: <ProductsList /> },
      { path: 'products/new', element: <ProductForm /> },
      { path: 'products/:id/edit', element: <ProductForm /> },
      { path: 'categories', element: <CategoriesList /> },
      { path: 'categories/new', element: <CategoryForm /> },
      { path: 'categories/:id/edit', element: <CategoryForm /> },
      { path: 'orders', element: <OrdersList /> },
      { path: 'orders/:id', element: <OrderDetail /> },
      { path: 'coupons', element: <CouponsList /> },
      { path: 'coupons/new', element: <CouponForm /> },
      { path: 'coupons/:id/edit', element: <CouponForm /> },
      { path: 'payment-gateways', element: <PaymentGateways /> },
      { path: 'banners', element: <BannersList /> },
      { path: 'banners/new', element: <BannerForm /> },
      { path: 'banners/:id/edit', element: <BannerForm /> },
      { path: 'settings/general', element: <GeneralSettings /> },
      { path: 'settings/store', element: <StoreSettingsPage /> },
    ],
  },
])

export default router
