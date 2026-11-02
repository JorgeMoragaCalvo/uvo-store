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
import { hasPermission, useAdminAuthStore } from '@/admin/stores/useAdminAuthStore'
import { useAdminOrdersStore } from '@/admin/stores/useAdminOrdersStore'
import type { AdminOrderDetail, AdminOrderRefund } from '@/admin/types/admin'

// G4: REFUNDED no está en ninguna de las dos listas a propósito. Marcarlo a mano dejaba la base
// diciendo "devuelto" con el dinero cobrado; ahora se llega ahí por la tarjeta de reembolsos, que
// primero mueve el dinero. El backend rechaza el atajo aunque alguien llame a la API directamente.
const ORDER_STATUSES = ['PENDING', 'PAID', 'PROCESSING', 'SHIPPED', 'DELIVERED', 'CANCELLED']
const PAYMENT_STATUSES = ['PENDING', 'PAID', 'FAILED']

// GAP-02: si el dinero salió, si la pasarela lo rechazó, o si no se sabe.
const REFUND_STATUS_LABELS: Record<AdminOrderRefund['status'], string> = {
  PENDING: 'Pendiente',
  COMPLETED: 'Hecho',
  FAILED: 'Rechazado',
}
const REFUND_STATUS_VARIANTS: Record<AdminOrderRefund['status'], 'destructive' | 'secondary' | 'outline'> = {
  PENDING: 'destructive',
  COMPLETED: 'secondary',
  FAILED: 'outline',
}

function formatCurrency(value: number) {
  return new Intl.NumberFormat('es-CL', { style: 'currency', currency: 'CLP' }).format(value)
}

// El estado actual siempre tiene que estar entre las opciones o el selector se ve vacío. Pasa con
// las órdenes ya reembolsadas, cuyo estado dejó de ser elegible pero sigue siendo el que tienen.
function withCurrent(options: string[], current: string) {
  return options.includes(current) ? options : [...options, current]
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

  // El mensaje puede depender de la respuesta: reintentar el descuento no siempre alcanza (GAP-03).
  async function runAction(
    action: () => Promise<AdminOrderDetail>,
    successMessage: string | ((updated: AdminOrderDetail) => string),
  ) {
    setBusy(true)
    try {
      let message = ''
      await applyDetailUpdate(async () => {
        const updated = await action()
        message = typeof successMessage === 'function' ? successMessage(updated) : successMessage
        return updated
      })
      toast.success(message)
    } catch (err) {
      const message = (err as { message?: string })?.message
      toast.error(message ?? 'No se pudo actualizar la orden')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-3">
        <Button variant="ghost" size="icon" onClick={onBack} aria-label="Volver a las órdenes">
          <ArrowLeft className="size-4" />
        </Button>
        <h1 className="text-2xl font-semibold">{order.orderNumber}</h1>
        <Badge>{order.status}</Badge>
        <Badge variant="outline">{order.paymentStatus}</Badge>
        {order.stockShortage && <Badge variant="destructive">Sin stock</Badge>}
      </div>

      {order.stockShortage && <StockShortageCard order={order} busy={busy} runAction={runAction} />}

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
          // GAP-03: el backend lo rechaza igual; deshabilitado, el motivo está en la alerta de arriba.
          disabled={busy || order.stockShortage}
          onClick={() => runAction(() => adminApi.orders.markShipped(order.id), 'Orden marcada como enviada')}
        >
          Marcar enviada
        </Button>
        <Button
          size="sm"
          variant="outline"
          disabled={busy || order.stockShortage}
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
                      <TableCell>
                        {item.productName}
                        {item.stockMissing && (
                          <Badge variant="destructive" className="ml-2">
                            Sin stock
                          </Badge>
                        )}
                      </TableCell>
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
                <Label htmlFor="order-status">Estado de la orden</Label>
                <Select
                  value={order.status}
                  // Una orden devuelta no vuelve de ahí por este selector: REFUNDED ya no es una
                  // opción, y dejarlo editable solo daría errores del backend.
                  disabled={busy || order.status === 'REFUNDED'}
                  onValueChange={(value) =>
                    runAction(() => adminApi.orders.updateStatus(order.id, value), 'Estado actualizado')
                  }
                >
                  <SelectTrigger id="order-status">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {withCurrent(ORDER_STATUSES, order.status).map((status) => (
                      <SelectItem key={status} value={status}>
                        {status}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="flex flex-col gap-2">
                <Label htmlFor="payment-status">Estado de pago</Label>
                <Select
                  value={order.paymentStatus}
                  disabled={busy || order.paymentStatus === 'REFUNDED'}
                  onValueChange={(value) =>
                    runAction(() => adminApi.orders.updatePaymentStatus(order.id, value), 'Estado de pago actualizado')
                  }
                >
                  <SelectTrigger id="payment-status">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {withCurrent(PAYMENT_STATUSES, order.paymentStatus).map((status) => (
                      <SelectItem key={status} value={status}>
                        {status}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
            </CardContent>
          </Card>

          <RefundCard order={order} busy={busy} runAction={runAction} />

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
                  // Guardar el seguimiento también marca la orden como enviada.
                  disabled={busy || order.stockShortage}
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

/**
 * GAP-03. La orden se cobró, pero al confirmar el pago no había stock para alguna línea (otro pago se llevó la
 * última unidad, o se vendió en el mostrador). No se despacha hasta que se reponga, se libere con una nota o se
 * reembolse.
 */
function StockShortageCard({
  order,
  busy,
  runAction,
}: {
  order: AdminOrderDetail
  busy: boolean
  runAction: (
    action: () => Promise<AdminOrderDetail>,
    successMessage: string | ((updated: AdminOrderDetail) => string),
  ) => Promise<void>
}) {
  const [note, setNote] = useState('')
  const missing = order.items.filter((item) => item.stockMissing)

  return (
    <Card className="border-destructive">
      <CardHeader>
        <CardTitle>Cobrada sin stock</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-3 text-sm">
        <p>
          Al confirmar el pago no había stock para{' '}
          {missing.map((item) => `${item.productName} (×${item.quantity})`).join(', ') || 'algunas líneas'}. No se
          puede despachar hasta resolverlo. Si no vas a conseguirlo, el reembolso está en la tarjeta de Reembolsos.
        </p>
        <div>
          <Button
            size="sm"
            variant="outline"
            disabled={busy}
            onClick={() =>
              runAction(
                () => adminApi.orders.retryStock(order.id),
                (updated) =>
                  updated.stockShortage
                    ? 'Todavía falta stock para algunas líneas'
                    : 'Stock descontado: la orden ya se puede despachar',
              )
            }
          >
            Reintentar descuento
          </Button>
        </div>
        <div className="flex flex-col gap-2 border-t pt-3">
          <Label htmlFor="stock-shortage-note">Nota</Label>
          <Input
            id="stock-shortage-note"
            value={note}
            onChange={(event) => setNote(event.target.value)}
            placeholder="Cómo se resolvió: se consiguió por fuera, se reembolsó lo que faltaba…"
            aria-describedby="stock-shortage-note-hint"
          />
          <p id="stock-shortage-note-hint" className="text-xs text-muted-foreground">
            Liberar no descuenta stock: solo permite despachar. Queda registrado.
          </p>
          <div>
            <Button
              size="sm"
              variant="outline"
              disabled={busy || note.trim() === ''}
              onClick={() =>
                runAction(() => adminApi.orders.releaseStockShortage(order.id, note), 'Bloqueo liberado')
              }
            >
              Liberar bloqueo
            </Button>
          </div>
        </div>
      </CardContent>
    </Card>
  )
}

/**
 * G4. Antes de esto, "reembolsar" era poner la orden en REFUNDED desde el selector de estado: la
 * base decía devuelto y la pasarela seguía diciendo cobrado. Aquí se pide el dinero de verdad.
 */
function RefundCard({
  order,
  busy,
  runAction,
}: {
  order: AdminOrderDetail
  busy: boolean
  runAction: (action: () => Promise<AdminOrderDetail>, successMessage: string) => Promise<void>
}) {
  const user = useAdminAuthStore((state) => state.user)
  const remaining = order.total - order.refundedAmount
  // Vacío = todo el saldo, que es lo que el backend entiende por un monto ausente.
  const [amount, setAmount] = useState('')
  const [reason, setReason] = useState('')

  if (!hasPermission(user, 'orders.refund')) {
    return null
  }

  const parsedAmount = amount.trim() === '' ? undefined : Number(amount)
  const amountInvalid =
    parsedAmount !== undefined && (Number.isNaN(parsedAmount) || parsedAmount <= 0 || parsedAmount > remaining)
  const canRefund = order.paymentStatus === 'PAID' && remaining > 0
  // GAP-02: lo pendiente ya cuenta en refundedAmount (el backend lo reserva), así que se dice aparte.
  const pendingAmount = order.refunds
    .filter((refund) => refund.status === 'PENDING')
    .reduce((sum, refund) => sum + refund.amount, 0)

  return (
    <Card>
      <CardHeader>
        <CardTitle>Reembolsos</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div className="flex flex-col gap-1 text-sm">
          <div className="flex justify-between">
            <span className="text-muted-foreground">Devuelto</span>
            <span>{formatCurrency(order.refundedAmount)}</span>
          </div>
          {pendingAmount > 0 && (
            <p className="text-xs text-muted-foreground">Incluye {formatCurrency(pendingAmount)} sin confirmar.</p>
          )}
          <div className="flex justify-between font-medium">
            <span>Por devolver</span>
            <span>{formatCurrency(remaining)}</span>
          </div>
        </div>

        {order.refunds.length > 0 && (
          <ul className="flex flex-col gap-2 border-t pt-3 text-sm">
            {order.refunds.map((refund) => (
              <li key={refund.id} className="flex flex-col gap-0.5">
                <div className="flex items-center justify-between gap-2">
                  {/* Un rechazo no movió dinero: se ve, pero tachado, porque no cuenta en lo devuelto. */}
                  <span className={refund.status === 'FAILED' ? 'text-muted-foreground line-through' : undefined}>
                    {formatCurrency(refund.amount)}
                  </span>
                  <div className="flex gap-1">
                    <Badge variant={REFUND_STATUS_VARIANTS[refund.status]}>{REFUND_STATUS_LABELS[refund.status]}</Badge>
                    <Badge variant={refund.type === 'EXTERNAL' ? 'outline' : 'secondary'}>{refund.type}</Badge>
                  </div>
                </div>
                {refund.reason && <span className="text-muted-foreground">{refund.reason}</span>}
                {refund.gatewayReference && (
                  <span className="text-xs text-muted-foreground">Ref. {refund.gatewayReference}</span>
                )}
                {refund.status === 'PENDING' && (
                  <PendingRefundResolver order={order} refundId={refund.id} busy={busy} runAction={runAction} />
                )}
              </li>
            ))}
          </ul>
        )}

        {canRefund ? (
          <div className="flex flex-col gap-2 border-t pt-3">
            <Label htmlFor="refund-amount">Monto (vacío = todo el saldo)</Label>
            <Input
              id="refund-amount"
              inputMode="numeric"
              placeholder={String(remaining)}
              value={amount}
              onChange={(event) => setAmount(event.target.value)}
              // PROD-24b. El mensaje de abajo es de este campo: se marca y se le une.
              aria-invalid={amountInvalid || undefined}
              aria-describedby={amountInvalid ? 'refund-amount-error' : undefined}
            />
            <Label htmlFor="refund-reason">Motivo</Label>
            <Input
              id="refund-reason"
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              placeholder="Producto defectuoso, arrepentimiento…"
              aria-describedby="refund-reason-hint"
            />
            {/* El botón de reembolso externo se deshabilita sin motivo: antes no se decía por qué. */}
            <p id="refund-reason-hint" className="text-xs text-muted-foreground">
              Obligatorio para registrar un reembolso hecho en la pasarela.
            </p>
            <Button
              variant="destructive"
              disabled={busy || amountInvalid}
              onClick={() =>
                runAction(() => adminApi.orders.refund(order.id, parsedAmount, reason), 'Reembolso realizado')
              }
            >
              Reembolsar
            </Button>
            <Button
              variant="outline"
              // El motivo es obligatorio aquí: es lo único que explica un movimiento que este
              // sistema no hizo y no puede verificar.
              disabled={busy || amountInvalid || reason.trim() === ''}
              onClick={() =>
                runAction(
                  () => adminApi.orders.recordExternalRefund(order.id, parsedAmount, reason),
                  'Reembolso externo registrado',
                )
              }
            >
              Registrar reembolso hecho en la pasarela
            </Button>
            {amountInvalid && (
              <p id="refund-amount-error" role="alert" className="text-sm text-destructive">
                El monto debe ser mayor que cero y no pasar del saldo.
              </p>
            )}
          </div>
        ) : (
          <p className="border-t pt-3 text-sm text-muted-foreground">
            {remaining <= 0 ? 'Esta orden ya se devolvió por completo.' : 'Solo se puede reembolsar una orden pagada.'}
          </p>
        )}
      </CardContent>
    </Card>
  )
}

/**
 * GAP-02. Un reembolso cuyo resultado no se supo (timeout, 5xx) queda PENDING y su importe reservado.
 * Aquí se cierra con lo que se vea en el panel de la pasarela; antes solo se podía con SQL. No es un
 * reembolso externo: ése sumaría otra fila y descontaría el mismo dinero dos veces.
 */
function PendingRefundResolver({
  order,
  refundId,
  busy,
  runAction,
}: {
  order: AdminOrderDetail
  refundId: number
  busy: boolean
  runAction: (action: () => Promise<AdminOrderDetail>, successMessage: string) => Promise<void>
}) {
  const [reference, setReference] = useState('')
  const [note, setNote] = useState('')
  const noteMissing = note.trim() === ''

  return (
    <div className="mt-1 flex flex-col gap-2 rounded-md border border-dashed p-2">
      <p className="text-xs text-muted-foreground">
        No se sabe si la pasarela hizo este reembolso. Revisa primero su panel. Esto no mueve dinero.
      </p>
      <Label htmlFor={`refund-${refundId}-reference`}>Referencia de la pasarela</Label>
      <Input
        id={`refund-${refundId}-reference`}
        value={reference}
        onChange={(event) => setReference(event.target.value)}
        placeholder="Solo si el reembolso se hizo"
      />
      <Label htmlFor={`refund-${refundId}-note`}>Nota</Label>
      <Input
        id={`refund-${refundId}-note`}
        value={note}
        onChange={(event) => setNote(event.target.value)}
        placeholder="Qué se vio en la pasarela"
      />
      <div className="flex flex-wrap gap-2">
        <Button
          size="sm"
          variant="outline"
          // Sin referencia no hay con qué cuadrar después la base y la pasarela.
          disabled={busy || noteMissing || reference.trim() === ''}
          onClick={() =>
            runAction(
              () => adminApi.orders.resolveRefund(order.id, refundId, 'COMPLETED', reference, note),
              'Reembolso confirmado como hecho',
            )
          }
        >
          Sí se hizo
        </Button>
        <Button
          size="sm"
          variant="outline"
          disabled={busy || noteMissing}
          onClick={() =>
            runAction(
              () => adminApi.orders.resolveRefund(order.id, refundId, 'FAILED', reference, note),
              'Reembolso marcado como no hecho',
            )
          }
        >
          No se hizo
        </Button>
      </div>
    </div>
  )
}
