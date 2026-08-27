import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useStoreSettingsStore } from '../../stores/useStoreSettingsStore'
import { selectItemCount, useCartStore } from '../../stores/useCartStore'

export default function Header() {
  const { settings, categories } = useStoreSettingsStore()
  const navigate = useNavigate()
  const [search, setSearch] = useState('')
  const itemCount = useCartStore(selectItemCount)
  const toggleCart = useCartStore((state) => state.toggleSidebar)

  // Debounced redirect to /shop?search=..., mirrors Search.init() in the legacy app.js.
  useEffect(() => {
    if (search.trim().length < 3) return
    const timeout = setTimeout(() => {
      navigate(`/shop?search=${encodeURIComponent(search.trim())}`)
    }, 500)
    return () => clearTimeout(timeout)
  }, [search, navigate])

  const topCategories = categories.slice(0, 5)
  const freeShippingMessage =
    settings?.shippingEnabled && settings.freeShippingEnabled && Number(settings.freeShippingThreshold) > 0
      ? `Envío gratis sobre $${Number(settings.freeShippingThreshold).toLocaleString('es-CL')}`
      : null

  return (
    <header className="sticky top-0 z-40 bg-white shadow-sm">
      {freeShippingMessage && (
        <div className="bg-primary py-1.5 text-center text-xs font-medium text-white">{freeShippingMessage}</div>
      )}

      <div className="mx-auto flex max-w-6xl items-center gap-6 px-4 py-3">
        <Link to="/" className="flex items-center gap-2 shrink-0">
          {settings?.storeLogo ? (
            <img src={settings.storeLogo} alt={settings.storeName} className="h-9 w-auto" />
          ) : (
            <span className="text-xl font-bold text-dark">{settings?.storeName ?? 'UvoStore'}</span>
          )}
        </Link>

        <nav className="hidden items-center gap-5 text-sm font-medium text-secondary md:flex">
          <Link to="/" className="hover:text-primary">Inicio</Link>
          <Link to="/shop" className="hover:text-primary">Tienda</Link>
          {topCategories.map((category) => (
            <Link key={category.id} to={`/category/${category.slug}`} className="hover:text-primary">
              {category.name}
            </Link>
          ))}
          <Link to="/shop?on_sale=true" className="hover:text-primary">Ofertas</Link>
        </nav>

        <div className="ml-auto flex items-center gap-4">
          <input
            type="search"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Buscar productos..."
            className="hidden w-56 rounded-full border border-gray-400 px-4 py-1.5 text-sm outline-none focus:border-primary sm:block"
          />

          <span className="cursor-not-allowed text-secondary" title="Cuenta (próximamente)" aria-disabled>
            👤
          </span>

          <button type="button" onClick={toggleCart} className="relative text-dark" title="Carrito">
            🛒
            <span className="absolute -right-2 -top-2 flex h-4 w-4 items-center justify-center rounded-full bg-primary text-[10px] text-white">
              {itemCount}
            </span>
          </button>
        </div>
      </div>
    </header>
  )
}
