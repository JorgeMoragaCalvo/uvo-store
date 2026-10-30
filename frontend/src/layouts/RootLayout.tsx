import { useEffect } from 'react'
import { Outlet } from 'react-router-dom'
import Header from '../components/layout/Header'
import Footer from '../components/layout/Footer'
import CartSidebar from '../components/cart/CartSidebar'
import Toast from '../components/Toast'
import { useStoreSettingsStore } from '../stores/useStoreSettingsStore'

export default function RootLayout() {
  const fetchAll = useStoreSettingsStore((state) => state.fetchAll)

  useEffect(() => {
    fetchAll()
  }, [fetchAll])

  return (
    <div className="flex min-h-svh flex-col">
      {/* PROD-24b. Para quien navega con teclado: sin esto, cada página empieza recorriendo el header entero. */}
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-[200] focus:rounded focus:bg-white focus:px-4 focus:py-2 focus:text-sm focus:font-medium focus:text-dark focus:shadow-lg"
      >
        Saltar al contenido
      </a>
      <Header />
      <main id="main-content" tabIndex={-1} className="flex-1 outline-none">
        <Outlet />
      </main>
      <Footer />
      <CartSidebar />
      <Toast />
    </div>
  )
}
