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
import AdminDashboard from './admin/pages/Dashboard'
import ProductsList from './admin/pages/products/ProductsList'
import ProductForm from './admin/pages/products/ProductForm'
import CategoriesList from './admin/pages/categories/CategoriesList'
import CategoryForm from './admin/pages/categories/CategoryForm'
import OrdersList from './admin/pages/orders/OrdersList'
import OrderDetail from './admin/pages/orders/OrderDetail'

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
    ],
  },
])

export default router
