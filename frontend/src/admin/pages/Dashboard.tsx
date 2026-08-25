import { useEffect, useState } from 'react'
import { AlertTriangle, Clock, Package, Receipt } from 'lucide-react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import adminApi from '@/admin/services/adminApi'
import type { AdminOrderStats, AdminProductStats } from '@/admin/types/admin'

export default function Dashboard() {
  const [productStats, setProductStats] = useState<AdminProductStats | null>(null)
  const [orderStats, setOrderStats] = useState<AdminOrderStats | null>(null)

  useEffect(() => {
    adminApi.products.stats().then(setProductStats).catch(() => setProductStats(null))
    adminApi.orders.stats().then(setOrderStats).catch(() => setOrderStats(null))
  }, [])

  const cards = [
    { label: 'Productos activos', value: productStats?.active, icon: Package },
    { label: 'Bajo stock', value: productStats?.lowStock, icon: AlertTriangle },
    { label: 'Órdenes pendientes', value: orderStats?.pending, icon: Clock },
    { label: 'Órdenes totales', value: orderStats?.all, icon: Receipt },
  ]

  return (
    <div className="flex flex-col gap-6">
      <h1 className="text-2xl font-semibold">Dashboard</h1>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {cards.map((card) => (
          <Card key={card.label}>
            <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
              <CardTitle className="text-sm font-medium text-muted-foreground">{card.label}</CardTitle>
              <card.icon className="size-4 text-muted-foreground" />
            </CardHeader>
            <CardContent>
              {card.value === undefined ? (
                <Skeleton className="h-8 w-16" />
              ) : (
                <div className="text-2xl font-bold">{card.value}</div>
              )}
            </CardContent>
          </Card>
        ))}
      </div>
    </div>
  )
}
