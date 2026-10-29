import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Menu } from 'lucide-react'
import { Sheet, SheetClose, SheetContent, SheetTitle, SheetTrigger } from '@/components/ui/sheet'
import { useStoreSettingsStore } from '../../stores/useStoreSettingsStore'
import { selectItemCount, useCartStore } from '../../stores/useCartStore'

export default function Header() {
  const { settings, categories } = useStoreSettingsStore()
  const navigate = useNavigate()
  const [search, setSearch] = useState('')
  const [menuOpen, setMenuOpen] = useState(false)
  const itemCount = useCartStore(selectItemCount)
  const toggleCart = useCartStore((state) => state.toggleSidebar)

  // Debounced redirect to /shop?search=..., mirrors Search.init() in the legacy app.js.
  useEffect(() => {
    if (search.trim().length < 3) return
    const timeout = setTimeout(() => {
      navigate(`/shop?search=${encodeURIComponent(search.trim())}`)
      // Si se buscó desde el menú del celular, los resultados quedan detrás: se cierra para verlos.
      setMenuOpen(false)
    }, 500)
    return () => clearTimeout(timeout)
  }, [search, navigate])

  const topCategories = categories.slice(0, 5)
  const navLinks = [
    { to: '/', label: 'Inicio' },
    { to: '/shop', label: 'Tienda' },
    ...topCategories.map((category) => ({ to: `/category/${category.slug}`, label: category.name })),
    { to: '/shop?on_sale=true', label: 'Ofertas' },
  ]
  const freeShippingMessage =
    settings?.shippingEnabled && settings.freeShippingEnabled && Number(settings.freeShippingThreshold) > 0
      ? `Envío gratis sobre $${Number(settings.freeShippingThreshold).toLocaleString('es-CL')}`
      : null

  return (
    <header className="sticky top-0 z-40 bg-white shadow-sm">
      {freeShippingMessage && (
        <div className="bg-primary py-1.5 text-center text-xs font-medium text-white">{freeShippingMessage}</div>
      )}

      <div className="mx-auto flex max-w-6xl items-center gap-3 px-4 py-3 lg:gap-6">
        {/* Bajo lg, la navegación y (en el celular) el buscador viven en este menú; antes, bajo md,
            desaparecían sin reemplazo y desde el header solo se llegaba al carrito. */}
        <Sheet open={menuOpen} onOpenChange={setMenuOpen}>
          <SheetTrigger asChild>
            <button
              type="button"
              className="-ml-2 flex size-10 items-center justify-center text-dark lg:hidden"
              aria-label="Abrir menú"
            >
              <Menu className="size-5" />
            </button>
          </SheetTrigger>
          <SheetContent side="left" className="w-72 bg-white p-0" aria-describedby={undefined}>
            <SheetTitle className="border-b px-4 py-4 text-lg font-semibold text-dark">
              {settings?.storeName ?? 'UvoStore'}
            </SheetTitle>
            <div className="flex flex-col gap-4 px-4">
              <input
                type="search"
                value={search}
                onChange={(event) => setSearch(event.target.value)}
                placeholder="Buscar productos..."
                aria-label="Buscar productos"
                className="w-full rounded-full border border-gray-400 px-4 py-2 text-base outline-none focus:border-primary"
              />
              <nav className="flex flex-col text-base font-medium text-secondary">
                {navLinks.map((link) => (
                  <SheetClose asChild key={link.to}>
                    <Link to={link.to} className="rounded py-2.5 hover:text-primary">
                      {link.label}
                    </Link>
                  </SheetClose>
                ))}
              </nav>
            </div>
          </SheetContent>
        </Sheet>

        <Link to="/" className="flex items-center gap-2 shrink-0">
          {settings?.storeLogo ? (
            <img src={settings.storeLogo} alt={settings.storeName} loading="eager" className="h-9 w-auto" />
          ) : (
            <span className="text-xl font-bold text-dark">{settings?.storeName ?? 'UvoStore'}</span>
          )}
        </Link>

        <nav className="hidden items-center gap-5 text-sm font-medium text-secondary lg:flex">
          {navLinks.map((link) => (
            <Link key={link.to} to={link.to} className="hover:text-primary">
              {link.label}
            </Link>
          ))}
        </nav>

        <div className="ml-auto flex items-center gap-2 sm:gap-4">
          <input
            type="search"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Buscar productos..."
            className="hidden w-56 rounded-full border border-gray-400 px-4 py-1.5 text-sm outline-none focus:border-primary sm:block"
          />

          {/*
            PROD-07: aquí había un 👤 «Cuenta (próximamente)». Se quitó, no se comentó por costumbre:
            era un punto de entrada a una función diferida, y no lleva a ninguna parte porque el
            storefront no tiene superficie de cuenta de comprador. Vuelve cuando existan las pantallas
            (ver CLAUDE.md, PROD-07), y entonces como <Link>, no como un span inerte.
          */}
          <button
            type="button"
            onClick={toggleCart}
            className="relative -mr-2 flex size-10 items-center justify-center text-xl text-dark"
            title="Carrito"
            aria-label="Abrir carrito"
          >
            🛒
            <span className="absolute right-0 top-0 flex h-4 w-4 items-center justify-center rounded-full bg-primary text-[10px] text-white">
              {itemCount}
            </span>
          </button>
        </div>
      </div>
    </header>
  )
}
