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
      <Header />
      <main className="flex-1">
        <Outlet />
      </main>
      <Footer />
      <CartSidebar />
      <Toast />
    </div>
  )
}
