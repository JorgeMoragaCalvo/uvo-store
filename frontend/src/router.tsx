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
])

export default router
