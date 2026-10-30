import { useState } from 'react'
import { NavLink, Outlet, useLocation, useMatches, useNavigate } from 'react-router-dom'
import {
  Banknote,
  BarChart3,
  CreditCard,
  Image,
  LayoutDashboard,
  LogOut,
  Map,
  Menu,
  Package,
  Receipt,
  Settings,
  Shield,
  Tags,
  Ticket,
  Truck,
  UserCog,
  Users,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { Toaster } from '@/components/ui/sonner'
import { Button } from '@/components/ui/button'
import { Sheet, SheetContent, SheetTitle, SheetTrigger } from '@/components/ui/sheet'
import { hasPermission, useAdminAuthStore } from '@/admin/stores/useAdminAuthStore'
import { cn } from '@/lib/utils'
import { useDocumentTitle } from '@/hooks/useDocumentTitle'

// A1: `permission` is what reveals each entry. Dashboard has none — it's where every admin lands,
// whatever their role. Hiding a link is cosmetic; the endpoints behind it are enforced server-side.
const NAV_ITEMS: { to: string; label: string; icon: LucideIcon; end?: boolean; permission?: string }[] = [
  { to: '/admin', label: 'Dashboard', icon: LayoutDashboard, end: true },
  { to: '/admin/products', permission: 'products.view', label: 'Productos', icon: Package },
  { to: '/admin/categories', permission: 'categories.view', label: 'Categorías', icon: Tags },
  { to: '/admin/orders', permission: 'orders.view', label: 'Órdenes', icon: Receipt },
  { to: '/admin/customers', permission: 'customers.view', label: 'Clientes', icon: Users },
  { to: '/admin/coupons', permission: 'coupons.view', label: 'Cupones', icon: Ticket },
  { to: '/admin/users', permission: 'users.view', label: 'Usuarios', icon: UserCog },
  { to: '/admin/roles', permission: 'roles.view', label: 'Roles', icon: Shield },
  { to: '/admin/shipping/zones', permission: 'shipping.view', label: 'Zonas de envío', icon: Map },
  { to: '/admin/shipping/methods', permission: 'shipping.view', label: 'Métodos de envío', icon: Truck },
  { to: '/admin/shipping/rates', permission: 'shipping.view', label: 'Tarifas de envío', icon: Banknote },
  { to: '/admin/reports', permission: 'reports.view', label: 'Reportes', icon: BarChart3 },
  { to: '/admin/payment-gateways', permission: 'payments.view', label: 'Pasarelas de pago', icon: CreditCard },
  { to: '/admin/banners', permission: 'banners.view', label: 'Banners', icon: Image },
  { to: '/admin/settings/store', permission: 'settings.view', label: 'Config. de tienda', icon: Settings },
  { to: '/admin/settings/general', permission: 'settings.view', label: 'Config. general', icon: Settings },
]

// PROD-24b. El título de la pestaña dice en qué sección se está: la entrada del menú cuyo destino es el
// prefijo más largo de la URL («/admin/products/3/edit» → Productos).
function sectionTitle(pathname: string) {
  const match = NAV_ITEMS.filter((item) => (item.end ? pathname === item.to : pathname.startsWith(item.to)))
    .sort((a, b) => b.to.length - a.to.length)[0]
  return match?.label ?? null
}

function NavLinks({ onNavigate }: { onNavigate?: () => void }) {
  const user = useAdminAuthStore((state) => state.user)
  const visible = NAV_ITEMS.filter((item) => !item.permission || hasPermission(user, item.permission))

  return (
    <nav aria-label="Menú del panel" className="flex flex-col gap-1 px-3">
      {visible.map((item) => (
        <NavLink
          key={item.to}
          to={item.to}
          end={item.end}
          onClick={onNavigate}
          className={({ isActive }) =>
            cn(
              'flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors',
              isActive ? 'bg-secondary text-secondary-foreground' : 'text-muted-foreground hover:bg-muted hover:text-foreground',
            )
          }
        >
          <item.icon aria-hidden="true" className="size-4" />
          {item.label}
        </NavLink>
      ))}
    </nav>
  )
}

export default function AdminLayout() {
  const user = useAdminAuthStore((state) => state.user)
  const logout = useAdminAuthStore((state) => state.logout)
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const [menuOpen, setMenuOpen] = useState(false)
  useDocumentTitle(sectionTitle(pathname), 'Panel de administración')

  // Hiding the menu entry isn't enough — the URL can be typed. Each admin route declares what it
  // needs via `handle` in router.tsx, so one check here covers all 36 of them without a wrapper
  // component per route (and without fighting the lazy loading from A6).
  const matches = useMatches() as { handle?: { permission?: string } }[]
  const required = matches.map((m) => m.handle?.permission).filter(Boolean) as string[]
  const allowed = required.every((permission) => hasPermission(user, permission))

  function handleLogout() {
    logout()
    navigate('/admin/login', { replace: true })
  }

  return (
    <div className="flex min-h-svh bg-muted/30">
      {/* PROD-24b. Sin esto, cada pantalla empieza recorriendo con Tab las 16 entradas del menú. */}
      <a
        href="#admin-main"
        className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-[200] focus:rounded-md focus:bg-background focus:px-4 focus:py-2 focus:text-sm focus:font-medium focus:shadow-lg"
      >
        Saltar al contenido
      </a>
      <aside className="hidden w-64 shrink-0 border-r bg-background md:flex md:flex-col">
        <div className="flex h-14 items-center border-b px-4 text-lg font-semibold">UvoStore Admin</div>
        <div className="flex-1 py-4">
          <NavLinks />
        </div>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-14 items-center gap-3 border-b bg-background px-4">
          {/* PROD-24b. Controlado para cerrarlo al elegir una sección: antes quedaba abierto encima de la
              página nueva. El SheetTitle le da nombre al diálogo. */}
          <Sheet open={menuOpen} onOpenChange={setMenuOpen}>
            <SheetTrigger asChild>
              <Button variant="ghost" size="icon" className="md:hidden" aria-label="Abrir menú">
                <Menu className="size-5" />
              </Button>
            </SheetTrigger>
            <SheetContent side="left" className="w-64 p-0" aria-describedby={undefined}>
              <SheetTitle className="flex h-14 items-center border-b px-4 text-lg font-semibold">UvoStore Admin</SheetTitle>
              <div className="py-4">
                <NavLinks onNavigate={() => setMenuOpen(false)} />
              </div>
            </SheetContent>
          </Sheet>

          <div className="ml-auto flex items-center gap-3">
            <span className="text-sm text-muted-foreground">{user?.name}</span>
            <Button variant="outline" size="sm" onClick={handleLogout}>
              <LogOut className="size-4" />
              Salir
            </Button>
          </div>
        </header>

        <main id="admin-main" tabIndex={-1} className="flex-1 p-4 outline-none md:p-6">
          {allowed ? (
            <Outlet />
          ) : (
            <div className="rounded-lg border border-dashed p-8 text-center">
              <p className="font-medium">No tienes permiso para ver esta sección</p>
              <p className="mt-1 text-sm text-muted-foreground">
                Pídele a un administrador que revise tu rol.
              </p>
            </div>
          )}
        </main>
      </div>

      <Toaster />
    </div>
  )
}
