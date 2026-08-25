import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { toast } from 'sonner'
import { ArrowLeft } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import adminApi from '@/admin/services/adminApi'
import { useAdminOrdersStore } from '@/admin/stores/useAdminOrdersStore'
import type { AdminOrderDetail } from '@/admin/types/admin'

const ORDER_STATUSES = ['PENDING', 'PAID', 'PROCESSING', 'SHIPPED', 'DELIVERED', 'CANCELLED', 'REFUNDED']
const PAYMENT_STATUSES = ['PENDING', 'PAID', 'FAILED', 'REFUNDED']

function formatCurrency(value: number) {
  return new Intl.NumberFormat('es-CL', { style: 'currency', currency: 'CLP' }).format(value)
}

export default function OrderDetail() {
  const { id } = useParams()
  const navigate = useNavigate()
  const order = useAdminOrdersStore((state) => state.detail)
  const loading = useAdminOrdersStore((state) => state.detailLoading)
  const fetchDetail = useAdminOrdersStore((state) => state.fetchDetail)

  useEffect(() => {
    if (!id) return
    fetchDetail(Number(id)).catch(() => toast.error('No se pudo cargar la orden'))
  }, [id, fetchDetail])

  if (loading || !order) {
    return <p className="text-muted-foreground">Cargando…</p>
  }

  // Keyed by order id so local state (the tracking-number input) initializes fresh from the
  // newly-loaded order instead of needing an effect to resync it from a prop change.
  return <OrderDetailContent key={order.id} order={order} onBack={() => navigate('/admin/orders')} />
}

function OrderDetailContent({ order, onBack }: { order: AdminOrderDetail; onBack: () => void }) {
  const applyDetailUpdate = useAdminOrdersStore((state) => state.applyDetailUpdate)
  const [tracking, setTracking] = useState(order.trackingNumber ?? '')
  const [busy, setBusy] = useState(false)

  async function runAction(action: () => Promise<AdminOrderDetail>, successMessage: string) {
    setBusy(true)
    try {
      await applyDetailUpdate(action)
      toast.success(successMessage)
    } catch (err) {
      const message = (err as { message?: string })?.message
      toast.error(message ?? 'No se pudo actualizar la orden')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center gap-3">
        <Button variant="ghost" size="icon" onClick={onBack}>
          <ArrowLeft className="size-4" />
        </Button>
        <h1 className="text-2xl font-semibold">{order.orderNumber}</h1>
        <Badge>{order.status}</Badge>
        <Badge variant="outline">{order.paymentStatus}</Badge>
      </div>

      <div className="flex flex-wrap gap-2">
        <Button
          size="sm"
          variant="outline"
          disabled={busy}
          onClick={() => runAction(() => adminApi.orders.markProcessing(order.id), 'Orden marcada en proceso')}
        >
          Marcar en proceso
        </Button>
        <Button
          size="sm"
          variant="outline"
          disabled={busy}
          onClick={() => runAction(() => adminApi.orders.markShipped(order.id), 'Orden marcada como enviada')}
        >
          Marcar enviada
        </Button>
        <Button
          size="sm"
          variant="outline"
          disabled={busy}
          onClick={() => runAction(() => adminApi.orders.markDelivered(order.id), 'Orden marcada como entregada')}
        >
          Marcar entregada
        </Button>
        <Button
          size="sm"
          variant="destructive"
          disabled={busy}
          onClick={() => runAction(() => adminApi.orders.cancel(order.id), 'Orden cancelada')}
        >
          Cancelar orden
        </Button>
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <div className="flex flex-col gap-4 lg:col-span-2">
          <Card>
            <CardHeader>
              <CardTitle>Productos</CardTitle>
            </CardHeader>
            <CardContent>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Producto</TableHead>
                    <TableHead>SKU</TableHead>
                    <TableHead>Cant.</TableHead>
                    <TableHead className="text-right">Subtotal</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {order.items.map((item) => (
                    <TableRow key={item.id}>
                      <TableCell>{item.productName}</TableCell>
                      <TableCell className="text-muted-foreground">{item.productSku ?? '—'}</TableCell>
                      <TableCell>{item.quantity}</TableCell>
                      <TableCell className="text-right">{formatCurrency(item.subtotal)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>

              <div className="mt-4 flex flex-col items-end gap-1 text-sm">
                <div className="flex w-48 justify-between">
                  <span className="text-muted-foreground">Subtotal</span>
                  <span>{formatCurrency(order.subtotal)}</span>
                </div>
                <div className="flex w-48 justify-between">
                  <span className="text-muted-foreground">Descuento</span>
                  <span>-{formatCurrency(order.discountAmount)}</span>
                </div>
                <div className="flex w-48 justify-between">
                  <span className="text-muted-foreground">Envío</span>
                  <span>{formatCurrency(order.shippingCost)}</span>
                </div>
                <div className="flex w-48 justify-between">
                  <span className="text-muted-foreground">Impuesto</span>
                  <span>{formatCurrency(order.taxAmount)}</span>
                </div>
                <div className="flex w-48 justify-between font-semibold">
                  <span>Total</span>
                  <span>{formatCurrency(order.total)}</span>
                </div>
              </div>
            </CardContent>
          </Card>

          {order.shippingAddress && (
            <Card>
              <CardHeader>
                <CardTitle>Dirección de envío</CardTitle>
              </CardHeader>
              <CardContent className="text-sm text-muted-foreground">
                <p>
                  {order.shippingAddress.firstName} {order.shippingAddress.lastName}
                </p>
                <p>{order.shippingAddress.addressLine1}</p>
                {order.shippingAddress.addressLine2 && <p>{order.shippingAddress.addressLine2}</p>}
                <p>
                  {order.shippingAddress.city}, {order.shippingAddress.state}
                </p>
                <p>{order.shippingAddress.postalCode}</p>
                <p>{order.shippingAddress.phone}</p>
              </CardContent>
            </Card>
          )}
        </div>

        <div className="flex flex-col gap-4">
          <Card>
            <CardHeader>
              <CardTitle>Cliente</CardTitle>
            </CardHeader>
            <CardContent className="flex flex-col gap-1 text-sm">
              <p className="font-medium">
                {order.customerFirstName} {order.customerLastName}
              </p>
              <p className="text-muted-foreground">{order.customerEmail}</p>
              {order.customerPhone && <p className="text-muted-foreground">{order.customerPhone}</p>}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>Estado</CardTitle>
            </CardHeader>
            <CardContent className="flex flex-col gap-4">
              <div className="flex flex-col gap-2">
                <Label>Estado de la orden</Label>
                <Select
                  value={order.status}
                  onValueChange={(value) =>
                    runAction(() => adminApi.orders.updateStatus(order.id, value), 'Estado actualizado')
                  }
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {ORDER_STATUSES.map((status) => (
                      <SelectItem key={status} value={status}>
                        {status}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="flex flex-col gap-2">
                <Label>Estado de pago</Label>
                <Select
                  value={order.paymentStatus}
                  onValueChange={(value) =>
                    runAction(() => adminApi.orders.updatePaymentStatus(order.id, value), 'Estado de pago actualizado')
                  }
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {PAYMENT_STATUSES.map((status) => (
                      <SelectItem key={status} value={status}>
                        {status}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>Seguimiento</CardTitle>
            </CardHeader>
            <CardContent className="flex flex-col gap-2">
              <Label htmlFor="tracking">Número de seguimiento</Label>
              <div className="flex gap-2">
                <Input id="tracking" value={tracking} onChange={(event) => setTracking(event.target.value)} />
                <Button
                  variant="outline"
                  disabled={busy}
                  onClick={() => runAction(() => adminApi.orders.saveTracking(order.id, tracking), 'Seguimiento guardado')}
                >
                  Guardar
                </Button>
              </div>
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  )
}
