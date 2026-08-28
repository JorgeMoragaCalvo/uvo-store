import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { CreditCard, Image, LayoutDashboard, LogOut, Menu, Package, Receipt, Settings, Shield, Tags, Ticket, UserCog, Users } from 'lucide-react'
import { Toaster } from '@/components/ui/sonner'
import { Button } from '@/components/ui/button'
import { Sheet, SheetContent, SheetTrigger } from '@/components/ui/sheet'
import { useAdminAuthStore } from '@/admin/stores/useAdminAuthStore'
import { cn } from '@/lib/utils'

const NAV_ITEMS = [
  { to: '/admin', label: 'Dashboard', icon: LayoutDashboard, end: true },
  { to: '/admin/products', label: 'Productos', icon: Package },
  { to: '/admin/categories', label: 'Categorías', icon: Tags },
  { to: '/admin/orders', label: 'Órdenes', icon: Receipt },
  { to: '/admin/customers', label: 'Clientes', icon: Users },
  { to: '/admin/coupons', label: 'Cupones', icon: Ticket },
  { to: '/admin/users', label: 'Usuarios', icon: UserCog },
  { to: '/admin/roles', label: 'Roles', icon: Shield },
  { to: '/admin/payment-gateways', label: 'Pasarelas de pago', icon: CreditCard },
  { to: '/admin/banners', label: 'Banners', icon: Image },
  { to: '/admin/settings/store', label: 'Config. de tienda', icon: Settings },
  { to: '/admin/settings/general', label: 'Config. general', icon: Settings },
]

function NavLinks({ onNavigate }: { onNavigate?: () => void }) {
  return (
    <nav className="flex flex-col gap-1 px-3">
      {NAV_ITEMS.map((item) => (
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
          <item.icon className="size-4" />
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

  function handleLogout() {
    logout()
    navigate('/admin/login', { replace: true })
  }

  return (
    <div className="flex min-h-svh bg-muted/30">
      <aside className="hidden w-64 shrink-0 border-r bg-background md:flex md:flex-col">
        <div className="flex h-14 items-center border-b px-4 text-lg font-semibold">UvoStore Admin</div>
        <div className="flex-1 py-4">
          <NavLinks />
        </div>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-14 items-center gap-3 border-b bg-background px-4">
          <Sheet>
            <SheetTrigger asChild>
              <Button variant="ghost" size="icon" className="md:hidden">
                <Menu className="size-5" />
              </Button>
            </SheetTrigger>
            <SheetContent side="left" className="w-64 p-0">
              <div className="flex h-14 items-center border-b px-4 text-lg font-semibold">UvoStore Admin</div>
              <div className="py-4">
                <NavLinks />
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

        <main className="flex-1 p-4 md:p-6">
          <Outlet />
        </main>
      </div>

      <Toaster />
    </div>
  )
}
