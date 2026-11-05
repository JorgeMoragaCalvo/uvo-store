import { useEffect, useState } from 'react'
import { AlertTriangle, Clock, Package, PackageX, Receipt, SendHorizontal } from 'lucide-react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import adminApi from '@/admin/services/adminApi'
import type { AdminOrderStats, AdminProductStats } from '@/admin/types/admin'

export default function Dashboard() {
  const [productStats, setProductStats] = useState<AdminProductStats | null>(null)
  const [orderStats, setOrderStats] = useState<AdminOrderStats | null>(null)
  // PROD-24b. Sin esto, una cifra que no llegaba quedaba cargando para siempre.
  const [productsFailed, setProductsFailed] = useState(false)
  const [ordersFailed, setOrdersFailed] = useState(false)

  useEffect(() => {
    adminApi.products.stats().then(setProductStats).catch(() => setProductsFailed(true))
    adminApi.orders.stats().then(setOrderStats).catch(() => setOrdersFailed(true))
  }, [])

  const cards = [
    { label: 'Productos activos', value: productStats?.active, failed: productsFailed, icon: Package },
    { label: 'Bajo stock', value: productStats?.lowStock, failed: productsFailed, icon: AlertTriangle },
    { label: 'Órdenes pendientes', value: orderStats?.pending, failed: ordersFailed, icon: Clock },
    { label: 'Órdenes totales', value: orderStats?.all, failed: ordersFailed, icon: Receipt },
    // GAP-03: cobradas que no se pueden despachar; están en la pestaña «Sin stock» de Órdenes.
    { label: 'Órdenes sin stock', value: orderStats?.stockShortage, failed: ordersFailed, icon: PackageX },
    // GAP-04: ventas al POS y correos que no se entregaron; se revisan en «Envíos fallidos».
    { label: 'Envíos fallidos', value: orderStats?.outboxFailed, failed: ordersFailed, icon: SendHorizontal },
  ]

  return (
    <div className="flex flex-col gap-6">
      <h1 className="text-2xl font-semibold">Dashboard</h1>
      {(productsFailed || ordersFailed) && (
        <p role="alert" className="rounded-md bg-destructive/10 p-3 text-sm text-destructive">
          No se pudieron cargar algunas cifras. Recarga la página para intentarlo de nuevo.
        </p>
      )}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {cards.map((card) => (
          <Card key={card.label}>
            <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
              <CardTitle className="text-sm font-medium text-muted-foreground">{card.label}</CardTitle>
              <card.icon className="size-4 text-muted-foreground" />
            </CardHeader>
            <CardContent>
              {card.value !== undefined ? (
                <div className="text-2xl font-bold">{card.value}</div>
              ) : card.failed ? (
                <div className="text-2xl font-bold text-muted-foreground">
                  <span aria-hidden="true">—</span>
                  <span className="sr-only">Sin datos</span>
                </div>
              ) : (
                <Skeleton className="h-8 w-16" />
              )}
            </CardContent>
          </Card>
        ))}
      </div>
    </div>
  )
}
